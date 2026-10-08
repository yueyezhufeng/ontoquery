package com.ontoquery.sql;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 唯一 SQL 执行入口（本体与传统两侧共用）：
 * 剥离注释后必须为单条 SELECT/WITH 语句；queryTimeout 10s；maxRows 500（501 判定截断）。
 * 任何 DML/DDL 关键字直接拒绝，保证演示只读安全。
 */
@Component
public class SafeQueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(SafeQueryExecutor.class);

    private static final int MAX_ROWS = 500;
    private static final int TIMEOUT_SECONDS = 10;
    private static final int PREVIEW_LENGTH = 30;

    /** 语句中不允许出现的关键字（大小写不敏感，字符串外匹配） */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|drop|create|alter|truncate|grant|revoke|call|lock|unlock|load|handler|merge|replace|rename|set)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern STRING_LITERAL = Pattern.compile("'([^']|'')*'");

    private final DataSource dataSource;

    public SafeQueryExecutor(JdbcTemplate jdbcTemplate) {
        this.dataSource = jdbcTemplate.getDataSource();
    }

    /** 校验：剥注释与首尾空白后以 SELECT 或 WITH 开头，且不含被禁关键字 */
    public void validate(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 为空");
        }
        String stripped = stripComments(sql).trim();
        String lower = stripped.toLowerCase();
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            throw new IllegalArgumentException("仅允许 SELECT 查询语句，已拒绝执行: "
                    + stripped.substring(0, Math.min(PREVIEW_LENGTH, stripped.length())));
        }
        if (FORBIDDEN.matcher(stripStringLiterals(lower)).find()) {
            throw new IllegalArgumentException("检测到被禁止的关键字，仅允许只读 SELECT 查询");
        }
    }

    /** 执行并返回结果表；语法/执行错误返回 error 而非抛出（管线要把错误展示给观众） */
    public ResultTable execute(String sql) {
        try {
            validate(sql);
        } catch (IllegalArgumentException e) {
            return ResultTable.failure(e.getMessage());
        }
        long start = System.nanoTime();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(MAX_ROWS + 1);
            stmt.setQueryTimeout(TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                return readTable(rs, start);
            }
        } catch (SQLException e) {
            log.warn("SQL 执行失败: {}", e.getMessage());
            return failedTable("SQL 执行失败: " + e.getMessage(), start);
        } catch (RuntimeException e) {
            log.error("SQL 执行基础设施异常", e);
            return failedTable("SQL 执行失败: " + e.getMessage(), start);
        }
    }

    private ResultTable readTable(ResultSet rs, long start) throws SQLException {
        ResultTable table = new ResultTable();
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        for (int i = 1; i <= colCount; i++) {
            table.getColumns().add(meta.getColumnLabel(i));
        }
        int count = 0;
        while (rs.next()) {
            if (count < MAX_ROWS) {
                List<String> row = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    Object v = rs.getObject(i);
                    row.add(v == null ? null : String.valueOf(v));
                }
                table.getRows().add(row);
            }
            count++;
        }
        table.setRowCount(count);
        table.setTruncated(count > MAX_ROWS);
        table.setElapsedMs((System.nanoTime() - start) / 1_000_000);
        return table;
    }

    private ResultTable failedTable(String message, long start) {
        ResultTable t = ResultTable.failure(message);
        t.setElapsedMs((System.nanoTime() - start) / 1_000_000);
        return t;
    }

    /** EXPLAIN 结果（供 QueryVerifier），错误时返回单行 error 表 */
    public List<List<String>> explain(String sql) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery("EXPLAIN " + sql)) {
                return readRows(rs);
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("EXPLAIN 失败: {}", e.getMessage());
            List<List<String>> rows = new ArrayList<>();
            rows.add(List.of("error"));
            rows.add(List.of(String.valueOf(e.getMessage())));
            return rows;
        }
    }

    private List<List<String>> readRows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<List<String>> rows = new ArrayList<>();
        List<String> cols = new ArrayList<>(colCount);
        for (int i = 1; i <= colCount; i++) {
            cols.add(meta.getColumnLabel(i));
        }
        rows.add(cols);
        while (rs.next()) {
            List<String> row = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                Object v = rs.getObject(i);
                row.add(v == null ? "" : String.valueOf(v));
            }
            rows.add(row);
        }
        return rows;
    }

    /** 标量查询便捷方法（linter 证据查询、benchmark 比对用），失败返回 null */
    public String scalar(String sql) {
        ResultTable t = execute(sql);
        if (t.getError() != null || t.getRows().isEmpty() || t.getRows().get(0).isEmpty()) {
            return null;
        }
        return t.getRows().get(0).get(0);
    }

    /** 去掉 -- 行注释与块注释 */
    static String stripComments(String sql) {
        String noBlock = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
        StringBuilder sb = new StringBuilder(noBlock.length());
        for (String line : noBlock.split("\n", -1)) {
            int idx = line.indexOf("--");
            if (idx >= 0) {
                // 本系统生成的 SQL 字符串不含 --，LLM 侧已先剥围栏
                sb.append(line, 0, idx);
            } else {
                sb.append(line);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 把单引号字符串字面量替换为等长空白，避免字符串内容误触发关键字匹配 */
    static String stripStringLiterals(String sql) {
        Matcher m = STRING_LITERAL.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, " ".repeat(m.group().length()));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
