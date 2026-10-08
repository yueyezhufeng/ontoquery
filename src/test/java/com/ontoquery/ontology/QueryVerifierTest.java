package com.ontoquery.ontology;

import com.ontoquery.support.Confidence;
import com.ontoquery.support.ConfidenceFactor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * QueryVerifier 单测：置信度公式边界（满分/同义词扣分/钳制下限/因子 delta/公式还原）。
 */
class QueryVerifierTest {

    private final QueryVerifier verifier = new QueryVerifier(null);

    @Test
    void fullScoreEntityYieldsHundred() {
        // 准备：全部实体精确实名、无歧义、无警告
        // 执行
        Confidence confidence = verifier.computeConfidence(Double.valueOf(1.0D), "实体平均得分 1（精确实体命中）",
                Integer.valueOf(0), Integer.valueOf(0));
        // 断言
        assertEquals(Integer.valueOf(100), confidence.getScore(), "满分实体应得 100");
        assertEquals("100 - 40*(1-1) - 12*0 - 8*0", confidence.getFormula(), "公式应逐项还原计算式");
        assertEquals(Integer.valueOf(0), confidence.getFactors().get(0).getDelta(), "实体因子不扣分");
        assertEquals(Integer.valueOf(0), confidence.getFactors().get(1).getDelta(), "歧义因子不扣分");
        assertEquals(Integer.valueOf(0), confidence.getFactors().get(2).getDelta(), "警告因子不扣分");
    }

    @Test
    void synonymAverageDeductsRoundedEntityPenalty() {
        // 准备：平均分 0.92，扣 40*(1-0.92)=3.2
        // 执行
        Confidence confidence = verifier.computeConfidence(Double.valueOf(0.92D), "实体平均得分 0.92（同义词命中）",
                Integer.valueOf(0), Integer.valueOf(0));
        // 断言
        assertEquals(Integer.valueOf(97), confidence.getScore(), "100-3.2 四舍五入应为 97");
        assertEquals("100 - 40*(1-0.92) - 12*0 - 8*0", confidence.getFormula(), "0.92 应原样出现在公式中");
        assertEquals(Integer.valueOf(-3), confidence.getFactors().get(0).getDelta(), "3.2 应四舍五入为 3 的扣分");
    }

    @Test
    void scoreIsClampedToLowerBoundFiftyFive() {
        // 准备：0.5 均分 + 3 歧义 + 5 警告 = 100-20-36-40=4，应钳制到 55
        // 执行
        Confidence confidence = verifier.computeConfidence(Double.valueOf(0.5D), "实体平均得分 0.5（类名推断）",
                Integer.valueOf(3), Integer.valueOf(5));
        // 断言
        assertEquals(Integer.valueOf(55), confidence.getScore(), "低于 55 的原始分应钳制到 55");
        List<ConfidenceFactor> factors = confidence.getFactors();
        assertEquals(Integer.valueOf(-20), factors.get(0).getDelta(), "实体因子应扣 20");
        assertEquals(Integer.valueOf(-36), factors.get(1).getDelta(), "歧义因子应扣 36（3 项 x 12）");
        assertEquals(Integer.valueOf(-40), factors.get(2).getDelta(), "警告因子应扣 40（5 项 x 8）");
        assertEquals("100 - 40*(1-0.5) - 12*3 - 8*5", confidence.getFormula(), "公式应还原全部扣分项");
    }

    @Test
    void eachAmbiguityDeductsTwelve() {
        // 准备
        // 执行
        Confidence confidence = verifier.computeConfidence(Double.valueOf(1.0D), "实体平均得分 1（精确实体命中）",
                Integer.valueOf(1), Integer.valueOf(0));
        // 断言
        assertEquals(Integer.valueOf(88), confidence.getScore(), "1 项歧义应得 88");
        assertEquals("未决歧义 1 项", confidence.getFactors().get(1).getLabel(), "歧义因子应列出项数");
        assertEquals(Integer.valueOf(-12), confidence.getFactors().get(1).getDelta(), "单项歧义扣 12");
    }

    @Test
    void eachVerifyWarningDeductsEight() {
        // 准备
        // 执行
        Confidence confidence = verifier.computeConfidence(Double.valueOf(1.0D), "实体平均得分 1（精确实体命中）",
                Integer.valueOf(0), Integer.valueOf(2));
        // 断言
        assertEquals(Integer.valueOf(84), confidence.getScore(), "2 条执行计划警告应得 84");
        assertEquals("执行计划警告 2 项", confidence.getFactors().get(2).getLabel(), "警告因子应列出条数");
        assertEquals(Integer.valueOf(-16), confidence.getFactors().get(2).getDelta(), "两条警告扣 16");
    }

    @Test
    void nullAverageScoreTreatedAsFull() {
        // 准备：无实体提及时均分按 1.0 计
        // 执行
        Confidence confidence = verifier.computeConfidence(null, "无实体提及（按满分计）",
                Integer.valueOf(0), Integer.valueOf(0));
        // 断言
        assertEquals(Integer.valueOf(100), confidence.getScore(), "无实体提及不应扣实体分");
        assertEquals(Integer.valueOf(0), confidence.getFactors().get(0).getDelta(), "实体因子 delta 为 0");
    }
}
