package com.ontoquery.ontology.ops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 本体写操作：实例/同义词/映射 CRUD 与审计落库。
 * 全部参数绑定；核心保护集（CONTRACT 第 8 节三实例）禁改删禁改映射。
 *
 * @author 月夜烛峰
 */
@Service
public class OntologyWriteService {

    private static final Logger log = LoggerFactory.getLogger(OntologyWriteService.class);

    /** 核心保护集：黄金 SQL 逐字符依赖，禁止 update/delete/改映射（加同义词放行） */
    public static final Set<String> PROTECTED_INSTANCE_CODES = Set.of("E11", "D_METFORMIN", "LAB_HBA1C");

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

    /** 审计镜像 JSON 的反序列化目标类型 */
    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> IMAGE_TYPE
            = new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
    };

    private static final String KIND_INSTANCE = "instance";
    private static final String KIND_SYNONYM = "synonym";
    private static final String KIND_MAPPING = "mapping";
    private static final String OP_CREATE = "create";
    private static final String OP_UPDATE = "update";
    private static final String OP_DELETE = "delete";
    private static final String OP_REVERT = "revert";
    private static final String OPERATOR_LOCAL = "local";
    private static final String MAPPING_KIND_INSTANCE = "instance";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public OntologyWriteService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------ 实例

    /** 创建实例（可选携带同义词与映射，同一事务同一变更集）。返回 changesetId。 */
    @Transactional
    public Long createInstance(String classCode, String code, String nameCn, String remark,
            List<String> synonyms, OpsRequests.MappingBody mapping) {
        requireText(classCode, "classCode 不能为空");
        requireText(code, "code 不能为空");
        requireText(nameCn, "nameCn 不能为空");
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw reject(OpsError.BAD_PARAM, "code 仅允许字母数字下划线且长度 1-64: " + code);
        }
        Long classId = classIdByCode(classCode);
        if (instanceIdByCode(code) != null) {
            throw reject(OpsError.DUPLICATE, "实例编码已存在: " + code);
        }
        List<String> terms = normalizeSynonyms(synonyms);
        if (mapping != null) {
            validateMapping(mapping);
        }
        long changeset = nextChangesetId();
        jdbc.update("INSERT INTO ont_instance (class_id, code, name_cn, remark) VALUES (?, ?, ?, ?)",
                classId, code, nameCn, remark);
        audit(changeset, OP_CREATE, KIND_INSTANCE, code, null,
                instanceImage(classCode, code, nameCn, remark));
        Long instanceId = instanceIdByCode(code);
        for (String term : terms) {
            jdbc.update("INSERT INTO ont_synonym (term, instance_id) VALUES (?, ?)", term, instanceId);
            audit(changeset, OP_CREATE, KIND_SYNONYM, term, null, synonymImage(term, code));
        }
        if (mapping != null) {
            jdbc.update("INSERT INTO ont_mapping (kind, ref_id, table_name, column_name, value_expr)"
                    + " VALUES (?, ?, ?, ?, ?)", MAPPING_KIND_INSTANCE, instanceId,
                    mapping.getTable(), mapping.getColumn(), mapping.getValueExpr());
            audit(changeset, OP_CREATE, KIND_MAPPING, code, null, mappingImage(code, mapping));
        }
        log.info("创建实例 {}（{}），changeset={}", code, classCode, changeset);
        return changeset;
    }

    /** 改名/备注：null 字段不改，空串可清空 remark；code 不可改。 */
    @Transactional
    public Long updateInstance(String code, String nameCn, String remark) {
        requireText(code, "code 不能为空");
        Long id = instanceIdOrThrow(code);
        assertNotProtected(code);
        if (nameCn == null && remark == null) {
            throw reject(OpsError.BAD_PARAM, "至少提供 nameCn 或 remark 之一");
        }
        if (nameCn != null && nameCn.isBlank()) {
            throw reject(OpsError.BAD_PARAM, "nameCn 不能为空白");
        }
        Map<String, Object> before = instanceRow(id);
        String newName = nameCn != null ? nameCn : String.valueOf(before.get("name_cn"));
        Object newRemark = remark != null ? remark : before.get("remark");
        jdbc.update("UPDATE ont_instance SET name_cn = ?, remark = ? WHERE id = ?", newName, newRemark, id);
        long changeset = nextChangesetId();
        audit(changeset, OP_UPDATE, KIND_INSTANCE, code,
                instanceImage(String.valueOf(before.get("class_code")), code,
                        String.valueOf(before.get("name_cn")), before.get("remark")),
                instanceImage(String.valueOf(before.get("class_code")), code, newName, newRemark));
        return changeset;
    }

    /** 删除实例：同事务级联删同义词与映射。审计行序：同义词/映射先行，实例最后（回退逆序重建依赖此序）。 */
    @Transactional
    public Long deleteInstance(String code) {
        requireText(code, "code 不能为空");
        Long id = instanceIdOrThrow(code);
        assertNotProtected(code);
        List<Map<String, Object>> synonyms = jdbc.queryForList(
                "SELECT term FROM ont_synonym WHERE instance_id = ?", id);
        List<Map<String, Object>> mappings = jdbc.queryForList(
                "SELECT table_name, column_name, value_expr FROM ont_mapping WHERE kind = ? AND ref_id = ?",
                MAPPING_KIND_INSTANCE, id);
        Map<String, Object> row = instanceRow(id);
        String classCode = String.valueOf(row.get("class_code"));
        long changeset = nextChangesetId();
        jdbc.update("DELETE FROM ont_mapping WHERE kind = ? AND ref_id = ?", MAPPING_KIND_INSTANCE, id);
        for (Map<String, Object> synonym : synonyms) {
            audit(changeset, OP_DELETE, KIND_SYNONYM, String.valueOf(synonym.get("term")),
                    synonymImage(String.valueOf(synonym.get("term")), code), null);
        }
        if (!mappings.isEmpty()) {
            Map<String, Object> m = mappings.get(0);
            audit(changeset, OP_DELETE, KIND_MAPPING, code,
                    mappingImage(code, String.valueOf(m.get("table_name")), String.valueOf(m.get("column_name")),
                            String.valueOf(m.get("value_expr"))), null);
        }
        jdbc.update("DELETE FROM ont_synonym WHERE instance_id = ?", id);
        jdbc.update("DELETE FROM ont_instance WHERE id = ?", id);
        audit(changeset, OP_DELETE, KIND_INSTANCE, code,
                instanceImage(classCode, code, String.valueOf(row.get("name_cn")),
                        String.valueOf(row.get("remark"))), null);
        log.info("删除实例 {} 及 {} 同义词 {} 映射，changeset={}", code, synonyms.size(),
                mappings.size(), changeset);
        return changeset;
    }

    // ------------------------------------------------------------ 同义词

    /** 加同义词：trim 后查重；目标实例须存在；保护实例放行（新写法上线主场景）。 */
    @Transactional
    public Long addSynonym(String rawTerm, String targetCode) {
        requireText(rawTerm, "term 不能为空");
        String term = rawTerm.trim();
        requireText(targetCode, "targetCode 不能为空");
        Long instanceId = instanceIdOrThrow(targetCode);
        Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM ont_synonym WHERE term = ?",
                Long.class, term);
        if (exists != null && exists.intValue() > 0) {
            throw reject(OpsError.DUPLICATE, "同义词条已存在: " + term);
        }
        long changeset = nextChangesetId();
        jdbc.update("INSERT INTO ont_synonym (term, instance_id) VALUES (?, ?)", term, instanceId);
        audit(changeset, OP_CREATE, KIND_SYNONYM, term, null, synonymImage(term, targetCode));
        log.info("新增同义词 {} -> {}，changeset={}", term, targetCode, changeset);
        return changeset;
    }

    /** 删同义词：类级（种子）拦截，实例级放行。 */
    @Transactional
    public Long removeSynonym(String rawTerm) {
        requireText(rawTerm, "term 不能为空");
        String term = rawTerm.trim();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, term, instance_id FROM ont_synonym WHERE term = ?", term);
        if (rows.isEmpty()) {
            throw reject(OpsError.NOT_FOUND, "同义词条不存在: " + term);
        }
        Map<String, Object> row = rows.get(0);
        if (row.get("instance_id") == null) {
            throw reject(OpsError.PROTECTED, "类级同义词为种子保护，不可删除: " + term);
        }
        Long instanceId = ((Number) row.get("instance_id")).longValue();
        String instanceCode = jdbc.queryForObject(
                "SELECT code FROM ont_instance WHERE id = ?", String.class, instanceId);
        long changeset = nextChangesetId();
        jdbc.update("DELETE FROM ont_synonym WHERE id = ?", ((Number) row.get("id")).longValue());
        audit(changeset, OP_DELETE, KIND_SYNONYM, term, synonymImage(term, instanceCode), null);
        return changeset;
    }

    // ------------------------------------------------------------ 映射

    /** 改实例映射：保护集拦截；valueExpr 白名单校验；无则插入有则更新。 */
    @Transactional
    public Long updateInstanceMapping(String instanceCode, OpsRequests.MappingBody mapping) {
        requireText(instanceCode, "instanceCode 不能为空");
        Long instanceId = instanceIdOrThrow(instanceCode);
        assertNotProtected(instanceCode);
        requireText(mapping.getTable(), "mapping.table 不能为空");
        requireText(mapping.getColumn(), "mapping.column 不能为空");
        String reason = ValueExprValidator.rejectReason(mapping.getTable(), mapping.getColumn(),
                mapping.getValueExpr(), jdbc);
        if (reason != null) {
            throw reject(OpsError.VALUE_EXPR, reason);
        }
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT id, table_name, column_name, value_expr FROM ont_mapping"
                        + " WHERE kind = ? AND ref_id = ?", MAPPING_KIND_INSTANCE, instanceId);
        long changeset = nextChangesetId();
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO ont_mapping (kind, ref_id, table_name, column_name, value_expr)"
                            + " VALUES (?, ?, ?, ?, ?)", MAPPING_KIND_INSTANCE, instanceId,
                    mapping.getTable(), mapping.getColumn(), mapping.getValueExpr());
            audit(changeset, OP_CREATE, KIND_MAPPING, instanceCode, null, mappingImage(instanceCode, mapping));
        } else {
            Map<String, Object> old = existing.get(0);
            jdbc.update("UPDATE ont_mapping SET table_name = ?, column_name = ?, value_expr = ? WHERE id = ?",
                    mapping.getTable(), mapping.getColumn(), mapping.getValueExpr(),
                    ((Number) old.get("id")).longValue());
            audit(changeset, OP_UPDATE, KIND_MAPPING, instanceCode,
                    mappingImage(instanceCode, String.valueOf(old.get("table_name")),
                            String.valueOf(old.get("column_name")), String.valueOf(old.get("value_expr"))),
                    mappingImage(instanceCode, mapping));
        }
        return changeset;
    }

    // ------------------------------------------------------------ 变更历史

    /** 最近 limit 个变更集（行倒序读出后按集分组，保持最新在前）。 */
    public List<Map<String, Object>> recentChanges(int limit) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT changeset_id, op_type, target_kind, target_code, is_revert, operator, create_time"
                        + " FROM ont_change_log ORDER BY id DESC LIMIT ?", limit);
        Map<Long, Map<String, Object>> bySet = new LinkedHashMap<>(rows.size() * 2);
        Map<Long, List<Map<String, Object>>> opsBySet = new LinkedHashMap<>(rows.size() * 2);
        for (Map<String, Object> row : rows) {
            Long setId = ((Number) row.get("changeset_id")).longValue();
            Map<String, Object> set = bySet.get(setId);
            if (set == null) {
                set = new LinkedHashMap<>(8);
                set.put("changesetId", setId);
                int revertFlag = ((Number) row.get("is_revert")).intValue();
                set.put("isRevert", Integer.valueOf(revertFlag).equals(Integer.valueOf(1)));
                set.put("operator", String.valueOf(row.get("operator")));
                set.put("createTime", String.valueOf(row.get("create_time")));
                List<Map<String, Object>> ops = new ArrayList<>();
                set.put("ops", ops);
                bySet.put(setId, set);
                opsBySet.put(setId, ops);
            }
            Map<String, Object> op = new LinkedHashMap<>(4);
            op.put("opType", String.valueOf(row.get("op_type")));
            op.put("targetKind", String.valueOf(row.get("target_kind")));
            op.put("targetCode", String.valueOf(row.get("target_code")));
            opsBySet.get(setId).add(op);
        }
        return new ArrayList<>(bySet.values());
    }

    /** 可回退目标 = 按 id 最新的变更集且不是回退集（只回退最近一次动作）；否则 null。 */
    public Long latestRevertableChangesetId() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT changeset_id, is_revert FROM ont_change_log ORDER BY id DESC LIMIT 1");
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        if (((Number) row.get("is_revert")).intValue() == 1) {
            return null;
        }
        return ((Number) row.get("changeset_id")).longValue();
    }

    // ------------------------------------------------------------ 回退

    /** 回退最近一次变更（按 id 最新且非回退集，连续回退第二次 A0410）：按行逆序撤销，写入逆向变更集。 */
    @Transactional
    public Long revertLatest() {
        Long targetId = latestRevertableChangesetId();
        if (targetId == null) {
            throw reject(OpsError.NOTHING_TO_REVERT, "最新变更已是回退或暂无可回退的变更集");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT op_type, target_kind, target_code, before_json, after_json FROM ont_change_log"
                        + " WHERE changeset_id = ? ORDER BY id", targetId);
        long newChangeset = nextChangesetId();
        for (int i = rows.size() - 1; i >= 0; i--) {
            Map<String, Object> row = rows.get(i);
            String opType = String.valueOf(row.get("op_type"));
            String kind = String.valueOf(row.get("target_kind"));
            String targetCode = String.valueOf(row.get("target_code"));
            if (OP_CREATE.equals(opType)) {
                undoCreate(kind, targetCode);
            } else if (OP_DELETE.equals(opType)) {
                undoDelete(kind, targetCode, String.valueOf(row.get("before_json")));
            } else {
                restoreUpdate(kind, targetCode, String.valueOf(row.get("before_json")));
            }
            audit(newChangeset, OP_REVERT, kind, targetCode,
                    parseImage(String.valueOf(row.get("after_json"))),
                    parseImage(String.valueOf(row.get("before_json"))), true);
        }
        log.info("回退变更集 {} -> 新变更集 {}", targetId, newChangeset);
        return newChangeset;
    }

    private void undoCreate(String kind, String targetCode) {
        if (KIND_MAPPING.equals(kind)) {
            Long instanceId = instanceIdByCode(targetCode);
            if (instanceId != null) {
                jdbc.update("DELETE FROM ont_mapping WHERE kind = ? AND ref_id = ?",
                        MAPPING_KIND_INSTANCE, instanceId);
            }
        } else if (KIND_SYNONYM.equals(kind)) {
            jdbc.update("DELETE FROM ont_synonym WHERE term = ?", targetCode);
        } else {
            Long instanceId = instanceIdByCode(targetCode);
            if (instanceId != null) {
                jdbc.update("DELETE FROM ont_instance WHERE id = ?", instanceId);
            }
        }
    }

    private void undoDelete(String kind, String targetCode, String beforeJson) {
        Map<String, Object> before = parseImage(beforeJson);
        if (KIND_MAPPING.equals(kind)) {
            Long instanceId = instanceIdOrThrow(String.valueOf(before.get("instanceCode")));
            jdbc.update("INSERT INTO ont_mapping (kind, ref_id, table_name, column_name, value_expr)"
                            + " VALUES (?, ?, ?, ?, ?)", MAPPING_KIND_INSTANCE, instanceId,
                    String.valueOf(before.get("table")), String.valueOf(before.get("column")),
                    String.valueOf(before.get("valueExpr")));
        } else if (KIND_SYNONYM.equals(kind)) {
            Long instanceId = instanceIdOrThrow(String.valueOf(before.get("instanceCode")));
            jdbc.update("INSERT INTO ont_synonym (term, instance_id) VALUES (?, ?)",
                    String.valueOf(before.get("term")), instanceId);
        } else {
            Long classId = classIdByCode(String.valueOf(before.get("classCode")));
            jdbc.update("INSERT INTO ont_instance (class_id, code, name_cn, remark) VALUES (?, ?, ?, ?)",
                    classId, String.valueOf(before.get("code")), String.valueOf(before.get("nameCn")),
                    before.get("remark") == null ? null : String.valueOf(before.get("remark")));
        }
    }

    private void restoreUpdate(String kind, String targetCode, String beforeJson) {
        Map<String, Object> before = parseImage(beforeJson);
        if (KIND_MAPPING.equals(kind)) {
            Long instanceId = instanceIdOrThrow(String.valueOf(before.get("instanceCode")));
            jdbc.update("UPDATE ont_mapping SET table_name = ?, column_name = ?, value_expr = ?"
                            + " WHERE kind = ? AND ref_id = ?",
                    String.valueOf(before.get("table")), String.valueOf(before.get("column")),
                    String.valueOf(before.get("valueExpr")), MAPPING_KIND_INSTANCE, instanceId);
        } else {
            Long instanceId = instanceIdOrThrow(targetCode);
            jdbc.update("UPDATE ont_instance SET name_cn = ?, remark = ? WHERE id = ?",
                    String.valueOf(before.get("nameCn")),
                    before.get("remark") == null ? null : String.valueOf(before.get("remark")), instanceId);
        }
    }

    private Map<String, Object> parseImage(String json) {
        if (json == null || "null".equals(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, IMAGE_TYPE);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("审计镜像解析失败: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------ 公共助手

    private OpsRejectException reject(String code, String message) {
        return new OpsRejectException(code, message);
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw reject(OpsError.BAD_PARAM, message);
        }
    }

    private void assertNotProtected(String code) {
        if (PROTECTED_INSTANCE_CODES.contains(code)) {
            throw reject(OpsError.PROTECTED, "核心保护实例禁止该操作: " + code);
        }
    }

    private Long classIdByCode(String classCode) {
        try {
            return jdbc.queryForObject("SELECT id FROM ont_class WHERE code = ?", Long.class, classCode);
        } catch (EmptyResultDataAccessException e) {
            throw reject(OpsError.NOT_FOUND, "类不存在: " + classCode);
        }
    }

    private Long instanceIdByCode(String code) {
        try {
            return jdbc.queryForObject("SELECT id FROM ont_instance WHERE code = ?", Long.class, code);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    private Long instanceIdOrThrow(String code) {
        Long id = instanceIdByCode(code);
        if (id == null) {
            throw reject(OpsError.NOT_FOUND, "实例不存在: " + code);
        }
        return id;
    }

    /** 同义词条规范化：trim、去空、去重、库内查重（trim 后判定） */
    private List<String> normalizeSynonyms(List<String> synonyms) {
        if (synonyms == null || synonyms.isEmpty()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>(synonyms.size());
        for (String raw : synonyms) {
            String term = raw == null ? "" : raw.trim();
            if (term.isEmpty()) {
                throw reject(OpsError.BAD_PARAM, "同义词条不能为空白");
            }
            if (terms.contains(term)) {
                continue;
            }
            Long exists = jdbc.queryForObject("SELECT COUNT(*) FROM ont_synonym WHERE term = ?",
                    Long.class, term);
            if (exists != null && exists.intValue() > 0) {
                throw reject(OpsError.DUPLICATE, "同义词条已存在: " + term);
            }
            terms.add(term);
        }
        return terms;
    }

    /** 映射校验：必填 + valueExpr 白名单（语法/表白名单/列存在性） */
    private void validateMapping(OpsRequests.MappingBody mapping) {
        requireText(mapping.getTable(), "mapping.table 不能为空");
        requireText(mapping.getColumn(), "mapping.column 不能为空");
        String reason = ValueExprValidator.rejectReason(mapping.getTable(), mapping.getColumn(),
                mapping.getValueExpr(), jdbc);
        if (reason != null) {
            throw reject(OpsError.VALUE_EXPR, reason);
        }
    }

    private long nextChangesetId() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(changeset_id), 0) + 1 FROM ont_change_log",
                Long.class);
        return max == null ? 1L : max.longValue();
    }

    private void audit(long changesetId, String opType, String targetKind, String targetCode,
            Map<String, Object> before, Map<String, Object> after) {
        audit(changesetId, opType, targetKind, targetCode, before, after, false);
    }

    private void audit(long changesetId, String opType, String targetKind, String targetCode,
            Map<String, Object> before, Map<String, Object> after, boolean isRevert) {
        jdbc.update("INSERT INTO ont_change_log (changeset_id, op_type, target_kind, target_code,"
                        + " before_json, after_json, is_revert, operator) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                changesetId, opType, targetKind, targetCode, toJson(before), toJson(after),
                Integer.valueOf(isRevert ? 1 : 0), OPERATOR_LOCAL);
    }

    private String toJson(Map<String, Object> image) {
        if (image == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(image);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审计镜像序列化失败: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> instanceRow(Long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT i.code, i.name_cn, i.remark, c.code AS class_code FROM ont_instance i"
                        + " JOIN ont_class c ON c.id = i.class_id WHERE i.id = ?", id);
        if (rows.isEmpty()) {
            throw reject(OpsError.NOT_FOUND, "实例行不存在: id=" + id);
        }
        return rows.get(0);
    }

    private Map<String, Object> instanceImage(String classCode, String code, String nameCn, Object remark) {
        Map<String, Object> m = new LinkedHashMap<>(4);
        m.put("classCode", classCode);
        m.put("code", code);
        m.put("nameCn", nameCn);
        m.put("remark", remark);
        return m;
    }

    private Map<String, Object> synonymImage(String term, String instanceCode) {
        Map<String, Object> m = new LinkedHashMap<>(2);
        m.put("term", term);
        m.put("instanceCode", instanceCode);
        return m;
    }

    private Map<String, Object> mappingImage(String instanceCode, OpsRequests.MappingBody mapping) {
        return mappingImage(instanceCode, mapping.getTable(), mapping.getColumn(), mapping.getValueExpr());
    }

    private Map<String, Object> mappingImage(String instanceCode, String table, String column, String valueExpr) {
        Map<String, Object> m = new LinkedHashMap<>(4);
        m.put("instanceCode", instanceCode);
        m.put("table", table);
        m.put("column", column);
        m.put("valueExpr", valueExpr);
        return m;
    }
}
