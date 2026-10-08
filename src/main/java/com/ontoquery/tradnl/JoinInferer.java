package com.ontoquery.tradnl;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 传统管线第三步：按"同名列"推断候选 JOIN（患者编号/就诊编号出现在多表即视为可关联）。
 * 只看列名字面相等，不理解基数语义——这正是后续 COUNT 膨胀风险的来源。
 *
 * @author 月夜烛峰
 */
@Component
public class JoinInferer {

    /** 候选关联：tableA.column = tableB.column */
    public record JoinCandidate(String tableA, String tableB, String columnName) {

        @Override
        public String toString() {
            return tableA + "." + columnName + " = " + tableB + "." + columnName;
        }
    }

    public List<JoinCandidate> infer(List<SchemaLinker.SchemaColumn> schemaColumns) {
        Map<String, List<String>> columnTables = new LinkedHashMap<>(32);
        for (SchemaLinker.SchemaColumn col : schemaColumns) {
            columnTables.computeIfAbsent(col.columnName(), k -> new ArrayList<>(4)).add(col.tableName());
        }
        List<JoinCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : columnTables.entrySet()) {
            List<String> tables = entry.getValue();
            if (tables.size() < 2) {
                continue;
            }
            for (int i = 0; i < tables.size(); i++) {
                for (int j = i + 1; j < tables.size(); j++) {
                    candidates.add(new JoinCandidate(tables.get(i), tables.get(j), entry.getKey()));
                }
            }
        }
        return candidates;
    }
}
