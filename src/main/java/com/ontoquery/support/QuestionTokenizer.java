package com.ontoquery.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自然语言问题轻量分词与槽位抽取（传统侧 Mock 生成器与风险检测共用）。
 * 与本体侧 DictionaryNer 无依赖关系：本类刻意保持"无领域知识"的朴素实现，
 * 用于体现传统管线缺少词典的缺陷。
 *
 * @author 月夜烛峰
 */
public final class QuestionTokenizer {

    /** CJK 连续段或拉丁字母数字段（含 % 小数点，如 HbA1c%） */
    private static final Pattern TOKEN = Pattern.compile("[\\u4e00-\\u9fff]+|[A-Za-z0-9][A-Za-z0-9%.]*");

    private static final Pattern DISEASE_PHRASE = Pattern.compile("(?:诊断为|患有|确诊(?:为)?|罹患)([\\u4e00-\\u9fffA-Za-z0-9]+?)(?=且|并|，|。|的|中|，|\\s|$)");

    private static final Pattern DRUG_PHRASE = Pattern.compile("(?:使用|服用|应用|给予)([\\u4e00-\\u9fffA-Za-z0-9]+?)(?=治疗|治疗的|进行|后|且|并|，|。|\\s|$)");

    private static final Pattern LAB_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9%]{2,}|糖化血红蛋白|空腹血糖|餐后血糖|血常规|尿微量白蛋白|血脂");

    private static final Pattern COMPARE_PHRASE = Pattern.compile("(大于|超过|高于|小于|低于|不低于|高于等于)(\\d+(?:\\.\\d+)?)");

    private static final Map<String, Integer> TIME_WINDOWS = new LinkedHashMap<>(8);

    static {
        TIME_WINDOWS.put("近一周", 7);
        TIME_WINDOWS.put("近一个月", 30);
        TIME_WINDOWS.put("近三个月", 90);
        TIME_WINDOWS.put("近半年", 180);
        TIME_WINDOWS.put("近一年", 365);
        TIME_WINDOWS.put("最近一周", 7);
        TIME_WINDOWS.put("最近一个月", 30);
        TIME_WINDOWS.put("最近一年", 365);
    }

    private QuestionTokenizer() {
    }

    /** 分词：CJK 连续段与拉丁字母数字段，过滤单字符（保留 2 字及以上） */
    public static List<String> tokens(String question) {
        List<String> list = new ArrayList<>();
        if (question == null) {
            return list;
        }
        Matcher m = TOKEN.matcher(question);
        while (m.find()) {
            String t = m.group();
            if (t.length() >= 2) {
                list.add(t);
            }
        }
        return list;
    }

    /** 抄用户原词的疾病短语（"诊断为X且" -> X）；无结构提示时回退为含"病/炎/症"的最长词 */
    public static String extractDiseasePhrase(String question) {
        Matcher m = DISEASE_PHRASE.matcher(question);
        if (m.find()) {
            return m.group(1);
        }
        String best = null;
        for (String t : tokens(question)) {
            if (t.length() >= 2 && (t.contains("病") || t.contains("炎") || t.contains("症"))) {
                if (best == null || t.length() > best.length()) {
                    best = t;
                }
            }
        }
        return best;
    }

    /** 抄用户原词的药品短语（"使用X治疗" -> X）；回退为含"药/苷/平/汀"的最长词 */
    public static String extractDrugPhrase(String question) {
        Matcher m = DRUG_PHRASE.matcher(question);
        if (m.find()) {
            return m.group(1);
        }
        String best = null;
        for (String t : tokens(question)) {
            if (t.length() >= 2 && (t.contains("药") || t.contains("苷") || t.contains("汀"))) {
                if (best == null || t.length() > best.length()) {
                    best = t;
                }
            }
        }
        return best;
    }

    /** 检验项目词（拉丁 token 如 HbA1c，或中文检验名） */
    public static String extractLabPhrase(String question) {
        if (question == null) {
            return null;
        }
        Matcher m = LAB_TOKEN.matcher(question);
        if (m.find()) {
            return m.group();
        }
        return null;
    }

    /** 比较槽位："大于7" -> {op:">", value:"7"}；无则 null */
    public static CompareSlot extractCompare(String question) {
        if (question == null) {
            return null;
        }
        Matcher m = COMPARE_PHRASE.matcher(question);
        if (!m.find()) {
            return null;
        }
        String word = m.group(1);
        String op;
        switch (word) {
            case "大于":
            case "超过":
            case "高于":
                op = ">";
                break;
            case "低于":
            case "小于":
                op = "<";
                break;
            default:
                op = ">=";
        }
        return new CompareSlot(op, m.group(2));
    }

    /** 时间窗：label 与天数；无时间词返回 null */
    public static TimeWindow extractTimeWindow(String question) {
        if (question == null) {
            return null;
        }
        for (Map.Entry<String, Integer> e : TIME_WINDOWS.entrySet()) {
            if (question.contains(e.getKey())) {
                return new TimeWindow(e.getKey(), e.getValue());
            }
        }
        return null;
    }

    /** 比较槽位 */
    public static final class CompareSlot {
        private final String op;
        private final String value;

        public CompareSlot(String op, String value) {
            this.op = op;
            this.value = value;
        }

        public String getOp() {
            return op;
        }

        public String getValue() {
            return value;
        }

        @Override
        public String toString() {
            return "CompareSlot{op='" + op + "', value='" + value + "'}";
        }
    }

    /** 时间窗槽位 */
    public static final class TimeWindow {
        private final String label;
        private final Integer days;

        public TimeWindow(String label, Integer days) {
            this.label = label;
            this.days = days;
        }

        public String getLabel() {
            return label;
        }

        public Integer getDays() {
            return days;
        }

        @Override
        public String toString() {
            return "TimeWindow{label='" + label + "', days=" + days + "}";
        }
    }
}
