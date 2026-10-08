package com.ontoquery.ontology;

import com.ontoquery.sql.SafeQueryExecutor;
import com.ontoquery.support.Confidence;
import com.ontoquery.support.ConfidenceFactor;

import java.util.ArrayList;
import java.util.List;

/**
 * 校验与置信度：SafeQueryExecutor.explain() 检查全表扫描 -> 警告；
 * 置信度公式 clamp(100 - 40*(1-avg实体分) - 12*未决歧义数 - 8*verify警告数, 55, 100)，
 * factors 逐项列 delta，formula 字符串还原计算式。
 */
public class QueryVerifier {

    private static final int MIN_SCORE = 55;
    private static final int MAX_SCORE = 100;
    private static final int PENALTY_ENTITY = 40;
    private static final int PENALTY_AMBIGUITY = 12;
    private static final int PENALTY_WARNING = 8;
    private static final String TYPE_COLUMN = "type";
    private static final String TABLE_COLUMN = "table";
    private static final String ROWS_COLUMN = "rows";
    private static final String FULL_SCAN_TYPE = "ALL";
    /** 预估扫描行数低于该值不告警：锚表 dim_patient 2 万行全表扫属正常形态 */
    private static final long FULL_SCAN_ROWS_THRESHOLD = 30000L;

    private final SafeQueryExecutor executor;

    public QueryVerifier(SafeQueryExecutor executor) {
        this.executor = executor;
    }

    /**
     * EXPLAIN 检查：type=ALL 且预估扫描行数超过阈值才告警（物化伪表 rows=NULL、
     * 两万行级锚表全表扫属正常形态，不产生噪音；行数未知不告警）。
     * 返回警告文案列表（空为通过）。
     */
    public List<String> verify(String sql) {
        List<String> warnings = new ArrayList<>();
        List<List<String>> explain = executor.explain(sql);
        if (explain.size() < 2) {
            return warnings;
        }
        if ("error".equals(explain.get(0).get(0))) {
            warnings.add("EXPLAIN 无法解析执行计划，跳过全表扫描检查");
            return warnings;
        }
        int typeIdx = columnIndexOf(explain.get(0), TYPE_COLUMN);
        int tableIdx = columnIndexOf(explain.get(0), TABLE_COLUMN);
        int rowsIdx = columnIndexOf(explain.get(0), ROWS_COLUMN);
        if (typeIdx < 0) {
            return warnings;
        }
        for (List<String> row : explain.subList(1, explain.size())) {
            if (typeIdx >= row.size()) {
                continue;
            }
            if (!FULL_SCAN_TYPE.equalsIgnoreCase(row.get(typeIdx))) {
                continue;
            }
            Long estimateRows = parseLong(row, rowsIdx);
            if (estimateRows == null || estimateRows.longValue() <= FULL_SCAN_ROWS_THRESHOLD) {
                continue;
            }
            String table = tableIdx >= 0 && tableIdx < row.size() ? row.get(tableIdx) : "?";
            warnings.add("表 " + table + " 执行计划为全表扫描（type=ALL，预估 "
                    + estimateRows.longValue() + " 行），建议核查索引");
        }
        return warnings;
    }

    /** 解析 EXPLAIN rows 列；物化伪表该列为 NULL，返回 null 表示未知 */
    private Long parseLong(List<String> row, int rowsIdx) {
        if (rowsIdx < 0 || rowsIdx >= row.size()) {
            return null;
        }
        String value = row.get(rowsIdx);
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int columnIndexOf(List<String> header, String column) {
        for (int i = 0; i < header.size(); i++) {
            if (column.equalsIgnoreCase(header.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 置信度计算（纯函数，不触库）：avgScore 为空按 1.0 计。
     */
    public Confidence computeConfidence(Double avgScore, String entityLabel, Integer ambiguityCount,
            Integer warningCount) {
        double avg = avgScore == null ? 1.0D : avgScore.doubleValue();
        int ambiguity = ambiguityCount == null ? 0 : ambiguityCount.intValue();
        int warning = warningCount == null ? 0 : warningCount.intValue();

        double entityPenalty = PENALTY_ENTITY * (1.0D - avg);
        double ambiguityPenalty = PENALTY_AMBIGUITY * ambiguity;
        double warningPenalty = PENALTY_WARNING * warning;
        double raw = 100.0D - entityPenalty - ambiguityPenalty - warningPenalty;
        int score = (int) Math.round(Math.max(MIN_SCORE, Math.min(MAX_SCORE, raw)));

        List<ConfidenceFactor> factors = new ArrayList<>(3);
        factors.add(new ConfidenceFactor(entityLabel, Integer.valueOf((int) Math.round(entityPenalty)) * -1));
        if (ambiguity == 0) {
            factors.add(new ConfidenceFactor("无未决歧义", Integer.valueOf(0)));
        } else {
            factors.add(new ConfidenceFactor("未决歧义 " + ambiguity + " 项",
                    Integer.valueOf(-PENALTY_AMBIGUITY * ambiguity)));
        }
        if (warning == 0) {
            factors.add(new ConfidenceFactor("执行计划无警告", Integer.valueOf(0)));
        } else {
            factors.add(new ConfidenceFactor("执行计划警告 " + warning + " 项",
                    Integer.valueOf(-PENALTY_WARNING * warning)));
        }

        String formula = "100 - " + PENALTY_ENTITY + "*(1-" + formatScore(avg) + ") - " + PENALTY_AMBIGUITY + "*"
                + ambiguity + " - " + PENALTY_WARNING + "*" + warning;
        return new Confidence(Integer.valueOf(score), formula, factors);
    }

    /** 均分展示：1.0 -> 1，0.92 -> 0.92 */
    private String formatScore(double value) {
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
