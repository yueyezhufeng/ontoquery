package com.ontoquery.ontology.ops;

import com.ontoquery.benchmark.BenchmarkRunner;
import com.ontoquery.ontology.OntologyPipeline;
import com.ontoquery.ontology.OntologyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 本体运维门面：before 校验 -> 写（事务）-> 热加载 -> after 校验 -> 报告。
 * 门面自身不开事务：写事务边界在 OntologyWriteService，提交后失败不回滚只上报。
 *
 * @author 月夜烛峰
 */
@Service
public class OntologyAdminService {

    private static final Logger log = LoggerFactory.getLogger(OntologyAdminService.class);

    private static final int RECENT_CHANGES_LIMIT = 50;
    private static final String MAPPING_KIND_INSTANCE = "instance";

    private final OntologyWriteService writeService;
    private final OntologyRepository repository;
    private final OntologyPipeline pipeline;
    private final BenchmarkRunner benchmarkRunner;
    private final CoverageScanner coverageScanner;
    private final JdbcTemplate jdbc;

    public OntologyAdminService(OntologyWriteService writeService, OntologyRepository repository,
            OntologyPipeline pipeline, BenchmarkRunner benchmarkRunner, CoverageScanner coverageScanner,
            JdbcTemplate jdbc) {
        this.writeService = writeService;
        this.repository = repository;
        this.pipeline = pipeline;
        this.benchmarkRunner = benchmarkRunner;
        this.coverageScanner = coverageScanner;
        this.jdbc = jdbc;
    }

    public Map<String, Object> createInstance(OpsRequests.CreateInstanceBody body) {
        return applyWithValidation(() -> writeService.createInstance(body.getClassCode(), body.getCode(),
                body.getNameCn(), body.getRemark(), body.getSynonyms(), body.getMapping()));
    }

    public Map<String, Object> updateInstance(OpsRequests.UpdateInstanceBody body) {
        return applyWithValidation(() -> writeService.updateInstance(body.getCode(),
                body.getNameCn(), body.getRemark()));
    }

    public Map<String, Object> deleteInstance(String code) {
        return applyWithValidation(() -> writeService.deleteInstance(code));
    }

    public Map<String, Object> addSynonym(OpsRequests.SynonymBody body) {
        return applyWithValidation(() -> writeService.addSynonym(body.getTerm(), body.getTargetCode()));
    }

    public Map<String, Object> removeSynonym(String term) {
        return applyWithValidation(() -> writeService.removeSynonym(term));
    }

    public Map<String, Object> updateMapping(OpsRequests.MappingUpdateBody body) {
        return applyWithValidation(() -> writeService.updateInstanceMapping(body.getInstanceCode(),
                toMappingBody(body)));
    }

    public Map<String, Object> revert() {
        return applyWithValidation(writeService::revertLatest);
    }

    /** 手动热加载：不进事务不写审计。 */
    public Map<String, Object> manualReload() {
        reload();
        return Map.of("reloaded", Boolean.TRUE);
    }

