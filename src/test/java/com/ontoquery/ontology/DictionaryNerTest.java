package com.ontoquery.ontology;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DictionaryNer 单测：Trie 最长匹配 / 同义词 / 时间词 / 中文数字 / 意图词。
 */
class DictionaryNerTest {

    private final DictionaryNer ner = new DictionaryNer(OntologyFixtures.fullModel());

    @Test
    void longestMatchPrefersLongerTerm() {
        // 准备：高于等于 为 高于 的更长词；HbA1c% 为 HbA1c 的更长词
        // 执行
        List<Mention> mentions = ner.recognize("HbA1c%高于等于7");
        // 断言
        assertEquals("HbA1c%", mentions.get(0).getText(), "应最长匹配 HbA1c% 而非 HbA1c");
        assertEquals("LAB_HBA1C", mentions.get(0).getInstanceCode(), "HbA1c% 应命中 LAB_HBA1C");
        assertEquals("高于等于", mentions.get(1).getText(), "应最长匹配 高于等于 而非 高于");
        assertEquals(">=", mentions.get(1).getCompareOperator(), "高于等于 应解析为 >=");
        assertEquals("7", mentions.get(2).getText(), "数字 7 应单独成提及");
    }

    @Test
    void synonymMatchScoresNinetyTwo() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        DictionaryNer synonymNer = new DictionaryNer(model);
        // 执行
        List<Mention> mentions = synonymNer.recognize("使用格华止治疗");
        // 断言
        assertEquals(1, mentions.size(), "应只识别出实体提及");
        Mention mention = mentions.get(0);
        assertEquals("D_METFORMIN", mention.getInstanceCode(), "格华止应命中 D_METFORMIN");
        assertEquals(Double.valueOf(0.92D), mention.getScore(), "同义词命中得分应为 0.92");
        assertEquals(Mention.VIA_SYNONYM, mention.getVia(), "命中方式应为同义词");
    }

    @Test
    void exactInstanceNameScoresOne() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        DictionaryNer nameNer = new DictionaryNer(model);
        // 执行
        List<Mention> mentions = nameNer.recognize("诊断为2型糖尿病");
        // 断言
        assertEquals("E11", mentions.get(0).getInstanceCode(), "2型糖尿病应命中 E11");
        assertEquals(Double.valueOf(1.0D), mentions.get(0).getScore(), "精确实例名得分应为 1.0");
        assertEquals(Mention.VIA_INSTANCE_NAME, mentions.get(0).getVia(), "命中方式应为实例名");
    }

    @Test
    void instanceCodeIsInDictionary() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        DictionaryNer codeNer = new DictionaryNer(model);
        // 执行
        List<Mention> mentions = codeNer.recognize("编码E11的患者");
        // 断言
        assertEquals("E11", mentions.get(0).getInstanceCode(), "实例编码 E11 应直接命中");
        assertEquals(Double.valueOf(1.0D), mentions.get(0).getScore(), "实例编码得分应为 1.0");
        assertEquals(Mention.VIA_INSTANCE_CODE, mentions.get(0).getVia(), "命中方式应为实例编码");
    }

    @Test
    void classNameMatchesWithoutInstance() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        DictionaryNer classNer = new DictionaryNer(model);
        // 执行
        List<Mention> mentions = classNer.recognize("某种疾病很常见");
        // 断言
        Mention mention = mentions.get(0);
        assertEquals("CLS_DISEASE", mention.getClassCode(), "疾病应命中类 CLS_DISEASE");
        assertNull(mention.getInstanceCode(), "类名命中不应有实例编码");
        assertEquals(Double.valueOf(0.85D), mention.getScore(), "类名命中得分应为 0.85");
    }

    @Test
    void timeWordsResolveToDays() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        DictionaryNer timeNer = new DictionaryNer(model);
        // 执行
        List<Mention> week = timeNer.recognize("近一周");
        List<Mention> month = timeNer.recognize("近一个月");
        List<Mention> quarter = timeNer.recognize("近三个月");
        List<Mention> half = timeNer.recognize("近半年");
        List<Mention> year = timeNer.recognize("近一年");
        // 断言
        assertEquals(Integer.valueOf(7), week.get(0).getTimeDays(), "近一周应为 7 天");
        assertEquals(Integer.valueOf(30), month.get(0).getTimeDays(), "近一个月应为 30 天");
        assertEquals(Integer.valueOf(90), quarter.get(0).getTimeDays(), "近三个月应为 90 天");
        assertEquals(Integer.valueOf(180), half.get(0).getTimeDays(), "近半年应为 180 天");
        assertEquals(Integer.valueOf(365), year.get(0).getTimeDays(), "近一年应为 365 天");
    }

    @Test
    void chineseNumbersAreParsed() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        DictionaryNer numberNer = new DictionaryNer(model);
        // 执行
        List<Mention> seventy = numberNer.recognize("大于七十");
        List<Mention> thirty = numberNer.recognize("前三十位");
        // 断言
        assertEquals(Double.valueOf(70D), seventy.get(1).getNumericValue(), "七十应解析为 70");
        assertEquals(Mention.TYPE_NUMBER, seventy.get(1).getType(), "七十应为数字提及");
        assertEquals(Double.valueOf(30D), thirty.get(0).getNumericValue(), "三十应解析为 30");
    }

    @Test
    void intentWordsAreRecognized() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        DictionaryNer intentNer = new DictionaryNer(model);
        // 执行
        List<Mention> count = intentNer.recognize("有多少人");
        List<Mention> avg = intentNer.recognize("平均");
        List<Mention> top = intentNer.recognize("Top");
        List<Mention> list = intentNer.recognize("哪些药品");
        // 断言
        assertEquals(Mention.INTENT_COUNT, count.get(0).getIntentKind(), "多少人应为计数意图");
        assertEquals(Mention.INTENT_AVG, avg.get(0).getIntentKind(), "平均应为均值意图");
        assertEquals(Mention.INTENT_TOP_N, top.get(0).getIntentKind(), "Top 应为 TopN 意图（大小写不敏感）");
        assertEquals(Mention.INTENT_LIST, list.get(0).getIntentKind(), "哪些应为清单意图");
        assertEquals(Mention.TYPE_ENTITY, list.get(1).getType(), "哪些之后药品仍应识别为实体");
    }

    @Test
    void goldenQuestionYieldsExpectedMentionMix() {
        // 准备
        String question = "近一个月诊断为2型糖尿病且HbA1c大于7的患者中，使用二甲双胍治疗的有多少人？";
        // 执行
        List<Mention> mentions = ner.recognize(question);
        // 断言
        int entity = 0;
        int time = 0;
        int compare = 0;
        int number = 0;
        int intent = 0;
        for (Mention mention : mentions) {
            switch (mention.getType()) {
                case Mention.TYPE_ENTITY: entity = entity + 1; break;
                case Mention.TYPE_TIME: time = time + 1; break;
                case Mention.TYPE_COMPARE: compare = compare + 1; break;
                case Mention.TYPE_NUMBER: number = number + 1; break;
                default: intent = intent + 1; break;
            }
        }
        assertEquals(4, entity, "黄金问题应识别 4 个实体（2型糖尿病/HbA1c/患者/二甲双胍）");
        assertEquals(1, time, "黄金问题应识别 1 个时间词");
        assertEquals(1, compare, "黄金问题应识别 1 个比较词");
        assertEquals(1, number, "黄金问题应识别 1 个数字");
        assertEquals(1, intent, "黄金问题应识别 1 个意图词（多少人）");
        assertTrue(mentions.get(0).getBegin() <= mentions.get(mentions.size() - 1).getBegin(),
                "提及应按位置升序输出");
    }
}
