package com.ontoquery.web;

import com.ontoquery.ontology.ops.OntologyAdminService;
import com.ontoquery.ontology.ops.OpsRequests;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 本体运维端点（契约第 9 节）：实例/同义词/映射写操作、热加载、变更历史、回退、覆盖扫描。
 * 变更类端点保存后自动热加载并重跑本体侧校验，响应统一携带验证报告。
 *
 * @author 月夜烛峰
 */
@RestController
@RequestMapping("/api/ontology/admin")
public class OntologyAdminController {

    private final OntologyAdminService adminService;

    public OntologyAdminController(OntologyAdminService adminService) {
        this.adminService = adminService;
    }

    /** 实例列表（含同义词、映射、protected 标记） */
    @GetMapping("/instances")
    public List<Map<String, Object>> instances(@RequestParam(required = false) String classCode) {
        return adminService.instances(classCode);
    }

    /** 创建实例（可携带同义词与映射） */
    @PostMapping("/instance")
    public Map<String, Object> create(@RequestBody OpsRequests.CreateInstanceBody body) {
        return adminService.createInstance(body);
    }

    /** 改名/备注（code 不可改） */
    @PostMapping("/instance/update")
    public Map<String, Object> update(@RequestBody OpsRequests.UpdateInstanceBody body) {
        return adminService.updateInstance(body);
    }

    /** 级联删除实例（同义词与映射同删） */
    @PostMapping("/instance/delete")
    public Map<String, Object> delete(@RequestBody OpsRequests.CodeBody body) {
        return adminService.deleteInstance(body.getCode());
    }

    /** 加同义词（保护实例放行） */
    @PostMapping("/synonym/add")
    public Map<String, Object> addSynonym(@RequestBody OpsRequests.SynonymBody body) {
        return adminService.addSynonym(body);
    }

    /** 删同义词（类级同义词拦截） */
    @PostMapping("/synonym/delete")
    public Map<String, Object> deleteSynonym(@RequestBody OpsRequests.TermBody body) {
        return adminService.removeSynonym(body.getTerm());
    }

    /** 改实例映射（valueExpr 白名单校验） */
    @PostMapping("/mapping/update")
    public Map<String, Object> updateMapping(@RequestBody OpsRequests.MappingUpdateBody body) {
        return adminService.updateMapping(body);
    }

    /** 手动热加载 */
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        return adminService.manualReload();
    }

    /** 最近 50 个变更集 */
    @GetMapping("/changes")
    public List<Map<String, Object>> changes() {
        return adminService.changes();
    }

    /** 回退最近一次变更（最新集须非回退集，连续回退第二次 A0410） */
    @PostMapping("/revert")
    public Map<String, Object> revert() {
        return adminService.revert();
    }

    /** 覆盖缺口扫描 */
    @GetMapping("/coverage")
    public Map<String, Object> coverage() {
        return adminService.coverage();
    }
}
