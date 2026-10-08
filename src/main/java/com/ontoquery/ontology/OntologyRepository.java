package com.ontoquery.ontology;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 启动时全量加载 ont_* 表到内存 OntologyModel（类/实例/同义词/关系/属性/映射/规则）。
 * 库内无种子数据时得到空本体，管线按超纲问题处理，不影响启动。
 */
@Component
public class OntologyRepository {

    private final JdbcTemplate jdbcTemplate;

    private volatile OntologyModel model;

    public OntologyRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.model = load(jdbcTemplate);
    }

    public OntologyModel getModel() {
        return model;
    }

    /** 热加载：从库中重建模型并原子换引用（图谱/管线随后读到新数据）。 */
    public synchronized OntologyModel reload() {
        this.model = load(jdbcTemplate);
        return this.model;
    }

    private OntologyModel load(JdbcTemplate jdbc) {
        OntologyModel m = new OntologyModel();
        try {
            loadClasses(jdbc, m);
            loadInstances(jdbc, m);
            loadSynonyms(jdbc, m);
            loadRelations(jdbc, m);
            loadAttributes(jdbc, m);
            loadMappings(jdbc, m);
            loadRules(jdbc, m);
        } catch (DataAccessException e) {
            throw new IllegalStateException("本体元数据加载失败，请确认 ont_* 表已初始化: " + e.getMessage(), e);
        }
        return m;
    }

    private void loadClasses(JdbcTemplate jdbc, OntologyModel m) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, code, name_cn, parent_id, color, sort_no, remark FROM ont_class ORDER BY id");
        Map<Long, String> codeById = new HashMap<>(rows.size() * 2);
        for (Map<String, Object> row : rows) {
            codeById.put(asLong(row.get("id")), String.valueOf(row.get("code")));
        }
        for (Map<String, Object> row : rows) {
            Long parentId = asLong(row.get("parent_id"));
            m.addClass(String.valueOf(row.get("code")), String.valueOf(row.get("name_cn")),
                    parentId == null ? null : codeById.get(parentId),
                    row.get("color") == null ? null : String.valueOf(row.get("color")),
                    row.get("sort_no") == null ? Integer.valueOf(0)
                            : Integer.valueOf(((Number) row.get("sort_no")).intValue()),
                    row.get("remark") == null ? null : String.valueOf(row.get("remark")));
        }
    }

    private void loadInstances(JdbcTemplate jdbc, OntologyModel m) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT i.id, i.code, i.name_cn, i.remark, c.code AS class_code FROM ont_instance i"
                        + " JOIN ont_class c ON c.id = i.class_id ORDER BY i.id");
        for (Map<String, Object> row : rows) {
            m.addInstance(String.valueOf(row.get("code")), String.valueOf(row.get("class_code")),
                    String.valueOf(row.get("name_cn")),
                    row.get("remark") == null ? null : String.valueOf(row.get("remark")));
        }
    }

    private void loadSynonyms(JdbcTemplate jdbc, OntologyModel m) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.term, s.instance_id, s.class_id, ic.code AS instance_code, cc.code AS class_code"
                        + " FROM ont_synonym s"
                        + " LEFT JOIN ont_instance ic ON ic.id = s.instance_id"
                        + " LEFT JOIN ont_class cc ON cc.id = s.class_id ORDER BY s.id");
        for (Map<String, Object> row : rows) {
            m.addSynonym(String.valueOf(row.get("term")),
                    row.get("instance_code") == null ? null : String.valueOf(row.get("instance_code")),
                    row.get("class_code") == null ? null : String.valueOf(row.get("class_code")));
        }
    }

    private void loadRelations(JdbcTemplate jdbc, OntologyModel m) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT r.id, r.code, r.name_cn, r.cardinality, r.remark,"
                        + " dc.code AS domain_code, rc.code AS range_code FROM ont_relation r"
                        + " JOIN ont_class dc ON dc.id = r.domain_class_id"
                        + " JOIN ont_class rc ON rc.id = r.range_class_id ORDER BY r.id");
        for (Map<String, Object> row : rows) {
            m.addRelation(String.valueOf(row.get("code")), String.valueOf(row.get("name_cn")),
                    String.valueOf(row.get("domain_code")), String.valueOf(row.get("range_code")),
                    row.get("cardinality") == null ? null : String.valueOf(row.get("cardinality")),
                    row.get("remark") == null ? null : String.valueOf(row.get("remark")));
        }
    }

    private void loadAttributes(JdbcTemplate jdbc, OntologyModel m) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT a.id, a.code, a.name_cn, a.data_type, a.unit, a.value_low, a.value_high, a.remark,"
                        + " c.code AS class_code FROM ont_attribute a"
                        + " JOIN ont_class c ON c.id = a.class_id ORDER BY a.id");
        for (Map<String, Object> row : rows) {
            m.addAttribute(String.valueOf(row.get("class_code")), String.valueOf(row.get("code")),
                    String.valueOf(row.get("name_cn")), String.valueOf(row.get("data_type")),
                    row.get("unit") == null ? null : String.valueOf(row.get("unit")),
                    asDecimal(row.get("value_low")), asDecimal(row.get("value_high")),
                    row.get("remark") == null ? null : String.valueOf(row.get("remark")));
        }
    }

    private void loadMappings(JdbcTemplate jdbc, OntologyModel m) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT kind, ref_id, table_name, column_name, join_condition, value_expr FROM ont_mapping"
                        + " ORDER BY id");
        for (Map<String, Object> row : rows) {
            String kind = String.valueOf(row.get("kind"));
            String refCode = resolveRefCode(jdbc, kind, asLong(row.get("ref_id")));
            if (refCode == null) {
                continue;
            }
            m.addMapping(kind, refCode,
                    row.get("table_name") == null ? null : String.valueOf(row.get("table_name")),
                    row.get("column_name") == null ? null : String.valueOf(row.get("column_name")),
                    row.get("join_condition") == null ? null : String.valueOf(row.get("join_condition")),
                    row.get("value_expr") == null ? null : String.valueOf(row.get("value_expr")));
        }
    }

    private String resolveRefCode(JdbcTemplate jdbc, String kind, Long refId) {
        String table;
        switch (kind) {
            case OntologyModel.MAPPING_KIND_CLASS:
                table = "ont_class";
                break;
            case OntologyModel.MAPPING_KIND_RELATION:
                table = "ont_relation";
                break;
            case OntologyModel.MAPPING_KIND_ATTRIBUTE:
                table = "ont_attribute";
                break;
            case OntologyModel.MAPPING_KIND_INSTANCE:
                table = "ont_instance";
                break;
            default:
                return null;
        }
        List<String> codes = jdbc.queryForList("SELECT code FROM " + table + " WHERE id = ?", String.class, refId);
        return codes.isEmpty() ? null : codes.get(0);
    }

    private void loadRules(JdbcTemplate jdbc, OntologyModel m) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT rule_code, rule_type, name_cn, config FROM ont_rule ORDER BY id");
        for (Map<String, Object> row : rows) {
            m.addRule(String.valueOf(row.get("rule_code")), String.valueOf(row.get("rule_type")),
                    String.valueOf(row.get("name_cn")), String.valueOf(row.get("config")));
        }
    }

    private Long asLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        return new BigDecimal(String.valueOf(value));
    }
}
