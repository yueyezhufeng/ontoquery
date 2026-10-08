package com.ontoquery.risk;

import com.ontoquery.support.RiskFinding;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlRiskLinter 单元测试（不触库的规则：证据查询注入 null JdbcTemplate 时被安全跳过）。
 *
 * @author 月夜烛峰
 */
class SqlRiskLinterTest {

    private static final String CORE_QUESTION =
            "近一个月诊断为2型糖尿病且HbA1c大于7的患者中，使用二甲双胍治疗的有多少人？";

    private static final String FLAWED_SQL =
            "SELECT COUNT(*) AS patient_count\n"
                    + "FROM dim_patient p\n"
                    + "LEFT JOIN fact_diagnosis d ON d.patient_id = p.patient_id\n"
                    + "LEFT JOIN fact_lab_result l ON l.patient_id = p.patient_id\n"
                    + "LEFT JOIN fact_medication m ON m.patient_id = p.patient_id\n"
                    + "WHERE d.disease_name LIKE '%2型糖尿病%'\n"
                    + "  AND l.test_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)\n"
                    + "  AND l.test_name LIKE '%HbA1c%'\n"
                    + "  AND l.result_value > 7\n"
                    + "  AND m.drug_name LIKE '%二甲双胍%'";

    private final SqlRiskLinter linter = new SqlRiskLinter(null);

    private List<String> codes(List<RiskFinding> risks) {
        return risks.stream().map(RiskFinding::getCode).collect(Collectors.toList());
    }

    @Test
    void testFlawedSqlHitsExpectedRules() {
        List<String> codes = codes(linter.lint(FLAWED_SQL, CORE_QUESTION));
        assertTrue(codes.contains("FUZZY_LIKE"), "前导通配 LIKE 应命中 FUZZY_LIKE: " + codes);
        assertTrue(codes.contains("JOIN_FANOUT"), "JOIN+COUNT(*) 应命中 JOIN_FANOUT: " + codes);
        assertTrue(codes.contains("TIME_MISATTACH"), "时间挂错列应命中 TIME_MISATTACH: " + codes);
        assertTrue(codes.contains("VALUE_RANGE_INFO"), "HbA1c 比较应命中值域提示: " + codes);
    }

    @Test
    void testCleanSqlHitsNothing() {
        String cleanSql = "SELECT COUNT(DISTINCT p.patient_id) AS patient_count FROM dim_patient p "
                + "WHERE EXISTS (SELECT 1 FROM fact_diagnosis t0 WHERE t0.patient_id = p.patient_id "
                + "AND t0.disease_code = 'E11' "
                + "AND t0.diagnosis_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY))";
        List<RiskFinding> risks = linter.lint(cleanSql, CORE_QUESTION);
        assertEquals(0, risks.size(), "EXISTS+标准码+正确时间挂靠不应命中风险: " + codes(risks));
    }

    @Test
    void testNonMysqlSyntax() {
        List<String> codes = codes(linter.lint("SELECT * FROM dim_patient WHERE name ILIKE '%x%'", "叫什么"));
        assertTrue(codes.contains("NON_MYSQL"), "ILIKE 应命中 NON_MYSQL: " + codes);
    }

    @Test
    void testMissingAggregation() {
        List<String> codes = codes(linter.lint("SELECT patient_id FROM dim_patient", "患者有多少人"));
        assertTrue(codes.contains("MISSING_AGG"), "问数量无聚合应命中 MISSING_AGG: " + codes);
    }

    @Test
    void testTimeConditionMissing() {
        List<String> codes = codes(linter.lint("SELECT COUNT(*) FROM dim_patient", CORE_QUESTION));
        assertTrue(codes.contains("TIME_MISATTACH"), "问题有时间词而 SQL 无时间条件应命中: " + codes);
    }

    @Test
    void testEmptySqlYieldsNoRisks() {
        assertEquals(0, linter.lint(null, CORE_QUESTION).size(), "null SQL 返回空列表");
        assertEquals(0, linter.lint("   ", CORE_QUESTION).size(), "空白 SQL 返回空列表");
    }
}
