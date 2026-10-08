package com.ontoquery.ontology;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PathToSqlBuilder 单测：CONTRACT.md 第 7 节黄金 SQL 逐字符断言 + 意图四类正反例。
 */
class PathToSqlBuilderTest {

    /** 契约第 7 节黄金 SQL（含缩进与换行，以契约代码块为准） */
    private static final String GOLDEN_COUNT_SQL = """
            SELECT COUNT(DISTINCT p.patient_id) AS patient_count
            FROM dim_patient p
            WHERE EXISTS (SELECT 1 FROM fact_diagnosis t0
                          WHERE t0.patient_id = p.patient_id
                            AND t0.disease_code = 'E11'
                            AND t0.diagnosis_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY))
              AND EXISTS (SELECT 1 FROM fact_lab_result t1
                          WHERE t1.patient_id = p.patient_id
                            AND t1.test_code = 'LAB_HBA1C'
                            AND t1.result_value > 7)
              AND EXISTS (SELECT 1 FROM fact_medication t2
                          WHERE t2.patient_id = p.patient_id
                            AND t2.drug_code = 'D_METFORMIN')""";

    private static final String GOLDEN_QUESTION =
            "近一个月诊断为2型糖尿病且HbA1c大于7的患者中，使用二甲双胍治疗的有多少人？";

