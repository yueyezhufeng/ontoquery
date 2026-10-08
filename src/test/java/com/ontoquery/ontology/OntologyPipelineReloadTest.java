package com.ontoquery.ontology;

import com.ontoquery.sql.ResultTable;
import com.ontoquery.sql.SafeQueryExecutor;
import com.ontoquery.support.PipelineResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OntologyPipelineReloadTest {

    @Test
    void rebuildRuntime后新本体立即生效() {
        OntologyModel old = OntologyFixtures.fullModel();
        OntologyModel fresh = OntologyFixtures.fullModel();
        fresh.addInstance("D_NEW_DRUG", "CLS_DRUG", "新降糖药", "运维新增");
        fresh.addSynonym("库拉索兰", "D_NEW_DRUG", null);
        fresh.addMapping(OntologyModel.MAPPING_KIND_INSTANCE, "D_NEW_DRUG", "fact_medication", "drug_code",
                null, "{t}.drug_code = 'D_NEW_DRUG'");

        OntologyRepository repository = mock(OntologyRepository.class);
        when(repository.getModel()).thenReturn(old, fresh);
        SafeQueryExecutor executor = mock(SafeQueryExecutor.class);
        when(executor.execute(anyString())).thenReturn(simpleTable());

        OntologyPipeline pipeline = new OntologyPipeline(repository, executor);
        String question = "使用库拉索兰治疗的患者有多少";
        PipelineResult before = pipeline.answer(question);
        assertEquals("ok", before.getSqlStatus(), "旧本体对该计数问题宽松放行");
        assertNotNull(before.getSql(), "旧本体应产出全量计数 SQL");
        assertFalse(before.getSql().contains("D_NEW_DRUG"), "旧本体 SQL 不含新实例码");
        assertFalse(before.getSql().contains("fact_medication"), "旧本体无从生成用药路径");

        pipeline.rebuildRuntime();
        PipelineResult after = pipeline.answer(question);
        assertEquals("ok", after.getSqlStatus(), "热加载后应能生成 SQL");
        assertNotNull(after.getSql(), "应产出 SQL");
        assertTrue(after.getSql().contains("D_NEW_DRUG"), "SQL 应包含新实例码");
    }

    private ResultTable simpleTable() {
        ResultTable table = new ResultTable();
        table.setColumns(new ArrayList<>(List.of("patient_count")));
        table.setRows(new ArrayList<>(List.of(new ArrayList<>(List.of("1")))));
        table.setRowCount(Integer.valueOf(1));
        table.setTruncated(Boolean.FALSE);
        return table;
    }
}
