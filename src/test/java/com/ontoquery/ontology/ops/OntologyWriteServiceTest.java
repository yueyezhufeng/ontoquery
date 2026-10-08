package com.ontoquery.ontology.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OntologyWriteServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OntologyWriteService service = new OntologyWriteService(jdbc, new ObjectMapper());

    @Test
    void 创建实例写入实例同义词映射与三行审计() {
        when(jdbc.queryForObject(contains("FROM ont_class WHERE code"), eq(Long.class), any()))
                .thenReturn(5L);
        when(jdbc.queryForObject(contains("COALESCE(MAX(changeset_id)"), eq(Long.class)))
                .thenReturn(7L);
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(null, 9L);
        when(jdbc.queryForObject(contains("FROM ont_synonym WHERE term"), eq(Long.class), any()))
                .thenReturn(0L);
        when(jdbc.queryForObject(contains("information_schema.columns"), eq(Long.class), any(), any()))
                .thenReturn(1L);

        OpsRequests.MappingBody mapping = new OpsRequests.MappingBody();
        mapping.setTable("fact_medication");
        mapping.setColumn("drug_code");
        mapping.setValueExpr("{t}.drug_code = 'D_NEW'");
        Long changeset = service.createInstance("CLS_DRUG", "D_NEW", "新降糖药", "备注",
                List.of(" 新格华止 "), mapping);

        assertEquals(Long.valueOf(7L), changeset, "返回变更集编号");
        verify(jdbc).update(contains("INSERT INTO ont_instance"), eq(5L), eq("D_NEW"), eq("新降糖药"), eq("备注"));
        verify(jdbc).update(contains("INSERT INTO ont_synonym"), eq("新格华止"), eq(9L));
        verify(jdbc).update(contains("INSERT INTO ont_mapping"), eq("instance"), eq(9L), eq("fact_medication"),
                eq("drug_code"), eq("{t}.drug_code = 'D_NEW'"));
        verify(jdbc, times(3)).update(contains("INSERT INTO ont_change_log"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 空白字段被拒绝A0401() {
        OpsRejectException e = assertThrows(OpsRejectException.class,
                () -> service.createInstance("CLS_DRUG", "  ", "名称", null, null, null),
                "空白 code 应拒绝");
        assertEquals("A0401", e.getCode(), "错误码");
        OpsRejectException e2 = assertThrows(OpsRejectException.class,
                () -> service.createInstance("CLS_DRUG", "D_X", " ", null, null, null),
                "空白名称应拒绝");
        assertEquals("A0401", e2.getCode(), "错误码");
    }

    @Test
    void 重复编码被拒绝A0409() {
        when(jdbc.queryForObject(contains("FROM ont_class WHERE code"), eq(Long.class), any()))
                .thenReturn(5L);
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(9L);
        OpsRejectException e = assertThrows(OpsRejectException.class,
                () -> service.createInstance("CLS_DRUG", "D_OLD", "名称", null, null, null),
                "重复 code 应拒绝");
        assertEquals("A0409", e.getCode(), "错误码");
    }

    @Test
    void 保护实例更新与删除被拒绝A0403() {
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(1L);
        assertEquals("A0403", assertThrows(OpsRejectException.class,
                () -> service.updateInstance("E11", "新名", null)).getCode(), "E11 更新应拒绝");
        assertEquals("A0403", assertThrows(OpsRejectException.class,
                () -> service.deleteInstance("D_METFORMIN")).getCode(), "二甲双胍删除应拒绝");
        verify(jdbc, never()).update(contains("UPDATE ont_instance"), any(), any(), any());
        verify(jdbc, never()).update(contains("DELETE FROM ont_instance"), eq(1L));
    }

    @Test
    void 删除实例级联删同义词与映射并留三行审计() {
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(9L);
        when(jdbc.queryForList(contains("FROM ont_synonym WHERE instance_id"), eq(9L)))
                .thenReturn(List.of(Map.of("term", "新格华止")));
        when(jdbc.queryForList(contains("FROM ont_mapping WHERE kind"), eq("instance"), eq(9L)))
                .thenReturn(List.of(Map.of("table_name", "fact_medication",
                        "column_name", "drug_code", "value_expr", "{t}.drug_code = 'D_NEW'")));
        when(jdbc.queryForList(contains("WHERE i.id = ?"), eq(9L)))
                .thenReturn(List.of(Map.of("code", "D_NEW", "name_cn", "新降糖药", "remark", "",
                        "class_code", "CLS_DRUG")));
        when(jdbc.queryForObject(contains("COALESCE(MAX(changeset_id)"), eq(Long.class))).thenReturn(8L);

        Long changeset = service.deleteInstance("D_NEW");

        assertEquals(Long.valueOf(8L), changeset, "变更集编号");
        verify(jdbc).update(contains("DELETE FROM ont_mapping"), eq("instance"), eq(9L));
        verify(jdbc).update(contains("DELETE FROM ont_synonym"), eq(9L));
        verify(jdbc).update(contains("DELETE FROM ont_instance"), eq(9L));
        verify(jdbc, times(3)).update(contains("INSERT INTO ont_change_log"),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void 更新不存在的实例A0404() {
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenThrow(new EmptyResultDataAccessException(1));
        assertEquals("A0404", assertThrows(OpsRejectException.class,
                () -> service.updateInstance("D_NONE", "名", null)).getCode(), "不存在的实例应 404");
    }

    @Test
    void 更新保留NULL备注不写null字面量() {
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(9L);
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("code", "D_NEW");
        row.put("name_cn", "旧名");
        row.put("remark", null);
        row.put("class_code", "CLS_DRUG");
        when(jdbc.queryForList(contains("WHERE i.id = ?"), eq(9L))).thenReturn(List.of(row));
        when(jdbc.queryForObject(contains("COALESCE(MAX(changeset_id)"), eq(Long.class))).thenReturn(8L);

        service.updateInstance("D_NEW", "新名", null);

        verify(jdbc).update(contains("UPDATE ont_instance"), eq("新名"), isNull(), eq(9L));
    }

    @Test
    void 更新空白名称被拒绝A0401() {
        when(jdbc.queryForObject(contains("FROM ont_instance WHERE code"), eq(Long.class), any()))
                .thenReturn(9L);
        OpsRejectException e = assertThrows(OpsRejectException.class,
                () -> service.updateInstance("D_NEW", "   ", null), "空白名称应拒绝");
        assertEquals("A0401", e.getCode(), "错误码");
        verify(jdbc, never()).update(contains("UPDATE ont_instance"), any(), any(), any());
    }
}
