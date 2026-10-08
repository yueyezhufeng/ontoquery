package com.ontoquery.ontology;

import java.util.List;

/**
 * 词典识别产出的提及（mention）。
 * type: entity（实例/类实体）/ time（时间窗）/ compare（比较词）/ number（数字）/ intent（意图词）。
 */
public class Mention {

    public static final String TYPE_ENTITY = "entity";
    public static final String TYPE_TIME = "time";
    public static final String TYPE_COMPARE = "compare";
    public static final String TYPE_NUMBER = "number";
    public static final String TYPE_INTENT = "intent";

    /** 实体命中方式：精确实例名 / 实例编码 / 同义词 / 类名 */
    public static final String VIA_INSTANCE_NAME = "instance-name";
    public static final String VIA_INSTANCE_CODE = "instance-code";
    public static final String VIA_SYNONYM = "synonym";
    public static final String VIA_CLASS_NAME = "class-name";

    /** 意图种类：计数 / TopN / 均值 / 分组计数 / 实例清单 */
    public static final String INTENT_COUNT = "count";
    public static final String INTENT_TOP_N = "topn";
    public static final String INTENT_AVG = "avg";
    public static final String INTENT_GROUP = "group";
    public static final String INTENT_LIST = "list";

    private final String text;
    private final String type;
    private final Integer begin;
    private final Integer end;
    private final Double score;
    private final String via;

    /** type=entity：命中的实例编码（多候选歧义时为首选）与类编码 */
    private final String instanceCode;
    private final String classCode;
    /** type=entity：同词命中的全部实例候选（跨类同名时多于一个，构成歧义） */
    private final List<String> candidateInstanceCodes;
    /** type=time：解析出的天数 */
    private final Integer timeDays;
    /** type=compare：比较运算符 > < >= */
    private final String compareOperator;
    /** type=number：数值（阿拉伯或中文数字解析结果） */
    private final Double numericValue;
    /** type=intent：意图种类（INTENT_* 常量） */
    private final String intentKind;

    private Mention(Builder b) {
        this.text = b.text;
        this.type = b.type;
        this.begin = b.begin;
        this.end = b.end;
        this.score = b.score;
        this.via = b.via;
        this.instanceCode = b.instanceCode;
        this.classCode = b.classCode;
        this.candidateInstanceCodes = b.candidateInstanceCodes;
        this.timeDays = b.timeDays;
        this.compareOperator = b.compareOperator;
        this.numericValue = b.numericValue;
        this.intentKind = b.intentKind;
    }

    public String getText() { return text; }
    public String getType() { return type; }
    public Integer getBegin() { return begin; }
    public Integer getEnd() { return end; }
    public Double getScore() { return score; }
    public String getVia() { return via; }
    public String getInstanceCode() { return instanceCode; }
    public String getClassCode() { return classCode; }
    public List<String> getCandidateInstanceCodes() { return candidateInstanceCodes; }
    public Integer getTimeDays() { return timeDays; }
    public String getCompareOperator() { return compareOperator; }
    public Double getNumericValue() { return numericValue; }
    public String getIntentKind() { return intentKind; }

    public boolean isEntity() { return TYPE_ENTITY.equals(type); }

    @Override
    public String toString() {
        return "Mention{text='" + text + "', type='" + type + "', begin=" + begin + ", end=" + end
                + ", score=" + score + ", instanceCode='" + instanceCode + "', classCode='" + classCode + "'}";
    }

    /** 构造器：字段较多，用 Builder 保持可读 */
    public static class Builder {
        private final String text;
        private final String type;
        private final Integer begin;
        private final Integer end;
        private Double score;
        private String via;
        private String instanceCode;
        private String classCode;
        private List<String> candidateInstanceCodes;
        private Integer timeDays;
        private String compareOperator;
        private Double numericValue;
        private String intentKind;

        public Builder(String text, String type, Integer begin, Integer end) {
            this.text = text;
            this.type = type;
            this.begin = begin;
            this.end = end;
        }

        public Builder score(Double score) { this.score = score; return this; }
        public Builder via(String via) { this.via = via; return this; }
        public Builder instanceCode(String instanceCode) { this.instanceCode = instanceCode; return this; }
        public Builder classCode(String classCode) { this.classCode = classCode; return this; }
        public Builder candidates(List<String> codes) { this.candidateInstanceCodes = codes; return this; }
        public Builder timeDays(Integer timeDays) { this.timeDays = timeDays; return this; }
        public Builder compareOperator(String op) { this.compareOperator = op; return this; }
        public Builder numericValue(Double numericValue) { this.numericValue = numericValue; return this; }
        public Builder intentKind(String intentKind) { this.intentKind = intentKind; return this; }

        public Mention build() { return new Mention(this); }
    }
}