    /** 实例列表（DB 直读，含同义词/映射/保护标记）；classCode 为空返回全部。 */
    public List<Map<String, Object>> instances(String classCode) {
        String base = "SELECT i.id, i.code, i.name_cn, i.remark, c.code AS class_code, c.name_cn AS class_name"
                + " FROM ont_instance i JOIN ont_class c ON c.id = i.class_id";
        List<Map<String, Object>> rows = (classCode == null || classCode.isBlank())
                ? jdbc.queryForList(base + " ORDER BY c.sort_no, i.code")
                : jdbc.queryForList(base + " WHERE c.code = ? ORDER BY c.sort_no, i.code", classCode);
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Long id = ((Number) row.get("id")).longValue();
            Map<String, Object> item = new LinkedHashMap<>(8);
            item.put("code", String.valueOf(row.get("code")));
            item.put("nameCn", String.valueOf(row.get("name_cn")));
            item.put("remark", row.get("remark") == null ? "" : String.valueOf(row.get("remark")));
            item.put("classCode", String.valueOf(row.get("class_code")));
            item.put("className", String.valueOf(row.get("class_name")));
            item.put("protected", OntologyWriteService.PROTECTED_INSTANCE_CODES.contains(row.get("code")));
            item.put("synonyms", jdbc.queryForList(
                    "SELECT term FROM ont_synonym WHERE instance_id = ? ORDER BY id", String.class, id));
            item.put("mapping", mappingOf(id));
            result.add(item);
        }
        return result;
    }

    public List<Map<String, Object>> changes() {
        return writeService.recentChanges(RECENT_CHANGES_LIMIT);
    }

    public Map<String, Object> coverage() {
        return coverageScanner.scan();
    }

    // ------------------------------------------------------------ 编排

    private Map<String, Object> applyWithValidation(LongSupplier write) {
        BenchmarkRunner.OntologyCheck before = benchmarkRunner.runOntologyOnly();
        Long changesetId = write.getAsLong();
        try {
            reload();
        } catch (RuntimeException e) {
            log.error("变更 {} 已提交但热加载失败: {}", changesetId, e.getMessage(), e);
            throw new OpsRejectException(OpsError.RELOAD_FAILED,
                    "变更已保存（changeset=" + changesetId + "）但热加载失败: " + e.getMessage());
        }
        BenchmarkRunner.OntologyCheck after;
        try {
            after = benchmarkRunner.runOntologyOnly();
        } catch (RuntimeException e) {
            log.error("变更 {} 已提交但基准验证失败: {}", changesetId, e.getMessage(), e);
            throw new OpsRejectException(OpsError.RELOAD_FAILED,
                    "变更已保存（changeset=" + changesetId + "）但基准验证失败: " + e.getMessage());
        }
        return report(changesetId, before, after);
    }

    private void reload() {
        repository.reload();
        pipeline.rebuildRuntime();
    }

    private Map<String, Object> report(Long changesetId, BenchmarkRunner.OntologyCheck before,
            BenchmarkRunner.OntologyCheck after) {
        List<Integer> regressed = new ArrayList<>(after.failedQuestionNos());
        regressed.removeAll(before.failedQuestionNos());
        List<Integer> improved = new ArrayList<>(before.failedQuestionNos());
        improved.removeAll(after.failedQuestionNos());
        Map<String, Object> validation = new LinkedHashMap<>(8);
        validation.put("beforeAccuracy", before.accuracy());
        validation.put("afterAccuracy", after.accuracy());
        validation.put("beforeFailed", before.failedQuestionNos());
        validation.put("afterFailed", after.failedQuestionNos());
        validation.put("regressed", regressed);
        validation.put("improved", improved);
        Map<String, Object> result = new LinkedHashMap<>(4);
        result.put("changesetId", changesetId);
        result.put("applied", Boolean.TRUE);
        result.put("validation", validation);
        return result;
    }

    private Map<String, Object> mappingOf(Long instanceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT table_name, column_name, value_expr FROM ont_mapping"
                        + " WHERE kind = ? AND ref_id = ?", MAPPING_KIND_INSTANCE, instanceId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> mapping = new LinkedHashMap<>(4);
        mapping.put("table", String.valueOf(row.get("table_name")));
        mapping.put("column", String.valueOf(row.get("column_name")));
        mapping.put("valueExpr", String.valueOf(row.get("value_expr")));
        return mapping;
    }

    private OpsRequests.MappingBody toMappingBody(OpsRequests.MappingUpdateBody body) {
        OpsRequests.MappingBody mapping = new OpsRequests.MappingBody();
        mapping.setTable(body.getTable());
        mapping.setColumn(body.getColumn());
        mapping.setValueExpr(body.getValueExpr());
        return mapping;
    }
}
