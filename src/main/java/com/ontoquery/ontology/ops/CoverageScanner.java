package com.ontoquery.ontology.ops;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 覆盖缺口扫描：三组固定（表，名称列，归属类集合）与已收录词条比对，
 * 找出数据中存在而本体未收录的名称写法。表列名为代码常量，无注入面。
 * 类集合：种子把糖尿病实例放在 CLS_DIABETES、降糖药横跨 CLS_ORAL_HYPO/CLS_INSULIN，
 * 名称列的实际取值横跨多个类，故按类集合联合比对，避免把已收录实例名误报为缺口。
 *
 * @author 月夜烛峰
 */
@Service
public class CoverageScanner {

    /** 每组最多取的写法数（按行数降序）；100 覆盖种子全部词形，含排序靠后的英文散布写法 */
    static final int TERM_LIMIT = 100;

    private record ScanTarget(String tableName, String columnName, List<String> classCodes) {
    }

    private static final List<ScanTarget> TARGETS = List.of(
            new ScanTarget("fact_diagnosis", "disease_name", List.of("CLS_DISEASE", "CLS_DIABETES")),
            new ScanTarget("fact_medication", "drug_name",
                    List.of("CLS_DRUG", "CLS_ORAL_HYPO", "CLS_INSULIN")),
            new ScanTarget("fact_lab_result", "test_name", List.of("CLS_LAB_TEST")));

    private final JdbcTemplate jdbc;

    public CoverageScanner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 执行扫描：每组列出未收录写法（term + 出现行数）。 */
    public Map<String, Object> scan() {
        List<Map<String, Object>> columns = new ArrayList<>(TARGETS.size());
        for (ScanTarget target : TARGETS) {
            Set<String> known = knownTerms(target.classCodes());
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT " + target.columnName() + " AS term, COUNT(*) AS cnt FROM " + target.tableName()
                            + " GROUP BY " + target.columnName() + " ORDER BY cnt DESC, term LIMIT ?",
                    TERM_LIMIT);
            columns.add(Map.of("table", target.tableName(), "column", target.columnName(),
                    "classCode", target.classCodes().get(0), "classCodes", target.classCodes(),
                    "unmapped", filterUnmapped(rows, known)));
        }
        return Map.of("columns", columns);
    }

    /** 已收录词条 = 类集合全部实例标准名 + 指向这些实例的同义词（normalize 后比对）。 */
    private Set<String> knownTerms(List<String> classCodes) {
        String placeholders = String.join(",", Collections.nCopies(classCodes.size(), "?"));
        List<String> names = jdbc.queryForList(
                "SELECT i.name_cn FROM ont_instance i JOIN ont_class c ON c.id = i.class_id"
                        + " WHERE c.code IN (" + placeholders + ")", String.class, classCodes.toArray());
        List<String> synonyms = jdbc.queryForList(
                "SELECT s.term FROM ont_synonym s JOIN ont_instance i ON i.id = s.instance_id"
                        + " JOIN ont_class c ON c.id = i.class_id WHERE c.code IN (" + placeholders + ")",
                String.class, classCodes.toArray());
        Set<String> terms = new HashSet<>(names.size() + synonyms.size());
        names.forEach(name -> terms.add(normalize(name)));
        synonyms.forEach(term -> terms.add(normalize(term)));
        return terms;
    }

    /** 纯函数：rows（term/cnt 列）过滤掉已收录词条。 */
    static List<Map<String, Object>> filterUnmapped(List<Map<String, Object>> rows, Set<String> known) {
        List<Map<String, Object>> unmapped = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String term = String.valueOf(row.get("term"));
            if (!known.contains(normalize(term))) {
                unmapped.add(Map.of("term", term, "rowCount", row.get("cnt")));
            }
        }
        return unmapped;
    }

    /** 归一化：去首尾空白 + 小写（大小写不敏感比对）。 */
    static String normalize(String term) {
        return term == null ? "" : term.trim().toLowerCase(Locale.ROOT);
    }
}
