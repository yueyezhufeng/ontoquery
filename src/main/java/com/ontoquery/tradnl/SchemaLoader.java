package com.ontoquery.tradnl;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 传统管线第一步：读取业务表真实 DDL（SHOW CREATE TABLE），进程内缓存。
 * DDL 既是给 LLM 的提示词素材，也是后续列名匹配的数据源。
 *
 * @author 月夜烛峰
 */
@Component
public class SchemaLoader {

    /** 参与问答的业务表（传统侧无本体知识，只有这 9 张物理表） */
    private static final List<String> BUSINESS_TABLES = List.of(
            "dim_patient", "dim_department", "dim_disease", "dim_drug", "dim_lab_test",
            "fact_visit", "fact_diagnosis", "fact_lab_result", "fact_medication");

    private final JdbcTemplate jdbcTemplate;

    /** DDL 缓存（表结构运行期不变） */
    private volatile String cachedDdl;

    public SchemaLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> businessTables() {
        return BUSINESS_TABLES;
    }

    /** 拼接全部业务表的 SHOW CREATE TABLE 输出（懒加载、只拼一次） */
    public String loadDdl() {
        String result = cachedDdl;
        if (result != null) {
            return result;
        }
        synchronized (this) {
            if (cachedDdl != null) {
                return cachedDdl;
            }
            StringBuilder sb = new StringBuilder(8192);
            for (String table : BUSINESS_TABLES) {
                String createSql = jdbcTemplate.queryForObject(
                        "SHOW CREATE TABLE " + table, (rs, rowNum) -> rs.getString(2));
                sb.append("-- 表 ").append(table).append('\n')
                  .append(createSql).append(";\n\n");
            }
            cachedDdl = sb.toString();
            return cachedDdl;
        }
    }

    /** 每张表的列名清单（列名匹配用） */
    public List<String> loadColumns(String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE() AND table_name = ? ORDER BY ordinal_position",
                String.class, table);
    }

    /** 全部业务表的列名 + 中文注释（列名匹配数据源） */
    public List<SchemaLinker.SchemaColumn> loadSchemaColumns() {
        String placeholders = String.join(",", java.util.Collections.nCopies(BUSINESS_TABLES.size(), "?"));
        return jdbcTemplate.query(
                "SELECT table_name, column_name, column_comment FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE() AND table_name IN (" + placeholders + ")"
                        + " ORDER BY table_name, ordinal_position",
                (rs, rowNum) -> new SchemaLinker.SchemaColumn(rs.getString(1), rs.getString(2), rs.getString(3)),
                BUSINESS_TABLES.toArray());
    }
}
