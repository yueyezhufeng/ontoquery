package com.ontoquery.ontology.ops;

import com.ontoquery.benchmark.BenchmarkRunner;
import com.ontoquery.ontology.OntologyPipeline;
import com.ontoquery.ontology.OntologyRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OntologyAdminServiceTest {

    private final OntologyWriteService writeService = mock(OntologyWriteService.class);
    private final OntologyRepository repository = mock(OntologyRepository.class);
    private final OntologyPipeline pipeline = mock(OntologyPipeline.class);
    private final BenchmarkRunner benchmarkRunner = mock(BenchmarkRunner.class);
    private final CoverageScanner coverageScanner = mock(CoverageScanner.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OntologyAdminService service = new OntologyAdminService(writeService, repository,
            pipeline, benchmarkRunner, coverageScanner, jdbc);

    @Test
    void 写前后验证编排顺序与报告结构() {
        when(benchmarkRunner.runOntologyOnly()).thenReturn(
                new BenchmarkRunner.OntologyCheck(Integer.valueOf(97), List.of(Integer.valueOf(3))),
                new BenchmarkRunner.OntologyCheck(Integer.valueOf(100), List.of()));
        when(writeService.addSynonym("新词", "E11")).thenReturn(7L);
        OpsRequests.SynonymBody body = new OpsRequests.SynonymBody();
        body.setTerm("新词");
        body.setTargetCode("E11");

        Map<String, Object> result = service.addSynonym(body);

        InOrder order = inOrder(benchmarkRunner, writeService, repository, pipeline);
        order.verify(benchmarkRunner).runOntologyOnly();
        order.verify(writeService).addSynonym("新词", "E11");
        order.verify(repository).reload();
        order.verify(pipeline).rebuildRuntime();
        order.verify(benchmarkRunner).runOntologyOnly();

        assertEquals(Long.valueOf(7L), result.get("changesetId"), "变更集编号");
        assertEquals(Boolean.TRUE, result.get("applied"), "已应用");
        Map<?, ?> validation = (Map<?, ?>) result.get("validation");
        assertEquals(Integer.valueOf(97), validation.get("beforeAccuracy"), "前置准确率");
        assertEquals(Integer.valueOf(100), validation.get("afterAccuracy"), "后置准确率");
        assertEquals(List.of(Integer.valueOf(3)), validation.get("beforeFailed"), "前置失败题");
        assertEquals(List.of(), validation.get("afterFailed"), "后置失败题");
        assertEquals(List.of(), validation.get("regressed"), "无回归");
        assertEquals(List.of(Integer.valueOf(3)), validation.get("improved"), "题 3 提升");
    }

    @Test
    void 热加载失败B0502且写已提交不回滚() {
        when(benchmarkRunner.runOntologyOnly()).thenReturn(
                new BenchmarkRunner.OntologyCheck(Integer.valueOf(100), List.of()));
        when(writeService.deleteInstance("D_NEW")).thenReturn(8L);
        doThrow(new IllegalStateException("db down")).when(repository).reload();

        OpsRejectException e = assertThrows(OpsRejectException.class, () -> service.deleteInstance("D_NEW"));

        assertEquals("B0502", e.getCode(), "错误码");
        verify(writeService).deleteInstance("D_NEW");
        assertTrue(e.getMessage().contains("8"), "提示包含变更集编号");
    }

    @Test
    void 实例列表带保护标记与同义词() {
        when(jdbc.queryForList(contains("FROM ont_instance"))).thenReturn(List.of(
                Map.of("id", 1L, "code", "E11", "name_cn", "2型糖尿病", "remark", "",
                        "class_code", "CLS_DISEASE", "class_name", "疾病"),
                Map.of("id", 9L, "code", "D_NEW", "name_cn", "新降糖药", "remark", "",
                        "class_code", "CLS_DRUG", "class_name", "药品")));
        when(jdbc.queryForList(contains("FROM ont_synonym WHERE instance_id"), eq(String.class), eq(1L)))
                .thenReturn(List.of("II型糖尿病"));
        when(jdbc.queryForList(contains("FROM ont_synonym WHERE instance_id"), eq(String.class), eq(9L)))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM ont_mapping WHERE kind"), eq("instance"), eq(1L)))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM ont_mapping WHERE kind"), eq("instance"), eq(9L)))
                .thenReturn(List.of());

        List<Map<String, Object>> list = service.instances(null);

        assertEquals(2, list.size(), "两个实例");
        assertEquals(Boolean.TRUE, list.get(0).get("protected"), "E11 应保护");
        assertEquals(Boolean.FALSE, list.get(1).get("protected"), "新实例不保护");
        assertEquals(List.of("II型糖尿病"), list.get(0).get("synonyms"), "同义词列表");
    }

    @Test
    void 手动reload不写审计只热加载() {
        Map<String, Object> result = service.manualReload();

        assertEquals(Boolean.TRUE, result.get("reloaded"), "返回标记");
        verify(repository).reload();
        verify(pipeline).rebuildRuntime();
        verifyNoInteractions(writeService);
    }

    @Test
    void 写后验证失败B0502且数据不回滚() {
        when(benchmarkRunner.runOntologyOnly())
                .thenReturn(new BenchmarkRunner.OntologyCheck(Integer.valueOf(100), List.of()))
                .thenThrow(new IllegalStateException("bench down"));
        when(writeService.deleteInstance("D_X")).thenReturn(9L);

        OpsRejectException e = assertThrows(OpsRejectException.class, () -> service.deleteInstance("D_X"));

        assertEquals("B0502", e.getCode(), "错误码");
        verify(writeService).deleteInstance("D_X");
        verify(repository).reload();
        assertTrue(e.getMessage().contains("9"), "提示包含变更集编号");
    }
}
