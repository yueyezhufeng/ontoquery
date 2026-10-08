package com.ontoquery.ontology.ops;

import org.junit.jupiter.api.Test;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OntologyWriteServiceSynonymTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class, withSettings().strictness(Strictness.LENIENT));
    private final OntologyWriteService service =
            new OntologyWriteService(jdbc, new com.fasterxml.jackson.databind.ObjectMapper());

    /** Map.of 禁止 null 值，含 null 的同义词行用 HashMap 手工填充 */
    private Map<String, Object> synonymRow(Long id, String term, Long instanceId, Long classId) {
        Map<String, Object> row = new HashMap<>(8);
        row.put("id", id);
        row.put("term", term);
        row.put("instance_id", instanceId);
        row.put("class_id", classId);
        return row;
    }

    @Test
    void 保护实例允许加同义词() {
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(1L);
        when(jdbc.queryForObject(contains("FROM ont_synonym WHERE term"), eq(Long.class), any()))
                .thenReturn(0L);
        when(jdbc.queryForObject(contains("COALESCE(MAX(changeset_id)"), eq(Long.class))).thenReturn(3L);

        Long changeset = service.addSynonym(" 青霉素类过敏提示 ", "E11");

        assertEquals(Long.valueOf(3L), changeset, "变更集编号");
        verify(jdbc).update(contains("INSERT INTO ont_synonym"), eq("青霉素类过敏提示"), eq(1L));
    }

    @Test
    void 首尾空格词条按trim后查重A0409() {
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(2L);
        when(jdbc.queryForObject(contains("FROM ont_synonym WHERE term"), eq(Long.class), any()))
                .thenReturn(1L);
        OpsRejectException e = assertThrows(OpsRejectException.class,
                () -> service.addSynonym(" 格华止 ", "D_METFORMIN"), "trim 后与库内重复应拒绝");
        assertEquals("A0409", e.getCode(), "错误码");
        verify(jdbc, never()).update(contains("INSERT INTO ont_synonym"), any(), any());
    }

    @Test
    void 类级同义词删除被拒绝A0403() {
        when(jdbc.queryForList(contains("FROM ont_synonym WHERE term"), any(String.class)))
                .thenReturn(List.of(synonymRow(11L, "糖尿病", null, 6L)));
        OpsRejectException e = assertThrows(OpsRejectException.class,
                () -> service.removeSynonym("糖尿病"), "类级同义词是种子保护");
        assertEquals("A0403", e.getCode(), "错误码");
    }

    @Test
    void 实例同义词正常删除() {
        when(jdbc.queryForList(contains("FROM ont_synonym WHERE term"), any(String.class)))
                .thenReturn(List.of(synonymRow(12L, "格华止", 9L, null)));
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE id"), eq(String.class), eq(9L)))
                .thenReturn("D_NEW");
        when(jdbc.queryForObject(contains("COALESCE(MAX(changeset_id)"), eq(Long.class))).thenReturn(4L);

        Long changeset = service.removeSynonym("格华止");

        assertEquals(Long.valueOf(4L), changeset, "变更集编号");
        verify(jdbc).update(contains("DELETE FROM ont_synonym"), eq(12L));
    }

    @Test
    void 改映射走valueExpr校验B0501() {
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(9L);
        OpsRequests.MappingBody bad = new OpsRequests.MappingBody();
        bad.setTable("fact_medication");
        bad.setColumn("drug_code");
        bad.setValueExpr("{t}.drug_code = (SELECT 1)");
        OpsRejectException e = assertThrows(OpsRejectException.class,
                () -> service.updateInstanceMapping("D_NEW", bad), "子查询必须拒绝");
        assertEquals("B0501", e.getCode(), "错误码");
    }

    @Test
    void 保护实例改映射被拒绝A0403() {
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(1L);
        OpsRequests.MappingBody body = new OpsRequests.MappingBody();
        body.setTable("fact_diagnosis");
        body.setColumn("disease_code");
        body.setValueExpr("{t}.disease_code = 'E11'");
        assertEquals("A0403", assertThrows(OpsRejectException.class,
                () -> service.updateInstanceMapping("E11", body)).getCode(), "E11 改映射应拒绝");
    }

    @Test
    void 非保护实例映射走更新() {
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(9L);
        when(jdbc.queryForObject(contains("information_schema.columns"), eq(Long.class), any(), any()))
                .thenReturn(1L);
        when(jdbc.queryForList(contains("FROM ont_mapping WHERE kind"), eq("instance"), eq(9L)))
                .thenReturn(List.of(Map.of("id", 30L, "table_name", "fact_medication",
                        "column_name", "drug_code", "value_expr", "{t}.drug_code = 'D_OLD'")));
        when(jdbc.queryForObject(contains("COALESCE(MAX(changeset_id)"), eq(Long.class))).thenReturn(5L);

        OpsRequests.MappingBody body = new OpsRequests.MappingBody();
        body.setTable("fact_medication");
        body.setColumn("drug_code");
        body.setValueExpr("{t}.drug_code = 'D_NEW'");
        Long changeset = service.updateInstanceMapping("D_NEW", body);

        assertEquals(Long.valueOf(5L), changeset, "变更集编号");
        verify(jdbc).update(contains("UPDATE ont_mapping"), eq("fact_medication"), eq("drug_code"),
                eq("{t}.drug_code = 'D_NEW'"), eq(30L));
    }
}
