package com.ontoquery.ontology.ops;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实例映射 valueExpr 白名单校验：语法正则 + 表白名单 + 列存在性。
 *
 * @author 月夜烛峰
 */
public final class ValueExprValidator {

    private static final Pattern EXPR_PATTERN = Pattern.compile(
            "^\\{t\\}\\.([a-z_][a-z0-9_]*)\\s*(=|<>|!=|>=|<=|>|<)\\s*('[^']*'|-?\\d+(?:\\.\\d+)?)$");

    private static final Pattern COLUMN_PATTERN = Pattern.compile("\\{t\\}\\.([a-z_][a-z0-9_]*)");

    private static final Set<String> TABLE_WHITELIST = Set.of(
            "dim_patient", "dim_disease", "dim_lab_test", "dim_drug", "dim_department",
            "fact_visit", "fact_diagnosis", "fact_lab_result", "fact_medication");

    private ValueExprValidator() {
    }

    /** 语法校验：通过返回 null，否则返回失败原因。 */
    public static String syntaxRejectReason(String valueExpr) {
        if (valueExpr == null || valueExpr.isBlank()) {
            return "valueExpr 不能为空";
        }
        if (!EXPR_PATTERN.matcher(valueExpr).matches()) {
            return "valueExpr 必须形如 {t}.列名 操作符 '值'（列名小写，值不含引号）";
        }
        return null;
    }

    /** 完整校验：语法 + 表白名单 + 列存在性；通过返回 null。 */
    public static String rejectReason(String table, String column, String valueExpr, JdbcTemplate jdbc) {
        String syntax = syntaxRejectReason(valueExpr);
        if (syntax != null) {
            return syntax;
        }
        if (table == null || !TABLE_WHITELIST.contains(table)) {
            return "表不在业务表白名单内: " + table;
        }
        if (column == null || column.isBlank()) {
            return "column 不能为空";
        }
        Long colCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Long.class, table, column);
        if (colCount == null || colCount.intValue() == 0) {
            return "列不存在: " + table + "." + column;
        }
        Matcher matcher = COLUMN_PATTERN.matcher(valueExpr);
        if (matcher.find() && !column.equals(matcher.group(1))) {
            return "valueExpr 中列名与 mapping.column 不一致: " + matcher.group(1);
        }
        return null;
    }

    /** 供上层判断表白名单（列表页下拉用） */
    public static List<String> tableWhitelist() {
        return List.copyOf(TABLE_WHITELIST);
    }
}
