package com.ontoquery.ontology;

import java.util.ArrayList;
import java.util.List;

/**
 * 本体管线中间计划：NER 提及 -> 关系边 -> 推理后的约束集 + 意图。
 * 由 RelationLinker / ReasoningService 填充，PathPlanner / PathToSqlBuilder 消费。
 */
public class QueryPlan {

    private String question;
    private String intent = Mention.INTENT_COUNT;
    private Long topN;
    private String groupColumn;
    /** 分组属性编码（trace 展示解析依据） */
    private String groupAttributeCode;
    /** 事件表分组的宿主事件类（如按诊断名称写法分组时为 CLS_DIAGNOSIS，按记录数计数） */
    private String groupHostClassCode;
    /** 分组取值表达式（{a} 为锚别名占位符；如年龄分段 CASE 桶表达式），空则用锚表分组列 */
    private String groupExpression;
    /** 记录数意图的事件类（问「多少条记录/人次」时数事件表行数而非患者数） */
    private String recordCountClassCode;
    /** 按计数 TopN 的事件类与分组列（如 就诊量最高的前五科室） */
    private String topNGroupClassCode;
    private String topNGroupColumn;
    /** 列举意图的目标事件类（如 哪些药品 -> CLS_MEDICATION） */
    private String listHostClassCode;
    /** 列举输出的展示列（如 drug_code / drug_name） */
    private List<String> listColumns;
    /** 列举意图的行级过滤约束（类提及约束转译：选择性闭包实例 + 时间/数值谓词挂清单表行） */
    private Constraint listRowConstraint;
    private Integer ambiguityCount = Integer.valueOf(0);
    private final List<Mention> mentions = new ArrayList<>();
    private final List<RelationEdge> edges = new ArrayList<>();
    private final List<Constraint> constraints = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getIntent() { return intent; }
    public void setIntent(String intent) { this.intent = intent; }
    public Long getTopN() { return topN; }
    public void setTopN(Long topN) { this.topN = topN; }
    public String getGroupColumn() { return groupColumn; }
    public void setGroupColumn(String groupColumn) { this.groupColumn = groupColumn; }
    public String getGroupAttributeCode() { return groupAttributeCode; }
    public void setGroupAttributeCode(String groupAttributeCode) { this.groupAttributeCode = groupAttributeCode; }
    public String getGroupHostClassCode() { return groupHostClassCode; }
    public void setGroupHostClassCode(String groupHostClassCode) { this.groupHostClassCode = groupHostClassCode; }
    public String getGroupExpression() { return groupExpression; }
    public void setGroupExpression(String groupExpression) { this.groupExpression = groupExpression; }
    public String getRecordCountClassCode() { return recordCountClassCode; }
    public void setRecordCountClassCode(String recordCountClassCode) { this.recordCountClassCode = recordCountClassCode; }
    public String getTopNGroupClassCode() { return topNGroupClassCode; }
    public void setTopNGroupClassCode(String topNGroupClassCode) { this.topNGroupClassCode = topNGroupClassCode; }
    public String getTopNGroupColumn() { return topNGroupColumn; }
    public void setTopNGroupColumn(String topNGroupColumn) { this.topNGroupColumn = topNGroupColumn; }
    public String getListHostClassCode() { return listHostClassCode; }
    public void setListHostClassCode(String listHostClassCode) { this.listHostClassCode = listHostClassCode; }
    public List<String> getListColumns() { return listColumns; }
    public void setListColumns(List<String> listColumns) { this.listColumns = listColumns; }
    public Constraint getListRowConstraint() { return listRowConstraint; }
    public void setListRowConstraint(Constraint listRowConstraint) { this.listRowConstraint = listRowConstraint; }
    public Integer getAmbiguityCount() { return ambiguityCount; }
    public void setAmbiguityCount(Integer ambiguityCount) { this.ambiguityCount = ambiguityCount; }
    public List<Mention> getMentions() { return mentions; }
    public List<RelationEdge> getEdges() { return edges; }
    public List<Constraint> getConstraints() { return constraints; }
    public List<String> getWarnings() { return warnings; }

    public void addWarning(String warning) {
        warnings.add(warning);
    }

    @Override
    public String toString() {
        return "QueryPlan{intent='" + intent + "', mentions=" + mentions.size() + ", constraints="
                + constraints.size() + ", ambiguityCount=" + ambiguityCount + '}';
    }

    /**
     * 关系边：实体所在类经语义/数据关系挂到的事件链。
     */
    public static class RelationEdge {
        private final String relationCode;
        private final String relationName;
        private final String fromClassCode;
        private final String fromClassName;
        private final String toClassCode;
        private final String toClassName;
        /** true=语义边（N:1，仅展示不生成 JOIN）；false=数据边（1:N，可生成 EXISTS） */
        private final boolean semantic;