    @Test
    void goldenQuestionGeneratesExactContractSql() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        PathToSqlBuilder.BuildResult result = OntologyFixtures.runToSql(model, GOLDEN_QUESTION);
        // 断言
        assertTrue(result.isOk(), "黄金问题必须成功生成 SQL，错误: " + result.getError());
        assertEquals(GOLDEN_COUNT_SQL, result.getSql(),
                "黄金 SQL 应与 CONTRACT.md 第 7 节逐字符一致（含缩进换行），实际:\n" + result.getSql());
    }

    @Test
    void synonymDrivenQuestionGeneratesIdenticalSql() {
        // 准备：格华止为 D_METFORMIN 同义词，SQL 必须与标准名完全一致
        OntologyModel model = OntologyFixtures.fullModel();
        String question = "近一个月诊断为2型糖尿病且HbA1c大于7的患者中，使用格华止治疗的有多少人？";
        // 执行
        PathToSqlBuilder.BuildResult result = OntologyFixtures.runToSql(model, question);
        // 断言
        assertTrue(result.isOk(), "同义词问题应成功生成 SQL");
        assertEquals(GOLDEN_COUNT_SQL, result.getSql(), "同义词（格华止）应归一到 D_METFORMIN，SQL 与黄金一致");
    }

    @Test
    void countIntentWithoutConstraintCountsAllPatients() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        PathToSqlBuilder.BuildResult result = OntologyFixtures.runToSql(model, "患者有多少人");
        // 断言
        assertTrue(result.isOk(), "无约束计数应生成全量计数 SQL");
        assertEquals("SELECT COUNT(DISTINCT p.patient_id) AS patient_count\nFROM dim_patient p",
                result.getSql(), "无约束时仅 SELECT/FROM，不输出 WHERE");
    }

    @Test
    void groupIntentProducesScalarGroupSql() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        PathToSqlBuilder.BuildResult result = OntologyFixtures.runToSql(model, "各区域有多少患者");
        // 断言
        assertTrue(result.isOk(), "分组意图应成功生成 SQL");
        assertEquals("""
                SELECT CONCAT('[', GROUP_CONCAT(CONCAT(region, ':', cnt) ORDER BY region SEPARATOR ', '), ']') AS group_result
                FROM (SELECT p.region AS region, COUNT(DISTINCT p.patient_id) AS cnt
                FROM dim_patient p
                GROUP BY p.region) sub""", result.getSql(), "分组计数应折叠为可读列表标量并按分组列排序");
    }

    @Test
    void groupByInsuranceTypeResolvesMappedColumn() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult pr = OntologyFixtures.runToPlan(model, "各医保类型的患者分别有多少人");
        // 断言
        assertTrue(pr.buildResult.isOk(), "医保类型分组应成功生成 SQL");
        assertEquals("insurance_type", pr.plan.getGroupColumn(), "医保类型应经属性映射解析到 insurance_type 列");
        assertTrue(pr.buildResult.getSql().contains("ORDER BY insurance_type"), "列表应按 insurance_type 排序");
    }

    @Test
    void recordCountIntentCountsEventTableRows() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult pr = OntologyFixtures.runToPlan(model, "检验结果一共有多少条记录");
        // 断言
        assertTrue(pr.buildResult.isOk(), "记录数问题应成功生成 SQL");
        assertEquals("SELECT COUNT(*) AS record_count\nFROM fact_lab_result", pr.buildResult.getSql(),
                "「多少条记录」应数事件表行数而非患者数");
        assertEquals("CLS_LAB_RESULT", pr.plan.getRecordCountClassCode(), "计数对象应为检验结果事件类");
    }

    @Test
    void listIntentGeneratesDistinctProjectionSql() {
        // 准备：药品类全量闭包转清单目标，剩余诊断约束经患者锚 EXISTS 关联
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        PathToSqlBuilder.BuildResult result = OntologyFixtures.runToSql(model,
                "被诊断为2型糖尿病的患者使用了哪些药品");
        // 断言
        assertTrue(result.isOk(), "清单意图应成功生成 SQL，错误: " + result.getError());
        assertEquals("""
                SELECT DISTINCT t.drug_code, t.drug_name
                FROM fact_medication t
                WHERE EXISTS (SELECT 1 FROM dim_patient p
                              WHERE p.patient_id = t.patient_id
                                AND EXISTS (SELECT 1 FROM fact_diagnosis t0
                                  WHERE t0.patient_id = p.patient_id
                                    AND t0.disease_code = 'E11'))
                ORDER BY t.drug_code""", result.getSql(), "清单 SQL 应为清单表 DISTINCT + 患者锚 EXISTS，实际:\n"
                + result.getSql());
    }

    @Test
    void topNByCountGroupsEventTableColumn() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult pr = OntologyFixtures.runToPlan(model, "就诊量最高的前五个科室及其就诊次数分别是多少");
        // 断言
        assertTrue(pr.buildResult.isOk(), "按计数 TopN 应成功生成 SQL，错误: " + pr.buildResult.getError());
        assertEquals("""
                SELECT CONCAT('[', GROUP_CONCAT(CONCAT(dept_code, ':', cnt) ORDER BY cnt DESC, dept_code SEPARATOR ', '), ']') AS top_result
                FROM (SELECT dept_code, COUNT(*) AS cnt
                FROM fact_visit
                GROUP BY dept_code
                ORDER BY cnt DESC, dept_code
                LIMIT 5) sub""", pr.buildResult.getSql(), "就诊量 TopN 应按 fact_visit.dept_code 分组计数取前五");
    }

    @Test
    void visitTypeWordBecomesPredicateAndGenderGroups() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult pr = OntologyFixtures.runToPlan(model, "住院就诊的患者中男性和女性各有多少人");
        // 断言
        assertTrue(pr.buildResult.isOk(), "住院性别分组应成功生成 SQL");
        assertEquals("gender", pr.plan.getGroupColumn(), "男性和女性应解析到 gender 分组列");
        assertTrue(pr.buildResult.getSql().contains("t0.visit_type = '住院'"), "住院应生成 visit_type 谓词");
        assertTrue(pr.buildResult.getSql().contains("ORDER BY gender"), "列表应按 gender 排序");
    }

    @Test
    void topNIntentProducesOrderByLimitSql() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        PathToSqlBuilder.BuildResult result = OntologyFixtures.runToSql(model, "近三个月检验HbA1c排名前10的患者");
        // 断言
        assertTrue(result.isOk(), "TopN 意图应成功生成 SQL，错误: " + result.getError());
        assertEquals("""
                SELECT p.patient_id AS patient_id, t0.result_value AS result_value
                FROM dim_patient p
                JOIN fact_lab_result t0 ON t0.patient_id = p.patient_id
                WHERE t0.test_code = 'LAB_HBA1C'
                  AND t0.test_date >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)
                ORDER BY t0.result_value DESC
                LIMIT 10""", result.getSql(), "TopN 应 JOIN 度量表并 ORDER BY 度量列 DESC LIMIT N");
    }

    @Test
    void avgIntentProducesJoinAvgSql() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        PathToSqlBuilder.BuildResult result = OntologyFixtures.runToSql(model, "使用二甲双胍的患者平均HbA1c");
        // 断言
        assertTrue(result.isOk(), "均值意图应成功生成 SQL，错误: " + result.getError());
        assertEquals("""
                SELECT ROUND(AVG(t1.result_value), 2) AS avg_result_value
                FROM dim_patient p
                JOIN fact_lab_result t1 ON t1.patient_id = p.patient_id
                WHERE EXISTS (SELECT 1 FROM fact_medication t0
                              WHERE t0.patient_id = p.patient_id
                                AND t0.drug_code = 'D_METFORMIN')
                  AND t1.test_code = 'LAB_HBA1C'""",
                result.getSql(), "均值应 ROUND(AVG 度量列, 2)，其余约束保持 EXISTS");
    }

    @Test
    void twoInstanceMentionsWithoutOrProduceSeparateExists() {
        // 准备：「同时诊断为 A 和 B」无「或」连接，应为 AND 语义（两个独立 EXISTS）
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        PathToSqlBuilder.BuildResult result = OntologyFixtures.runToSql(model,
                "同时诊断为2型糖尿病和1型糖尿病的患者有多少人");
        // 断言
        assertTrue(result.isOk(), "双疾病 AND 问题应成功生成 SQL，错误: " + result.getError());
        assertEquals("""
                SELECT COUNT(DISTINCT p.patient_id) AS patient_count
                FROM dim_patient p
                WHERE EXISTS (SELECT 1 FROM fact_diagnosis t0
                              WHERE t0.patient_id = p.patient_id
                                AND t0.disease_code = 'E11')
                  AND EXISTS (SELECT 1 FROM fact_diagnosis t1
                              WHERE t1.patient_id = p.patient_id
                                AND t1.disease_code = 'E10')""",
                result.getSql(), "无「或」连接的同宿主双提及应生成两个独立 EXISTS（AND 语义）");
    }

    @Test
    void orConnectedInstancesMergeIntoSingleOrPredicate() {
        // 准备：「A 或 B」连接的同宿主提及应合并为单约束 OR 谓词
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        PathToSqlBuilder.BuildResult result = OntologyFixtures.runToSql(model,
                "使用二甲双胍或格列美脲治疗的患者有多少人");
        // 断言
        assertTrue(result.isOk(), "「或」连接问题应成功生成 SQL，错误: " + result.getError());
        assertEquals("""
                SELECT COUNT(DISTINCT p.patient_id) AS patient_count
                FROM dim_patient p
                WHERE EXISTS (SELECT 1 FROM fact_medication t0
                              WHERE t0.patient_id = p.patient_id
                                AND (t0.drug_code = 'D_METFORMIN' OR t0.drug_code = 'D_GLIMEPIRIDE'))""",
                result.getSql(), "「或」连接的同宿主双提及应合并为单 EXISTS 内 OR 谓词");
    }

    @Test
    void classClosureSubsumesInstanceMention() {
        // 准备：「胰岛素」类闭包已含「人胰岛素」实例，实例提及应被涵盖不再另建约束
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        PathToSqlBuilder.BuildResult result = OntologyFixtures.runToSql(model,
                "使用胰岛素（人胰岛素）治疗的患者有多少人");
        // 断言
        assertTrue(result.isOk(), "类闭包涵盖问题应成功生成 SQL，错误: " + result.getError());
        assertEquals("""
                SELECT COUNT(DISTINCT p.patient_id) AS patient_count
                FROM dim_patient p
                WHERE EXISTS (SELECT 1 FROM fact_medication t0
                              WHERE t0.patient_id = p.patient_id
                                AND (t0.drug_code = 'D_INSULIN_GLARGINE' OR t0.drug_code = 'D_INSULIN_REGULAR'))""",
                result.getSql(), "被类闭包涵盖的实例提及不应新增约束或谓词");
    }

    @Test
    void groupByEventTableNameVariantsCountsRows() {
        // 准备：分组列在事件表（诊断名称写法），按记录数计数
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult pr = OntologyFixtures.runToPlan(model,
                "2型糖尿病诊断的各名称写法分别有多少条");
        // 断言
        assertTrue(pr.buildResult.isOk(), "事件表分组应成功生成 SQL，错误: " + pr.buildResult.getError());
        assertEquals("""
                SELECT CONCAT('[', GROUP_CONCAT(CONCAT(disease_name, ':', cnt) ORDER BY cnt DESC, disease_name SEPARATOR ', '), ']') AS group_result
                FROM (SELECT disease_name, COUNT(*) AS cnt
                FROM fact_diagnosis
                WHERE disease_code = 'E11'
                GROUP BY disease_name) sub""", pr.buildResult.getSql(),
                "事件表分组应按记录数 COUNT(*) 并按计数降序折叠为标量");
        assertEquals("CLS_DIAGNOSIS", pr.plan.getGroupHostClassCode(), "分组宿主应为诊断事件类");
    }

    @Test
    void groupByAbnormalFlagCountsLabRows() {
        // 准备：按是否异常分组检验记录
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult pr = OntologyFixtures.runToPlan(model,
                "糖化血红蛋白检验按是否异常分组各有多少条");
        // 断言
        assertTrue(pr.buildResult.isOk(), "是否异常分组应成功生成 SQL，错误: " + pr.buildResult.getError());
        assertEquals("""
                SELECT CONCAT('[', GROUP_CONCAT(CONCAT(is_abnormal, ':', cnt) ORDER BY cnt DESC, is_abnormal SEPARATOR ', '), ']') AS group_result
                FROM (SELECT is_abnormal, COUNT(*) AS cnt
                FROM fact_lab_result
                WHERE test_code = 'LAB_HBA1C'
                GROUP BY is_abnormal) sub""", pr.buildResult.getSql(),
                "是否异常应解析到 fact_lab_result.is_abnormal 分组列");
    }

    @Test
    void ageBucketGroupGeneratesCaseExpression() {
        // 准备：年龄分段（值域分段知识）应生成 CASE 桶表达式分组
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        OntologyFixtures.PlanAndResult pr = OntologyFixtures.runToPlan(model,
                "2型糖尿病患者按年龄分为青年、中年、老年各有多少人");
        // 断言
        assertTrue(pr.buildResult.isOk(), "年龄分段问题应成功生成 SQL，错误: " + pr.buildResult.getError());
        assertTrue(pr.buildResult.getSql().contains(
                "CASE WHEN TIMESTAMPDIFF(YEAR, p.birth_date, CURDATE()) < 45 THEN '青年(<45)'"),
                "应生成年龄分段 CASE 表达式: " + pr.buildResult.getSql());
        assertTrue(pr.buildResult.getSql().contains("GROUP BY bucket) sub"),
                "应按桶别名分组: " + pr.buildResult.getSql());
    }

    @Test
    void avgIntentWithoutMeasureFailsExplainably() {
        // 准备：年龄属性无映射，不可度量
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        PathToSqlBuilder.BuildResult result = OntologyFixtures.runToSql(model, "患者的平均年龄是多少");
        // 断言
        assertFalse(result.isOk(), "无可度量列的均值问题应失败");
        assertNotNull(result.getError(), "失败必须给出可解释错误");
        assertTrue(result.getError().contains("均值"), "错误应说明均值前提缺失: " + result.getError());
        assertNull(result.getSql(), "失败时不产出 SQL");
    }

    @Test
    void topNIntentWithoutMeasureFailsExplainably() {
        // 准备
        OntologyModel model = OntologyFixtures.fullModel();
        // 执行
        PathToSqlBuilder.BuildResult result = OntologyFixtures.runToSql(model, "前10位患者");
        // 断言
        assertFalse(result.isOk(), "无可排序列的 TopN 问题应失败");
        assertTrue(result.getError().contains("TopN"), "错误应说明 TopN 前提缺失: " + result.getError());
    }

    @Test
    void unreachableConstraintClassProducesExplainableError() {
        // 准备：追加与患者无任何关系的孤立项
        OntologyModel model = OntologyFixtures.fullModel();
        model.addClass("CLS_RESEARCH", "科研项目", "CLS_EVENT_GROUP", "#94a3b8", 50, "孤子事件");
        model.addInstance("R01", "CLS_RESEARCH", "真实世界研究", null);
        model.addMapping(OntologyModel.MAPPING_KIND_CLASS, "CLS_RESEARCH", "fact_research", null, null, null);
        model.addMapping(OntologyModel.MAPPING_KIND_INSTANCE, "R01", "fact_research", "project_code", null,
                "{t}.project_code = 'R01'");
        // 执行
        PathToSqlBuilder.BuildResult result = OntologyFixtures.runToSql(model, "参与R01研究的患者有多少人");
        // 断言
        assertFalse(result.isOk(), "锚点不可达的约束应整体失败（超纲）");
        assertTrue(result.getError().contains("无法从锚类规划到"), "错误应说明不可达原因: " + result.getError());
        assertNull(result.getSql(), "超纲时不产出 SQL");
    }
}
