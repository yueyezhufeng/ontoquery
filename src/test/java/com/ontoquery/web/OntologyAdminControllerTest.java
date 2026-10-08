package com.ontoquery.web;

import com.ontoquery.ontology.ops.OntologyAdminService;
import com.ontoquery.ontology.ops.OpsRequests;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OntologyAdminControllerTest {

    private final OntologyAdminService adminService = mock(OntologyAdminService.class);
    private final OntologyAdminController controller = new OntologyAdminController(adminService);

    @Test
    void 端点委托门面并透传结果() {
        OpsRequests.CodeBody codeBody = new OpsRequests.CodeBody();
        codeBody.setCode("D_NEW");
        Map<String, Object> report = Map.of("changesetId", 7L, "applied", Boolean.TRUE);
        when(adminService.deleteInstance("D_NEW")).thenReturn(report);
        assertSame(report, controller.delete(codeBody), "删除端点透传门面报告");

        when(adminService.manualReload()).thenReturn(Map.of("reloaded", Boolean.TRUE));
        assertEquals(Boolean.TRUE, controller.reload().get("reloaded"), "reload 返回标记");

        when(adminService.instances("CLS_DRUG")).thenReturn(List.of(Map.of("code", "D_NEW")));
        assertEquals(1, controller.instances("CLS_DRUG").size(), "实例列表透传");

        when(adminService.changes()).thenReturn(List.of());
        assertTrue(controller.changes().isEmpty(), "变更历史透传");

        Map<String, Object> coverage = Map.of("columns", List.of());
        when(adminService.coverage()).thenReturn(coverage);
        assertSame(coverage, controller.coverage(), "覆盖扫描透传");
    }
}
