package com.ontoquery.ontology;

import com.ontoquery.ontology.trace.TraceContext;

import java.math.BigDecimal;

/**
 * 测试用本体 fixture：编码严格按 CONTRACT.md 第 8 节约定构造，不连数据库。
 * 同时提供 runToSql 工具：词典识别 -> 关系链接 -> 推理 -> 路径 -> SQL 全链（无 DB）。
 */
final class OntologyFixtures {

    private OntologyFixtures() {
    }

    /** 全量标准本体（类/实例/同义词/关系/属性/映射/规则） */
    static OntologyModel fullModel() {
        OntologyModel model = new OntologyModel();

        // 分组类
        model.addClass("CLS_PERSON_GROUP", "人员", null, "#22d3ee", 10, "人员分组");
        model.addClass("CLS_ENTITY_GROUP", "临床实体", null, "#06b6d4", 20, "临床实体分组");
        model.addClass("CLS_EVENT_GROUP", "临床事件", null, "#0ea5e9", 30, "临床事件分组");

        // 核心类与维度类
        model.addClass("CLS_PERSON", "患者", "CLS_PERSON_GROUP", "#8b5cf6", 10, "患者主索引");
        model.addClass("CLS_DISEASE", "疾病", "CLS_ENTITY_GROUP", "#f87171", 10, "ICD-10 疾病概念");
        model.addClass("CLS_LAB_TEST", "检验项目", "CLS_ENTITY_GROUP", "#38bdf8", 20, "检验项目概念");
        model.addClass("CLS_DRUG", "药品", "CLS_ENTITY_GROUP", "#34d399", 30, "药品概念");
        model.addClass("CLS_DEPARTMENT", "科室", "CLS_ENTITY_GROUP", "#fbbf24", 40, "科室概念");

        // 事件类
        model.addClass("CLS_VISIT", "就诊", "CLS_EVENT_GROUP", "#a78bfa", 10, "就诊事件");
        model.addClass("CLS_DIAGNOSIS", "诊断记录", "CLS_EVENT_GROUP", "#f472b6", 20, "诊断事件");
        model.addClass("CLS_LAB_RESULT", "检验结果", "CLS_EVENT_GROUP", "#60a5fa", 30, "检验事件");
        model.addClass("CLS_MEDICATION", "用药记录", "CLS_EVENT_GROUP", "#4ade80", 40, "用药事件");

        // 类目子类（子类闭包用）
        model.addClass("CLS_DIABETES", "糖尿病类", "CLS_DISEASE", "#ef4444", 2, "E10-E14 糖尿病类目");
        model.addClass("CLS_HYPO_DRUG", "降糖药", "CLS_DRUG", "#10b981", 4, "口服降糖药 + 胰岛素");
        model.addClass("CLS_ORAL_HYPO", "口服降糖药", "CLS_HYPO_DRUG", "#34d399", 4, "口服降糖药子类");
        model.addClass("CLS_INSULIN", "胰岛素", "CLS_HYPO_DRUG", "#059669", 5, "胰岛素子类");

        // 关键实例
        model.addInstance("E11", "CLS_DISEASE", "2型糖尿病", "ICD-10 E11");
        model.addInstance("E10", "CLS_DIABETES", "1型糖尿病", "ICD-10 E10");
        model.addInstance("D_METFORMIN", "CLS_DRUG", "二甲双胍", "双胍类口服降糖药");
        model.addInstance("D_GLIMEPIRIDE", "CLS_ORAL_HYPO", "格列美脲", "磺脲类口服降糖药");
        model.addInstance("D_INSULIN_GLARGINE", "CLS_INSULIN", "甘精胰岛素", "长效胰岛素类似物");
        model.addInstance("D_INSULIN_REGULAR", "CLS_INSULIN", "人胰岛素", "短效胰岛素");
        model.addInstance("LAB_HBA1C", "CLS_LAB_TEST", "糖化血红蛋白", "HbA1c 检验");

        // 同义词种子
        model.addSynonym("II型糖尿病", "E11", null);
        model.addSynonym("糖尿病II型", "E11", null);
        model.addSynonym("成人糖尿病", "E11", null);
        model.addSynonym("非胰岛素依赖型糖尿病", "E11", null);
        model.addSynonym("格华止", "D_METFORMIN", null);
        model.addSynonym("盐酸二甲双胍片", "D_METFORMIN", null);
        model.addSynonym("降糖片", "D_METFORMIN", null);
        model.addSynonym("HbA1c", "LAB_HBA1C", null);
        model.addSynonym("HbA1c%", "LAB_HBA1C", null);
        model.addSynonym("糖基化血红蛋白", "LAB_HBA1C", null);
        model.addSynonym("糖化血红蛋白A1c", "LAB_HBA1C", null);
        // 类目类级同义词（子类闭包入口）
        model.addSynonym("糖尿病", null, "CLS_DIABETES");

        // 数据关系（患者 1:N 事件）
        model.addRelation("REL_HAS_VISIT", "就诊于", "CLS_PERSON", "CLS_VISIT", "1:N", "患者到就诊");
        model.addRelation("REL_DIAGNOSED_WITH", "被诊断为", "CLS_PERSON", "CLS_DIAGNOSIS", "1:N", "患者到诊断");
        model.addRelation("REL_UNDERGOES_TEST", "有检验结果", "CLS_PERSON", "CLS_LAB_RESULT", "1:N", "患者到检验");
        model.addRelation("REL_TAKES_DRUG", "使用药物", "CLS_PERSON", "CLS_MEDICATION", "1:N", "患者到用药");

        // 语义关系（N:1，展示用）
        model.addRelation("REL_DIAGNOSIS_OF", "确诊为", "CLS_DIAGNOSIS", "CLS_DISEASE", "N:1", "诊断到疾病");
        model.addRelation("REL_TEST_OF", "检验项目为", "CLS_LAB_RESULT", "CLS_LAB_TEST", "N:1", "检验到项目");
        model.addRelation("REL_DRUG_OF", "使用药品为", "CLS_MEDICATION", "CLS_DRUG", "N:1", "用药到药品");
        model.addRelation("REL_VISIT_DEPT", "就诊科室", "CLS_VISIT", "CLS_DEPARTMENT", "N:1", "就诊到科室");

        // 数据属性
        model.addAttribute("CLS_LAB_RESULT", "ATTR_RESULT_VALUE", "结果值", "decimal", "%",
                new BigDecimal("4"), new BigDecimal("6"), "正常值 4-6");
        model.addAttribute("CLS_MEDICATION", "ATTR_DOSAGE", "剂量", "decimal", "mg",
                null, null, "单次剂量");
        model.addAttribute("CLS_PERSON", "ATTR_AGE", "年龄", "decimal", "岁",
                null, null, "由 birth_date 推导的展示属性");
        // 维度分组属性（分组/TopN/值过滤目标）
        model.addAttribute("CLS_PERSON", "ATTR_GENDER", "性别", "varchar", null, null, null, "M / F");
        model.addAttribute("CLS_PERSON", "ATTR_REGION", "常住地区", "varchar", null, null, null, "分组属性");
        model.addAttribute("CLS_PERSON", "ATTR_INSURANCE_TYPE", "医保类型", "varchar", null, null, null, "分组属性");
        model.addAttribute("CLS_VISIT", "ATTR_DEPT_CODE", "科室", "varchar", null, null, null, "科室编码");
        model.addAttribute("CLS_VISIT", "ATTR_VISIT_TYPE", "就诊类型", "varchar", null, null, null, "门诊/住院/急诊");
        model.addAttribute("CLS_MEDICATION", "ATTR_DRUG_CODE", "药品", "varchar", null, null, null, "药品编码");
        model.addAttribute("CLS_MEDICATION", "ATTR_FREQUENCY", "用药频次", "varchar", null, null, null, "qd/bid/tid");
        model.addAttribute("CLS_DIAGNOSIS", "ATTR_DISEASE_NAME", "名称写法", "varchar", null, null, null, "诊断名称原文写法");
        model.addAttribute("CLS_DIAGNOSIS", "ATTR_DISEASE_CODE", "诊断编码", "varchar", null, null, null, "ICD-10 编码");
        model.addAttribute("CLS_LAB_RESULT", "ATTR_IS_ABNORMAL", "是否异常", "varchar", null, null, null, "1 异常 / 0 正常");

        // 类映射
        model.addMapping(OntologyModel.MAPPING_KIND_CLASS, "CLS_PERSON", "dim_patient", null, null, null);
        model.addMapping(OntologyModel.MAPPING_KIND_CLASS, "CLS_VISIT", "fact_visit", null, null, null);
        model.addMapping(OntologyModel.MAPPING_KIND_CLASS, "CLS_DIAGNOSIS", "fact_diagnosis", null, null, null);
        model.addMapping(OntologyModel.MAPPING_KIND_CLASS, "CLS_LAB_RESULT", "fact_lab_result", null, null, null);
        model.addMapping(OntologyModel.MAPPING_KIND_CLASS, "CLS_MEDICATION", "fact_medication", null, null, null);
        model.addMapping(OntologyModel.MAPPING_KIND_CLASS, "CLS_DISEASE", "dim_disease", null, null, null);
        model.addMapping(OntologyModel.MAPPING_KIND_CLASS, "CLS_LAB_TEST", "dim_lab_test", null, null, null);
        model.addMapping(OntologyModel.MAPPING_KIND_CLASS, "CLS_DRUG", "dim_drug", null, null, null);
        model.addMapping(OntologyModel.MAPPING_KIND_CLASS, "CLS_DEPARTMENT", "dim_department", null, null, null);

        // 关系映射（事件表 join 条件）
        model.addMapping(OntologyModel.MAPPING_KIND_RELATION, "REL_HAS_VISIT", "fact_visit", null,
                "{t}.patient_id = {a}.patient_id", null);
        model.addMapping(OntologyModel.MAPPING_KIND_RELATION, "REL_DIAGNOSED_WITH", "fact_diagnosis", null,
                "{t}.patient_id = {a}.patient_id", null);
        model.addMapping(OntologyModel.MAPPING_KIND_RELATION, "REL_UNDERGOES_TEST", "fact_lab_result", null,
                "{t}.patient_id = {a}.patient_id", null);
        model.addMapping(OntologyModel.MAPPING_KIND_RELATION, "REL_TAKES_DRUG", "fact_medication", null,
                "{t}.patient_id = {a}.patient_id", null);

        // 属性映射
        model.addMapping(OntologyModel.MAPPING_KIND_ATTRIBUTE, "ATTR_RESULT_VALUE", "fact_lab_result",
                "result_value", null, "{t}.result_value");
        model.addMapping(OntologyModel.MAPPING_KIND_ATTRIBUTE, "ATTR_DOSAGE", "fact_medication",
                "dosage", null, "{t}.dosage");
        model.addMapping(OntologyModel.MAPPING_KIND_ATTRIBUTE, "ATTR_GENDER", "dim_patient",
                "gender", null, "{t}.gender");
        model.addMapping(OntologyModel.MAPPING_KIND_ATTRIBUTE, "ATTR_REGION", "dim_patient",
                "region", null, "{t}.region");
        model.addMapping(OntologyModel.MAPPING_KIND_ATTRIBUTE, "ATTR_INSURANCE_TYPE", "dim_patient",
                "insurance_type", null, "{t}.insurance_type");
        model.addMapping(OntologyModel.MAPPING_KIND_ATTRIBUTE, "ATTR_DEPT_CODE", "fact_visit",
                "dept_code", null, "{t}.dept_code");
        model.addMapping(OntologyModel.MAPPING_KIND_ATTRIBUTE, "ATTR_VISIT_TYPE", "fact_visit",
                "visit_type", null, "{t}.visit_type");
        model.addMapping(OntologyModel.MAPPING_KIND_ATTRIBUTE, "ATTR_DRUG_CODE", "fact_medication",
                "drug_code", null, "{t}.drug_code");
        model.addMapping(OntologyModel.MAPPING_KIND_ATTRIBUTE, "ATTR_FREQUENCY", "fact_medication",
                "frequency", null, "{t}.frequency");
        model.addMapping(OntologyModel.MAPPING_KIND_ATTRIBUTE, "ATTR_DISEASE_NAME", "fact_diagnosis",
                "disease_name", null, "{t}.disease_name");
        model.addMapping(OntologyModel.MAPPING_KIND_ATTRIBUTE, "ATTR_DISEASE_CODE", "fact_diagnosis",
                "disease_code", null, "{t}.disease_code");
        model.addMapping(OntologyModel.MAPPING_KIND_ATTRIBUTE, "ATTR_IS_ABNORMAL", "fact_lab_result",
                "is_abnormal", null, "{t}.is_abnormal");

        // 实例映射
        model.addMapping(OntologyModel.MAPPING_KIND_INSTANCE, "E11", "fact_diagnosis", "disease_code", null,
                "{t}.disease_code = 'E11'");
        model.addMapping(OntologyModel.MAPPING_KIND_INSTANCE, "E10", "fact_diagnosis", "disease_code", null,
                "{t}.disease_code = 'E10'");
        model.addMapping(OntologyModel.MAPPING_KIND_INSTANCE, "D_METFORMIN", "fact_medication", "drug_code", null,
                "{t}.drug_code = 'D_METFORMIN'");
        model.addMapping(OntologyModel.MAPPING_KIND_INSTANCE, "D_GLIMEPIRIDE", "fact_medication", "drug_code", null,
                "{t}.drug_code = 'D_GLIMEPIRIDE'");
        model.addMapping(OntologyModel.MAPPING_KIND_INSTANCE, "D_INSULIN_GLARGINE", "fact_medication", "drug_code",
                null, "{t}.drug_code = 'D_INSULIN_GLARGINE'");
        model.addMapping(OntologyModel.MAPPING_KIND_INSTANCE, "D_INSULIN_REGULAR", "fact_medication", "drug_code",
                null, "{t}.drug_code = 'D_INSULIN_REGULAR'");
        model.addMapping(OntologyModel.MAPPING_KIND_INSTANCE, "LAB_HBA1C", "fact_lab_result", "test_code", null,
                "{t}.test_code = 'LAB_HBA1C'");

        // 推理规则
        model.addRule("TIME_ATTACH_PROXIMITY", "time_attach", "时间就近挂靠", "{}");
        model.addRule("VALUE_RANGE", "value_range", "值域校验", "{}");
        model.addRule("SUBCLASS_CLOSURE", "subclass_closure", "子类闭包", "{}");
        return model;
    }

