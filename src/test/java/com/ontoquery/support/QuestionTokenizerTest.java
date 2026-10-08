package com.ontoquery.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * QuestionTokenizer 单元测试：核心问题各槽位抽取。
 *
 * @author 月夜烛峰
 */
class QuestionTokenizerTest {

    private static final String CORE_QUESTION =
            "近一个月诊断为2型糖尿病且HbA1c大于7的患者中，使用二甲双胍治疗的有多少人？";

    @Test
    void testDiseasePhraseCopiesUserWords() {
        assertEquals("2型糖尿病", QuestionTokenizer.extractDiseasePhrase(CORE_QUESTION),
                "应从「诊断为X且」结构抄出用户原词");
    }

    @Test
    void testDrugPhraseCopiesUserWords() {
        assertEquals("二甲双胍", QuestionTokenizer.extractDrugPhrase(CORE_QUESTION),
                "应从「使用X治疗」结构抄出用户原词");
    }

    @Test
    void testLabPhraseLatin() {
        assertEquals("HbA1c", QuestionTokenizer.extractLabPhrase(CORE_QUESTION), "应抽出拉丁检验词");
    }

    @Test
    void testCompareSlot() {
        QuestionTokenizer.CompareSlot slot = QuestionTokenizer.extractCompare(CORE_QUESTION);
        assertNotNull(slot, "核心问题含「大于7」应有比较槽位");
        assertEquals(">", slot.getOp(), "「大于」应解析为 >");
        assertEquals("7", slot.getValue(), "阈值应为 7");
    }

    @Test
    void testTimeWindow() {
        QuestionTokenizer.TimeWindow time = QuestionTokenizer.extractTimeWindow(CORE_QUESTION);
        assertNotNull(time, "核心问题含「近一个月」应有时间窗");
        assertEquals(Integer.valueOf(30), time.getDays(), "近一个月应为 30 天");
        assertEquals("近一个月", time.getLabel(), "标签应为近一个月");
    }

    @Test
    void testNoSlots() {
        assertNull(QuestionTokenizer.extractTimeWindow("全部患者有多少人"), "无时间词应返回 null");
        assertNull(QuestionTokenizer.extractCompare("列出所有患者"), "无比较词应返回 null");
    }

    @Test
    void testTokensFilterShort() {
        List<String> tokens = QuestionTokenizer.tokens("近一个月诊断为2型糖尿病");
        // 本分词器刻意朴素：CJK 连续段为一个 token（无词典切分），单字符段被过滤
        assertEquals("近一个月诊断为", tokens.get(0), "CJK 连续段应作为整体 token: " + tokens);
        assertEquals(false, tokens.contains("2"), "单字符拉丁 token 应被过滤: " + tokens);
        assertEquals(false, tokens.contains("一"), "不应出现独立的单字 token: " + tokens);
    }
}
