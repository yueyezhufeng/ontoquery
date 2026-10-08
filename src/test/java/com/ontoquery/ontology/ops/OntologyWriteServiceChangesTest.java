package com.ontoquery.ontology.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OntologyWriteServiceChangesTest {

    @Test
    void 按变更集分组且倒序() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OntologyWriteService service = new OntologyWriteService(jdbc, new ObjectMapper());
        when(jdbc.queryForList(contains("FROM ont_change_log"), eq(50))).thenReturn(List.of(
                Map.of("changeset_id", 7L, "op_type", "create", "target_kind", "instance",
                        "target_code", "D_NEW", "is_revert", 0, "operator", "local", "create_time",
                        java.time.LocalDateTime.of(2026, 10, 8, 12, 0, 0)),
                Map.of("changeset_id", 7L, "op_type", "create", "target_kind", "synonym",
                        "target_code", "新格华止", "is_revert", 0, "operator", "local", "create_time",
                        java.time.LocalDateTime.of(2026, 10, 8, 12, 0, 0)),
                Map.of("changeset_id", 6L, "op_type", "delete", "target_kind", "synonym",
                        "target_code", "格华止", "is_revert", 1, "operator", "local", "create_time",
                        java.time.LocalDateTime.of(2026, 10, 8, 11, 0, 0))));

        List<Map<String, Object>> changes = service.recentChanges(50);

        assertEquals(2, changes.size(), "两个变更集");
        assertEquals(Long.valueOf(7L), changes.get(0).get("changesetId"), "最新集在前");
        assertEquals(Boolean.FALSE, changes.get(0).get("isRevert"), "普通集标记");
        assertEquals(2, ((List<?>) changes.get(0).get("ops")).size(), "集 7 有两行操作");
        assertEquals(Boolean.TRUE, changes.get(1).get("isRevert"), "回退集标记");
    }

    @Test
    void 最新可回退集判定与连续回退拦截() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        OntologyWriteService service = new OntologyWriteService(jdbc, new ObjectMapper());
        when(jdbc.queryForList(contains("ORDER BY id DESC"))).thenReturn(List.of(
                Map.of("changeset_id", 6L, "is_revert", 0)));

        assertEquals(Long.valueOf(6L), service.latestRevertableChangesetId(), "最新集非回退时应返回 6");
        when(jdbc.queryForList(contains("ORDER BY id DESC"))).thenReturn(List.of(
                Map.of("changeset_id", 7L, "is_revert", 1)));
        assertNull(service.latestRevertableChangesetId(), "最新集已是回退集应返回 null（连续回退拦截）");
        when(jdbc.queryForList(contains("ORDER BY id DESC"))).thenReturn(List.of());
        assertNull(service.latestRevertableChangesetId(), "无历史时返回 null");
    }
}
