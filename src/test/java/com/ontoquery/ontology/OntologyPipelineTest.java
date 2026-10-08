package com.ontoquery.ontology;

import com.ontoquery.sql.ResultTable;
import com.ontoquery.sql.SafeQueryExecutor;
import com.ontoquery.support.PipelineResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OntologyPipeline 编排单测：mock 仓储与执行器，验证五步轨迹与 PipelineResult 形状。
 */
class OntologyPipelineTest {

    private static final String GOLDEN_QUESTION =
            "近一个月诊断为2型糖尿病且HbA1c大于7的患者中，使用二甲双胍治疗的有多少人？";

    @Test
    void goldenQuestionRunsFiveStepsAndReturnsCount() {
        // 准备：fixture 本体 + mock 执行器返回计数 327
        OntologyModel model = OntologyFixtures.fullModel();
        OntologyRepository repository = mock(OntologyRepository.class);
        when(repository.getModel()).thenReturn(model);
        SafeQueryExecutor executor = mock(SafeQueryExecutor.class);
        ResultTable table = new ResultTable();
        table.getColumns().add("patient_count");
        table.getRows().add(new ArrayList<>(List.of("327")));
        table.setRowCount(Integer.valueOf(1));
        table.setTruncated(Boolean.FALSE);
        when(executor.execute(org.mockito.ArgumentMatchers.anyString())).thenReturn(table);
        when(executor.explain(org.mockito.ArgumentMatchers.anyString())).thenReturn(
                List.of(List.of("id", "select_type", "table", "type"), List.of("1", "SIMPLE", "p", "index")));
        OntologyPipeline pipeline = new OntologyPipeline(repository, executor);

        // 执行
        PipelineResult result = pipeline.answer(GOLDEN_QUESTION);

        // 断言
        assertEquals("ontology", result.getEngine(), "engine 固定 ontology");
        assertEquals("deterministic", result.getMode(), "mode 固定 deterministic");
        assertEquals("确定性本体推理", result.getModeLabel(), "modeLabel 固定");
        assertNull(result.getFallbackReason(), "本体侧无降级");
        assertNull(result.getError(), "黄金问题不应出错");
        assertEquals(5, result.getSteps().size(), "必须固定五步轨迹");
        assertEquals("ner", result.getSteps().get(0).getKey(), "第一步 key=ner");
        assertEquals("relation", result.getSteps().get(1).getKey(), "第二步 key=relation");
        assertEquals("reasoning", result.getSteps().get(2).getKey(), "第三步 key=reasoning");
        assertEquals("path", result.getSteps().get(3).getKey(), "第四步 key=path");
        assertEquals("verify", result.getSteps().get(4).getKey(), "第五步 key=verify");
        assertEquals("ok", result.getSqlStatus(), "黄金问题 sqlStatus=ok");
        assertNotNull(result.getSql(), "应产出 SQL");
        assertTrue(result.getSql().startsWith("SELECT COUNT(DISTINCT p.patient_id)"), "计数 SQL 应统计患者数");
        assertEquals("327", result.getResult().getRows().get(0).get(0), "计数结果应为执行器返回值");
        assertNotNull(result.getConfidence(), "应产出置信度");
        assertTrue(result.getConfidence().getScore() >= 55 && result.getConfidence().getScore() <= 100,
                "置信度应在 [55,100] 区间");
        assertTrue(result.getTotalElapsedMs() >= 1L, "总耗时应大于 0");
    }

    @Test
    void outOfScopeQuestionFailsPathStepWithSqlStatusNone() {
        // 准备：无可度量列的均值问题在 path 步失败
        OntologyModel model = OntologyFixtures.fullModel();
        OntologyRepository repository = mock(OntologyRepository.class);
        when(repository.getModel()).thenReturn(model);
        SafeQueryExecutor executor = mock(SafeQueryExecutor.class);
        OntologyPipeline pipeline = new OntologyPipeline(repository, executor);

        // 执行
        PipelineResult result = pipeline.answer("患者的平均年龄是多少");

        // 断言
        assertEquals("none", result.getSqlStatus(), "超纲问题 sqlStatus=none");
        assertNull(result.getSql(), "超纲问题不产出 SQL");
        assertEquals("error", result.getSteps().get(3).getStatus(), "path 步应为 error");
        assertNotNull(result.getSteps().get(3).getSummary(), "path 步失败应带可解释摘要");
        assertNotNull(result.getResult(), "result 应为空表而非 null");
        assertEquals(Integer.valueOf(0), result.getResult().getRowCount(), "空表行数应为 0");
        assertNotNull(result.getConfidence(), "超纲问题仍应产出置信度");
    }

    @Test
    void blankQuestionIsRejected() {
        // 准备
        OntologyRepository repository = mock(OntologyRepository.class);
        when(repository.getModel()).thenReturn(OntologyFixtures.fullModel());
        OntologyPipeline pipeline = new OntologyPipeline(repository, mock(SafeQueryExecutor.class));
        // 执行 + 断言
        boolean rejected = false;
        try {
            pipeline.answer("  ");
        } catch (IllegalArgumentException e) {
            rejected = true;
        }
        assertTrue(rejected, "空白问题应抛出 IllegalArgumentException（由全局异常处理器转译）");
    }
}
