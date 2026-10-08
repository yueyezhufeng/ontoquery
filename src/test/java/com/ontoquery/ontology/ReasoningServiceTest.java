package com.ontoquery.ontology;

import com.ontoquery.ontology.trace.TraceContext;
import com.ontoquery.ontology.trace.TraceContext.TraceItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReasoningService 单测：时间就近挂靠 / 值域校验 / 歧义检测 / 子类闭包 / 同义归一。
 */
class ReasoningServiceTest {

    @Test
    void timeAttachesToNearestDiagnosisEvent() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult run = OntologyFixtures.runToPlan(model,
                "近一个月诊断为2型糖尿病的患者有多少人");
        // 断言
        assertEquals(1, run.plan.getConstraints().size(), "应形成 1 个诊断约束");
        QueryPlan.Constraint constraint = run.plan.getConstraints().get(0);
        assertEquals("CLS_DIAGNOSIS", constraint.getHostClassCode(), "约束宿主应为诊断记录");
        assertEquals(Integer.valueOf(30), constraint.getTimeDays(), "近一个月应挂靠 30 天");
        assertEquals("diagnosis_date", constraint.getDateColumn(), "时间应挂到 diagnosis_date");
        assertTrue(containsText(run.reasoningStep, "未挂到 fact_lab_result.test_date"),
                "trace 应明确说明未挂到 test_date");
        assertTrue(containsText(run.reasoningStep, "未挂到 fact_medication.prescribe_date"),
                "trace 应明确说明未挂到 prescribe_date");
    }

    @Test
    void timeAttachesToMedicationByProximity() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult run = OntologyFixtures.runToPlan(model, "近三个月使用格华止的患者");
        // 断言
        QueryPlan.Constraint constraint = run.plan.getConstraints().get(0);
        assertEquals("CLS_MEDICATION", constraint.getHostClassCode(), "使用 应就近挂到用药记录");
        assertEquals("prescribe_date", constraint.getDateColumn(), "时间应挂到 prescribe_date");
        assertEquals(Integer.valueOf(90), constraint.getTimeDays(), "近三个月应为 90 天");
    }

    @Test
    void valueWithinCredibleRangeIsLegal() {
        // 准备：HbA1c 正常 4~6，可信 3~20，7 合法
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult run = OntologyFixtures.runToPlan(model, "HbA1c大于7的患者");
        // 断言
        QueryPlan.Constraint constraint = run.plan.getConstraints().get(0);
        assertEquals(">", constraint.getValueOperator(), "比较符应为 >");
        assertEquals("result_value", constraint.getValueColumn(), "数值应挂 result_value 列");
        assertEquals(Double.valueOf(7D), constraint.getValueNumber(), "数值应为 7");
        assertFalse(hasWarningLike(run.plan, "单位"), "7 在可信区间内，不应出现单位可疑警告");
    }

    @Test
    void valueOutsideCredibleRangeWarnsUnitSuspicious() {
        // 准备：70 超出可信区间 [3,20]
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult run = OntologyFixtures.runToPlan(model, "HbA1c大于70的患者");
        // 断言
        assertTrue(hasWarningLike(run.plan, "单位"), "70 超出可信区间应产生单位可疑警告");
        assertTrue(containsLevel(run.reasoningStep, "warn"), "trace 应包含 warn 级文本");
        assertEquals(Double.valueOf(70D), run.plan.getConstraints().get(0).getValueNumber(),
                "可疑值仍按原值生成条件");
    }

    @Test
    void crossClassSameNameTermIsAmbiguous() {
        // 准备：疾病与药品两个类各有同名实例 红斑
        OntologyModel model = OntologyFixtures.fullModel();
        model.addInstance("D_RED_SPOT", "CLS_DISEASE", "红斑", null);
        model.addInstance("X_RED_CREAM", "CLS_DRUG", "红斑", null);
        // 执行
        OntologyFixtures.PlanAndResult run = OntologyFixtures.runToPlan(model, "红斑的患者");
        // 断言
        assertEquals(Integer.valueOf(1), run.plan.getAmbiguityCount(), "跨类同名应计 1 项未决歧义");
        assertTrue(containsText(run.reasoningStep, "红斑"), "trace 应写出歧义词");
        assertTrue(containsLevel(run.reasoningStep, "warn"), "歧义应为 warn 级");
    }

    @Test
    void noAmbiguityCountsZero() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult run = OntologyFixtures.runToPlan(model, "使用格华止的患者");
        // 断言
        assertEquals(Integer.valueOf(0), run.plan.getAmbiguityCount(), "无歧义时应计 0");
        assertTrue(containsText(run.reasoningStep, "无未决歧义"), "trace 应给出无歧义结论");
    }

    @Test
    void subclassClosureExpandsInstances() {
        // 准备：fixture 自带糖尿病类层级（E10）与类级同义词 糖尿病，另补挂 E14 到糖尿病类
        OntologyModel model = OntologyFixtures.fullModel();
        model.addInstance("E14", "CLS_DIABETES", "未特指的糖尿病", null);
        model.addMapping(OntologyModel.MAPPING_KIND_INSTANCE, "E14", "fact_diagnosis", "disease_code", null,
                "{t}.disease_code = 'E14'");
        // 执行
        OntologyFixtures.PlanAndResult run = OntologyFixtures.runToPlan(model, "诊断为糖尿病的患者");
        // 断言
        assertEquals(1, run.plan.getConstraints().size(), "类级同义词应形成 1 个约束");
        List<String> codes = run.plan.getConstraints().get(0).getInstanceCodes();
        assertTrue(codes.contains("E10"), "闭包应包含糖尿病类实例 E10");
        assertTrue(codes.contains("E14"), "闭包应包含后补实例 E14");
        assertFalse(codes.contains("E11"), "闭包不应越过类边界包含疾病类其余实例 E11");
        assertTrue(run.buildResult.getSql().contains(" OR "), "多实例应生成 OR 谓词: "
                + run.buildResult.getSql());
    }

    @Test
    void synonymNormalizationWritesTrace() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult run = OntologyFixtures.runToPlan(model, "使用格华止的患者");
        // 断言
        boolean found = false;
        for (TraceItem item : run.reasoningStep.getItems()) {
            if ("kv".equals(item.getType()) && "同义归一".equals(item.getLabel())
                    && "格华止".equals(item.getFrom()) && item.getTo().contains("D_METFORMIN")
                    && "同义词表".equals(item.getRule())) {
                found = true;
            }
        }
        assertTrue(found, "同义归一决策应写入 kv 型 trace（格华止 -> D_METFORMIN，rule=同义词表）");
        assertNotNull(run.plan.getIntent(), "意图应有默认值");
        assertEquals(Mention.INTENT_COUNT, run.plan.getIntent(), "默认意图应为计数");
    }

    @Test
    void classMentionBecomesValueMeasureHost() {
        // 准备：检验结果类无实例，修复前 >7 会误挂到二甲双胍剂量列
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult run = OntologyFixtures.runToPlan(model,
                "使用格华止的患者中检验结果值大于7的有多少人");
        // 断言
        assertEquals(2, run.plan.getConstraints().size(), "应形成用药与检验两个约束");
        QueryPlan.Constraint lab = run.plan.getConstraints().get(1);
        assertEquals("CLS_LAB_RESULT", lab.getHostClassCode(), "值条件宿主应为检验结果类");
        assertEquals("result_value", lab.getValueColumn(), "值条件应挂 result_value 列");
        assertTrue(lab.getInstanceCodes().isEmpty(), "类级度量宿主不应生成实例谓词");
        assertTrue(run.buildResult.getSql().contains("t1.result_value > 7"),
                "SQL 应含 result_value > 7 谓词:\n" + run.buildResult.getSql());
        assertFalse(run.buildResult.getSql().contains("dosage"), "值条件不得误挂用药剂量列");
        assertFalse(hasWarningLike(run.plan, "闭包内无实例"), "度量宿主类不应再报闭包为空警告");
    }

    @Test
    void instanceSpecializingBeatsClassMentionAsMeasureHost() {
        // 准备：HbA1c 实例宿主即检验结果类，应胜过类级提及保住 test_code 过滤
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult run = OntologyFixtures.runToPlan(model, "HbA1c检验结果值大于7的患者");
        // 断言
        assertEquals(1, run.plan.getConstraints().size(), "实例与值条件应合并为单一检验约束");
        QueryPlan.Constraint lab = run.plan.getConstraints().get(0);
        assertEquals("CLS_LAB_RESULT", lab.getHostClassCode(), "宿主应为检验结果类");
        assertTrue(lab.getInstanceCodes().contains("LAB_HBA1C"), "实例过滤应保留 LAB_HBA1C");
        assertEquals("result_value", lab.getValueColumn(), "值条件应挂 result_value 列");
    }

    @Test
    void listIntentResolvesDrugClassAsProjectionTarget() {
        // 准备：药品全量闭包覆盖宿主全部实例，应转为清单目标而非过滤约束
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult run = OntologyFixtures.runToPlan(model,
                "被诊断为2型糖尿病的患者使用了哪些药品");
        // 断言
        assertEquals(Mention.INTENT_LIST, run.plan.getIntent(), "哪些应识别为清单意图");
        assertEquals("CLS_MEDICATION", run.plan.getListHostClassCode(), "清单目标应为用药事件类");
        assertEquals(List.of("drug_code", "drug_name"), run.plan.getListColumns(), "展示列应为编码加名称");
        assertEquals(1, run.plan.getConstraints().size(), "药品全量闭包应退出过滤约束");
        assertEquals("CLS_DIAGNOSIS", run.plan.getConstraints().get(0).getHostClassCode(), "剩余约束应为诊断");
        assertTrue(run.buildResult.isOk(), "清单 SQL 应生成成功，错误: " + run.buildResult.getError());
        assertTrue(run.buildResult.getSql().startsWith("SELECT DISTINCT t.drug_code, t.drug_name"),
                "清单应 DISTINCT 展示列:\n" + run.buildResult.getSql());
    }

    @Test
    void listIntentSelectiveClosureBecomesRowFilter() {
        // 准备：降糖药闭包仅覆盖部分药品实例，应转为清单表行过滤
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult run = OntologyFixtures.runToPlan(model,
                "被诊断为2型糖尿病的患者使用了哪些降糖药");
        // 断言
        assertTrue(run.buildResult.isOk(), "选择性闭包清单应生成成功，错误: " + run.buildResult.getError());
        assertTrue(run.buildResult.getSql().contains("t.drug_code = 'D_GLIMEPIRIDE'"),
                "闭包实例应作清单行过滤:\n" + run.buildResult.getSql());
        assertFalse(run.buildResult.getSql().contains("'D_METFORMIN'"), "闭包外实例不应出现在清单 SQL");
    }

    private boolean containsText(TraceContext.TraceStep step, String keyword) {
        for (TraceItem item : step.getItems()) {
            if (item.getText() != null && item.getText().contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsLevel(TraceContext.TraceStep step, String level) {
        for (TraceItem item : step.getItems()) {
            if (level.equals(item.getLevel())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasWarningLike(QueryPlan plan, String keyword) {
        for (String warning : plan.getWarnings()) {
            if (warning.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
