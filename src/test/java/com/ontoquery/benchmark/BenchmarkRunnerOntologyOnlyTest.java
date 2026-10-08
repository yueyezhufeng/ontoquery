package com.ontoquery.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontoquery.ontology.OntologyPipeline;
import com.ontoquery.sql.ResultTable;
import com.ontoquery.sql.SafeQueryExecutor;
import com.ontoquery.support.PipelineResult;
import com.ontoquery.tradnl.TraditionalPipeline;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class BenchmarkRunnerOntologyOnlyTest {

    @Test
    void 全对时满分且无失败题() {
        OntologyPipeline pipeline = mock(OntologyPipeline.class);
        when(pipeline.answer(anyString())).thenReturn(result("327"));
        SafeQueryExecutor executor = mock(SafeQueryExecutor.class);
        when(executor.scalar(anyString())).thenReturn("327");
        BenchmarkRunner runner = new BenchmarkRunner(pipeline, mock(TraditionalPipeline.class),
                executor, mock(JdbcTemplate.class), new ObjectMapper());

        BenchmarkRunner.OntologyCheck check = runner.runOntologyOnly();

        assertEquals(Integer.valueOf(100), check.accuracy(), "全对应满分");
        assertTrue(check.failedQuestionNos().isEmpty(), "失败题列表为空");
    }

    @Test
    void 单题失败进入失败列表() {
        OntologyPipeline pipeline = mock(OntologyPipeline.class);
        // 第 1 题结果为 null（失败），其余全部与 golden 一致
        when(pipeline.answer(anyString())).thenReturn(result(null), result("327"));
        SafeQueryExecutor executor = mock(SafeQueryExecutor.class);
        when(executor.scalar(anyString())).thenReturn("327");
        BenchmarkRunner runner = new BenchmarkRunner(pipeline, mock(TraditionalPipeline.class),
                executor, mock(JdbcTemplate.class), new ObjectMapper());

        BenchmarkRunner.OntologyCheck check = runner.runOntologyOnly();

        assertEquals(Integer.valueOf(97), check.accuracy(), "36 题错 1 题应 97 分");
        assertEquals(1, check.failedQuestionNos().size(), "失败题号数量");
        assertEquals(Integer.valueOf(1), check.failedQuestionNos().get(0), "失败题为第 1 题");
    }

    private PipelineResult result(String count) {
        ResultTable table = new ResultTable();
        table.setColumns(List.of("value"));
        table.setRows(List.of(Collections.singletonList(count)));
        table.setRowCount(Integer.valueOf(1));
        table.setTruncated(Boolean.FALSE);
        PipelineResult r = new PipelineResult();
        r.setResult(table);
        return r;
    }
}
