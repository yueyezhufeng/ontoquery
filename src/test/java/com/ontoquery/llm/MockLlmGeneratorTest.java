package com.ontoquery.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MockLlmGenerator 单元测试：核心问题的典型缺陷 SQL（确定性）。
 *
 * @author 月夜烛峰
 */
class MockLlmGeneratorTest {

    private static final String CORE_QUESTION =
            "近一个月诊断为2型糖尿病且HbA1c大于7的患者中，使用二甲双胍治疗的有多少人？";

    private final MockLlmGenerator generator = new MockLlmGenerator();

    @Test
    void testContentWrappedInProseAndFence() {
        String content = generator.generateContent(CORE_QUESTION);
        assertTrue(content.startsWith("好的"), "Mock 输出应带解释前缀（演示清洗必要性）");
        assertTrue(content.contains("```"), "Mock 输出应带 markdown 围栏");
        assertEquals(generator.generateContent(CORE_QUESTION), content, "同问题输出必须确定性一致");
    }

    @Test
    void testCoreQuestionBuildsTypicalFlawedSql() {
        String sql = generator.buildSql(CORE_QUESTION);
        assertTrue(sql.contains("LIKE '%2型糖尿病%'"), "缺陷一：LIKE 用户原词: " + sql);
        assertTrue(sql.contains("LIKE '%HbA1c%'"), "检验词也应按原词 LIKE: " + sql);
        assertTrue(sql.contains("LIKE '%二甲双胍%'"), "药品词按原词 LIKE: " + sql);
        assertTrue(sql.contains("COUNT(*)"), "缺陷二：COUNT(*) 未去重: " + sql);
        assertTrue(sql.contains("l.test_date"), "缺陷三：时间条件挂到检验日期列: " + sql);
        assertTrue(sql.contains("INTERVAL 30 DAY"), "近一个月应转为 30 天窗口: " + sql);
    }

    @Test
    void testExtractedSqlFromMockContent() {
        String sql = SqlExtractor.extract(generator.generateContent(CORE_QUESTION));
        assertNotNull(sql, "Mock 输出必须能被 SqlExtractor 抽取出 SQL");
        assertTrue(sql.startsWith("SELECT"), "抽取结果应以 SELECT 开头");
    }

    @Test
    void testBareQuestionFallsBack() {
        String sql = generator.buildSql("全部患者有多少人");
        assertTrue(sql.contains("dim_patient"), "无槽位问题应回退基础查询: " + sql);
    }
}
