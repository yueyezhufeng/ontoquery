package com.ontoquery.ontology.trace;

import java.util.ArrayList;
import java.util.List;

/**
 * 解析管线步骤轨迹。两条管线（本体增强 / 传统 NL2SQL）统一使用本模型，
 * 前端按 steps[].items[].type 渲染可视化。
 *
 * item.type 取值：
 *  - kv          : {label, from, to, rule, level} 键值型推理决策
 *  - entity-chip : {label, from, to}              实体提及到本体概念（term 到 canonical）
 *  - relation-edge: {from, to, label}             A -关系-> B
 *  - path-chain  : {values[]}                     节点胶囊 + 箭头链
 *  - sql-pre     : {text}                         等宽代码片段
 *  - kv-table    : {columns[], rows[][]}          小型表格
 *  - text        : {text, level}                  纯文本行
 */
public class TraceContext {

    private final List<TraceStep> steps = new ArrayList<>();

    /** 开始一步计时，返回步骤对象；结束时调用 end(...) */
    public TraceStep begin(String key, String title) {
        TraceStep step = new TraceStep();
        step.setKey(key);
        step.setTitle(title);
        step.setStartedAt(System.nanoTime());
        step.setItems(new ArrayList<>());
        steps.add(step);
        return step;
    }

    /** 结束一步：写状态与摘要并计算耗时 */
    public void end(TraceStep step, String status, String summary) {
        step.setStatus(status);
        step.setSummary(summary);
        step.setElapsedMs(Math.max(1, (System.nanoTime() - step.getStartedAt()) / 1_000_000));
    }

    /** 异常快速结束 */
    public void fail(TraceStep step, String message) {
        end(step, "error", message);
    }

    public List<TraceStep> getSteps() { return steps; }

    public static class TraceStep {
        private String key;
        private String title;
        /** ok | warn | error */
        private String status = "ok";
        private Long elapsedMs;
        private String summary = "";
        private transient long startedAt;
        private List<TraceItem> items;

        public TraceItem kv(String label, String from, String to) {
            return add(TraceItem.kv(label, from, to));
        }
        public TraceItem kv(String label, String from, String to, String rule) {
            return add(TraceItem.kv(label, from, to, rule));
        }
        public TraceItem entityChip(String from, String to) {
            return add(TraceItem.entityChip(from, to));
        }
        public TraceItem relationEdge(String from, String to, String label) {
            return add(TraceItem.relationEdge(from, to, label));
        }
        public TraceItem pathChain(List<String> values) {
            return add(TraceItem.pathChain(values));
        }
        public TraceItem sqlPre(String text) {
            return add(TraceItem.sqlPre(text));
        }
        public TraceItem text(String text, String level) {
            return add(TraceItem.text(text, level));
        }
        public TraceItem kvTable(List<String> columns, List<List<String>> rows) {
            return add(TraceItem.kvTable(columns, rows));
        }
        private TraceItem add(TraceItem item) {
            items.add(item);
            return item;
        }

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Long getElapsedMs() { return elapsedMs; }
        public void setElapsedMs(Long elapsedMs) { this.elapsedMs = elapsedMs; }
        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }
        public long getStartedAt() { return startedAt; }
        public void setStartedAt(long startedAt) { this.startedAt = startedAt; }
        public List<TraceItem> getItems() { return items; }
        public void setItems(List<TraceItem> items) { this.items = items; }

        @Override
        public String toString() {
            return "TraceStep{key='" + key + "', title='" + title + "', status='" + status + "', elapsedMs="
                    + elapsedMs + ", summary='" + summary + "', items=" + (items == null ? 0 : items.size()) + '}';
        }
    }

    public static class TraceItem {
        private String type;
        private String label;
        private String from;
        private String to;
        private String rule;
        private String text;
        /** ok | warn | error | info */
        private String level = "ok";
        private List<String> values;
        private List<String> columns;
        private List<List<String>> rows;

        public static TraceItem kv(String label, String from, String to) {
            TraceItem i = new TraceItem();
            i.type = "kv"; i.label = label; i.from = from; i.to = to;
            return i;
        }
        public static TraceItem kv(String label, String from, String to, String rule) {
            TraceItem i = kv(label, from, to);
            i.rule = rule;
            return i;
        }
        public static TraceItem entityChip(String from, String to) {
            TraceItem i = new TraceItem();
            i.type = "entity-chip"; i.from = from; i.to = to;
            return i;
        }
        public static TraceItem relationEdge(String from, String to, String label) {
            TraceItem i = new TraceItem();
            i.type = "relation-edge"; i.from = from; i.to = to; i.label = label;
            return i;
        }
        public static TraceItem pathChain(List<String> values) {
            TraceItem i = new TraceItem();
            i.type = "path-chain"; i.values = values;
            return i;
        }
        public static TraceItem sqlPre(String text) {
            TraceItem i = new TraceItem();
            i.type = "sql-pre"; i.text = text;
            return i;
        }
        public static TraceItem text(String text, String level) {
            TraceItem i = new TraceItem();
            i.type = "text"; i.text = text; i.level = level;
            return i;
        }
        public static TraceItem kvTable(List<String> columns, List<List<String>> rows) {
            TraceItem i = new TraceItem();
            i.type = "kv-table"; i.columns = columns; i.rows = rows;
            return i;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
        public String getRule() { return rule; }
        public void setRule(String rule) { this.rule = rule; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        public String getLevel() { return level; }
        public void setLevel(String level) { this.level = level; }
        public List<String> getValues() { return values; }
        public void setValues(List<String> values) { this.values = values; }
        public List<String> getColumns() { return columns; }
        public void setColumns(List<String> columns) { this.columns = columns; }
        public List<List<String>> getRows() { return rows; }
        public void setRows(List<List<String>> rows) { this.rows = rows; }

        @Override
        public String toString() {
            return "TraceItem{type='" + type + "', label='" + label + "', from='" + from + "', to='" + to + "'}";
        }
    }
}
