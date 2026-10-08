package com.ontoquery.ontology.ops;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoverageScannerTest {

    @Test
    void 未命中词条按大小写不敏感比对() {
        List<Map<String, Object>> rows = List.of(
                Map.of("term", "格华止", "cnt", 10L),
                Map.of("term", "diabetes mellitus type 2", "cnt", 5L),
                Map.of("term", " HBA1C ", "cnt", 3L));
        Set<String> known = Set.of("格华止", "hba1c");

        List<Map<String, Object>> unmapped = CoverageScanner.filterUnmapped(rows, known);

        assertEquals(1, unmapped.size(), "仅英文写法未收录");
        assertEquals("diabetes mellitus type 2", unmapped.get(0).get("term"), "未收录词条");
        assertEquals(5L, ((Number) unmapped.get(0).get("rowCount")).longValue(), "行数");
    }

    @Test
    void 扫描按类集合联合比对并返回结构() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(contains("fact_diagnosis"), eq(100))).thenReturn(List.of(
                Map.of("term", "未特指的糖尿病", "cnt", 734L),
                Map.of("term", "diabetes mellitus type 2", "cnt", 500L)));
        when(jdbc.queryForList(contains("fact_medication"), eq(100))).thenReturn(List.of());
        when(jdbc.queryForList(contains("fact_lab_result"), eq(100))).thenReturn(List.of());
        // 类集合 IN 查询（种子把糖尿病实例放在 CLS_DIABETES，药品横跨三个类）
        when(jdbc.queryForList(contains("FROM ont_instance i JOIN"), eq(String.class),
                eq("CLS_DISEASE"), eq("CLS_DIABETES"))).thenReturn(List.of("未特指的糖尿病"));
        when(jdbc.queryForList(contains("FROM ont_synonym s JOIN"), eq(String.class),
                eq("CLS_DISEASE"), eq("CLS_DIABETES"))).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM ont_instance i JOIN"), eq(String.class),
                eq("CLS_DRUG"), eq("CLS_INSULIN"), eq("CLS_ORAL_HYPO"))).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM ont_synonym s JOIN"), eq(String.class),
                eq("CLS_DRUG"), eq("CLS_INSULIN"), eq("CLS_ORAL_HYPO"))).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM ont_instance i JOIN"), eq(String.class),
                eq("CLS_LAB_TEST"))).thenReturn(List.of());
        when(jdbc.queryForList(contains("FROM ont_synonym s JOIN"), eq(String.class),
                eq("CLS_LAB_TEST"))).thenReturn(List.of());
        CoverageScanner scanner = new CoverageScanner(jdbc);

        Map<String, Object> result = scanner.scan();

        List<?> columns = (List<?>) result.get("columns");
        assertEquals(3, columns.size(), "三组固定目标");
        Map<?, ?> first = (Map<?, ?>) columns.get(0);
        assertEquals("fact_diagnosis", first.get("table"), "首组表名");
        assertEquals("CLS_DISEASE", first.get("classCode"), "主归属类");
        assertEquals(List.of("CLS_DISEASE", "CLS_DIABETES"), first.get("classCodes"), "类集合");
        List<?> unmapped = (List<?>) first.get("unmapped");
        assertEquals(1, unmapped.size(), "跨类实例名命中、英文写法未收录");
        assertEquals("diabetes mellitus type 2",
                ((Map<?, ?>) unmapped.get(0)).get("term"), "仅英文写法在缺口");
    }
}
