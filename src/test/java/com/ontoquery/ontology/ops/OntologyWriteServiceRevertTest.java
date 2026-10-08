package com.ontoquery.ontology.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OntologyWriteServiceRevertTest {

    private JdbcTemplate jdbc = mock(JdbcTemplate.class, withSettings().strictness(Strictness.LENIENT));
    private OntologyWriteService service = new OntologyWriteService(jdbc, new ObjectMapper());

    /** Map.of 禁止 null 值，审计行含 null 的 before_json/after_json 用 HashMap 手工填充 */
    private Map<String, Object> auditRow(Long id, String opType, String kind, String code,
            String beforeJson, String afterJson) {
        Map<String, Object> row = new HashMap<>(16);
        row.put("id", id);
        row.put("op_type", opType);
        row.put("target_kind", kind);
        row.put("target_code", code);
        row.put("before_json", beforeJson);
        row.put("after_json", afterJson);
        return row;
    }

    @Test
    void 无可回退变更集A0410() {
        when(jdbc.queryForList(contains("ORDER BY id DESC"))).thenReturn(List.of());
        OpsRejectException e = assertThrows(OpsRejectException.class, () -> service.revertLatest());
        assertEquals("A0410", e.getCode(), "错误码");
    }

    @Test
    void 回退create集按逆序撤销映射同义词实例() {
        when(jdbc.queryForList(contains("ORDER BY id DESC"))).thenReturn(List.of(
                Map.of("changeset_id", 7L, "is_revert", 0)));
        when(jdbc.queryForObject(contains("COALESCE(MAX(changeset_id)"), eq(Long.class))).thenReturn(8L);
        when(jdbc.queryForList(contains("WHERE changeset_id ="), eq(7L))).thenReturn(List.of(
                auditRow(71L, "create", "instance", "D_NEW", null,
                        "{\"classCode\":\"CLS_DRUG\",\"code\":\"D_NEW\",\"nameCn\":\"新降糖药\",\"remark\":null}"),
                auditRow(72L, "create", "synonym", "新格华止", null,
                        "{\"term\":\"新格华止\",\"instanceCode\":\"D_NEW\"}"),
                auditRow(73L, "create", "mapping", "D_NEW", null,
                        "{\"instanceCode\":\"D_NEW\",\"table\":\"fact_medication\",\"column\":\"drug_code\","
                                + "\"valueExpr\":\"{t}.drug_code = 'D_NEW'\"}")));
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(9L);
        when(jdbc.queryForObject(contains("FROM ont_class WHERE code"), eq(Long.class), any()))
                .thenReturn(5L);

        Long newSet = service.revertLatest();

        assertEquals(Long.valueOf(8L), newSet, "返回逆向变更集编号");
        InOrder order = inOrder(jdbc);
        order.verify(jdbc).update(contains("DELETE FROM ont_mapping"), eq("instance"), eq(9L));
        order.verify(jdbc).update(contains("DELETE FROM ont_synonym"), eq("新格华止"));
        order.verify(jdbc).update(contains("DELETE FROM ont_instance"), eq(9L));
        verify(jdbc, times(3)).update(contains("INSERT INTO ont_change_log"), eq(8L), eq("revert"),
                any(), any(), any(), any(), eq(1), eq("local"));
    }

    @Test
    void 回退delete集重建实例同义词映射() {
        when(jdbc.queryForList(contains("ORDER BY id DESC"))).thenReturn(List.of(
                Map.of("changeset_id", 9L, "is_revert", 0)));
        when(jdbc.queryForObject(contains("COALESCE(MAX(changeset_id)"), eq(Long.class))).thenReturn(10L);
        when(jdbc.queryForList(contains("WHERE changeset_id ="), eq(9L))).thenReturn(List.of(
                auditRow(91L, "delete", "mapping", "D_OLD",
                        "{\"instanceCode\":\"D_OLD\",\"table\":\"fact_medication\",\"column\":\"drug_code\","
                                + "\"valueExpr\":\"{t}.drug_code = 'D_OLD'\"}", null),
                auditRow(92L, "delete", "synonym", "老格华止",
                        "{\"term\":\"老格华止\",\"instanceCode\":\"D_OLD\"}", null),
                auditRow(93L, "delete", "instance", "D_OLD",
                        "{\"classCode\":\"CLS_DRUG\",\"code\":\"D_OLD\",\"nameCn\":\"老药\",\"remark\":\"r\"}", null)));
        when(jdbc.queryForObject(contains("FROM ont_class WHERE code"), eq(Long.class), any()))
                .thenReturn(5L);
        // 实例重建后按 code 重查即得新 id
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(21L);

        Long newSet = service.revertLatest();

        assertEquals(Long.valueOf(10L), newSet, "返回逆向变更集编号");
        InOrder order = inOrder(jdbc);
        order.verify(jdbc).update(contains("INSERT INTO ont_instance"), eq(5L), eq("D_OLD"), eq("老药"), eq("r"));
        order.verify(jdbc).update(contains("INSERT INTO ont_synonym"), eq("老格华止"), eq(21L));
        order.verify(jdbc).update(contains("INSERT INTO ont_mapping"), eq("instance"), eq(21L),
                eq("fact_medication"), eq("drug_code"), eq("{t}.drug_code = 'D_OLD'"));
    }

    @Test
    void 回退update集还原before值() {
        when(jdbc.queryForList(contains("ORDER BY id DESC"))).thenReturn(List.of(
                Map.of("changeset_id", 11L, "is_revert", 0)));
        when(jdbc.queryForObject(contains("COALESCE(MAX(changeset_id)"), eq(Long.class))).thenReturn(12L);
        when(jdbc.queryForList(contains("WHERE changeset_id ="), eq(11L))).thenReturn(List.of(
                auditRow(111L, "update", "instance", "D_OLD",
                        "{\"classCode\":\"CLS_DRUG\",\"code\":\"D_OLD\",\"nameCn\":\"旧名\",\"remark\":null}",
                        "{\"classCode\":\"CLS_DRUG\",\"code\":\"D_OLD\",\"nameCn\":\"新名\",\"remark\":null}")));
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(21L);

        service.revertLatest();

        verify(jdbc).update(contains("UPDATE ont_instance"), eq("旧名"), isNull(), eq(21L));
    }
}