        public RelationEdge(String relationCode, String relationName, String fromClassCode, String fromClassName,
                String toClassCode, String toClassName, boolean semantic) {
            this.relationCode = relationCode;
            this.relationName = relationName;
            this.fromClassCode = fromClassCode;
            this.fromClassName = fromClassName;
            this.toClassCode = toClassCode;
            this.toClassName = toClassName;
            this.semantic = semantic;
        }

        public String getRelationCode() { return relationCode; }
        public String getRelationName() { return relationName; }
        public String getFromClassCode() { return fromClassCode; }
        public String getFromClassName() { return fromClassName; }
        public String getToClassCode() { return toClassCode; }
        public String getToClassName() { return toClassName; }
        public boolean isSemantic() { return semantic; }

        @Override
        public String toString() {
            return "RelationEdge{" + fromClassCode + " -" + relationCode + "-> " + toClassCode
                    + (semantic ? " (semantic)" : "") + "}";
        }
    }

    /**
     * 约束：一个 EXISTS 子查询的语义内容（实例过滤 + 可选时间挂靠 + 可选数值比较）。
     */
    public static class Constraint {
        /** 展示用触发词（如 2型糖尿病 / HbA1c / 近一个月） */
        private final String triggerText;
        /** 排序键：触发提及在问题中的起始位置（别名 t0/t1/t2 按此编号） */
        private final Integer orderKey;
        /** 最近一次并入本约束的提及结束位置（判断两次提及之间是否由「或」连接） */
        private Integer lastMentionEnd;
        /** 实例编码（类级闭包展开后可能多个，生成 OR 谓词） */
        private final List<String> instanceCodes = new ArrayList<>();
        /** 实例宿主事件类（由实例映射表反查） */
        private String hostClassCode;
        /** 否定约束：提及前有「不含/排除/除外」等否定词，实例谓词取 NOT */
        private boolean negative;
        /** 来源为类级提及（子类闭包展开），列举意图据此遴选清单目标 */
        private boolean fromClassMention;
        /** 时间挂靠：天数与日期列 */
        private Integer timeDays;
        private String dateColumn;
        private String timeText;
        /** 数值比较：运算符 / 数值 / 列 / 属性编码 */
        private String valueOperator;
        private Double valueNumber;
        private String valueColumn;
        private String valueAttributeCode;
        /** 附加谓词模板（含 {t} 占位符，如 "{t}.visit_type = '住院'"），由值过滤规则写入 */
        private final List<String> extraPredicates = new ArrayList<>();

        public Constraint(String triggerText, Integer orderKey) {
            this.triggerText = triggerText;
            this.orderKey = orderKey;
        }

        public String getTriggerText() { return triggerText; }
        public Integer getOrderKey() { return orderKey; }
        public Integer getLastMentionEnd() { return lastMentionEnd; }
        public void setLastMentionEnd(Integer lastMentionEnd) { this.lastMentionEnd = lastMentionEnd; }
        public List<String> getInstanceCodes() { return instanceCodes; }
        public String getHostClassCode() { return hostClassCode; }
        public void setHostClassCode(String hostClassCode) { this.hostClassCode = hostClassCode; }
        public boolean isNegative() { return negative; }
        public void setNegative(boolean negative) { this.negative = negative; }
        public boolean isFromClassMention() { return fromClassMention; }
        public void setFromClassMention(boolean fromClassMention) { this.fromClassMention = fromClassMention; }
        public Integer getTimeDays() { return timeDays; }
        public void setTimeDays(Integer timeDays) { this.timeDays = timeDays; }
        public String getDateColumn() { return dateColumn; }
        public void setDateColumn(String dateColumn) { this.dateColumn = dateColumn; }
        public String getTimeText() { return timeText; }
        public void setTimeText(String timeText) { this.timeText = timeText; }
        public String getValueOperator() { return valueOperator; }
        public void setValueOperator(String valueOperator) { this.valueOperator = valueOperator; }
        public Double getValueNumber() { return valueNumber; }
        public void setValueNumber(Double valueNumber) { this.valueNumber = valueNumber; }
        public String getValueColumn() { return valueColumn; }
        public void setValueColumn(String valueColumn) { this.valueColumn = valueColumn; }
        public String getValueAttributeCode() { return valueAttributeCode; }
        public void setValueAttributeCode(String valueAttributeCode) { this.valueAttributeCode = valueAttributeCode; }
        public List<String> getExtraPredicates() { return extraPredicates; }

        public boolean hasTime() { return timeDays != null && dateColumn != null; }
        public boolean hasValue() { return valueOperator != null && valueColumn != null && valueNumber != null; }

        @Override
        public String toString() {
            return "Constraint{trigger='" + triggerText + "', instances=" + instanceCodes + ", host="
                    + hostClassCode + ", timeDays=" + timeDays + ", dateColumn='" + dateColumn + "'}";
        }
    }
}