    /** 全链执行到 SQL 生成（不执行 SQL） */
    static PathToSqlBuilder.BuildResult runToSql(OntologyModel model, String question) {
        return runToPlan(model, question).buildResult;
    }

    /** 全链执行并携带计划（推理断言用） */
    static PlanAndResult runToPlan(OntologyModel model, String question) {
        // 准备：装配五段中的前四段
        TraceContext trace = new TraceContext();
        QueryPlan plan = new QueryPlan();
        plan.setQuestion(question);
        plan.getMentions().addAll(new DictionaryNer(model).recognize(question));
        new RelationLinker(model).link(plan);
        TraceContext.TraceStep step = trace.begin("reasoning", "本体推理");
        new ReasoningService(model).apply(plan, step);
        PathPlanner.PathPlan paths = new PathPlanner(model).plan(plan);
        PathToSqlBuilder.BuildResult build = new PathToSqlBuilder(model).build(plan, paths);
        return new PlanAndResult(plan, paths, step, build);
    }

    /** 中间产物打包 */
    static final class PlanAndResult {
        final QueryPlan plan;
        final PathPlanner.PathPlan paths;
        final TraceContext.TraceStep reasoningStep;
        final PathToSqlBuilder.BuildResult buildResult;

        PlanAndResult(QueryPlan plan, PathPlanner.PathPlan paths, TraceContext.TraceStep reasoningStep,
                PathToSqlBuilder.BuildResult buildResult) {
            this.plan = plan;
            this.paths = paths;
            this.reasoningStep = reasoningStep;
            this.buildResult = buildResult;
        }
    }
}
