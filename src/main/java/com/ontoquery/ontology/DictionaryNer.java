package com.ontoquery.ontology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 词典识别（NER）：Trie 最长匹配。
 * 词表 = 实例名 + 实例 code + 同义词 + 类名 + 内置词（时间窗/比较词/数字含中文数字/意图词）。
 * 打分：精确实例名或实例编码 1.0 / 同义词 0.92 / 类名 0.85 / 内置词 1.0。
 */
public class DictionaryNer {

    /** 时间窗词 -> 天数 */
    private static final Map<String, Integer> TIME_WORDS = new LinkedHashMap<>();

    /** 比较词 -> SQL 运算符 */
    private static final Map<String, String> COMPARE_WORDS = new LinkedHashMap<>();

    /** 意图词 -> 意图种类 */
    private static final Map<String, String> INTENT_WORDS = new LinkedHashMap<>();

    /** 中文数字字符集（用于连续段扫描） */
    private static final String CN_DIGIT_CHARS = "零〇一二两三四五六七八九十百千万";

    static {
        TIME_WORDS.put("近一周", 7);
        TIME_WORDS.put("近一个月", 30);
        TIME_WORDS.put("近三个月", 90);
        TIME_WORDS.put("近半年", 180);
        TIME_WORDS.put("近一年", 365);

        COMPARE_WORDS.put("大于等于", ">=");
        COMPARE_WORDS.put("高于等于", ">=");
        COMPARE_WORDS.put("不低于", ">=");
        COMPARE_WORDS.put("大于", ">");
        COMPARE_WORDS.put("超过", ">");
        COMPARE_WORDS.put("高于", ">");
        COMPARE_WORDS.put("小于", "<");
        COMPARE_WORDS.put("低于", "<");

        INTENT_WORDS.put("多少人", Mention.INTENT_COUNT);
        INTENT_WORDS.put("几位", Mention.INTENT_COUNT);
        INTENT_WORDS.put("多少", Mention.INTENT_COUNT);
        INTENT_WORDS.put("数量", Mention.INTENT_COUNT);
        INTENT_WORDS.put("排名", Mention.INTENT_TOP_N);
        INTENT_WORDS.put("最高", Mention.INTENT_TOP_N);
        INTENT_WORDS.put("最多", Mention.INTENT_TOP_N);
        INTENT_WORDS.put("Top", Mention.INTENT_TOP_N);
        INTENT_WORDS.put("top", Mention.INTENT_TOP_N);
        INTENT_WORDS.put("TOP", Mention.INTENT_TOP_N);
        INTENT_WORDS.put("哪些", Mention.INTENT_LIST);
        INTENT_WORDS.put("列出", Mention.INTENT_LIST);
        INTENT_WORDS.put("平均", Mention.INTENT_AVG);
        INTENT_WORDS.put("均值", Mention.INTENT_AVG);
        INTENT_WORDS.put("各", Mention.INTENT_GROUP);
        INTENT_WORDS.put("分别", Mention.INTENT_GROUP);
        INTENT_WORDS.put("分组", Mention.INTENT_GROUP);
    }

    private final TrieNode root = new TrieNode();
    private final OntologyModel model;
    private int maxTermLength = 1;

    public DictionaryNer(OntologyModel model) {
        this.model = model;
        buildDictionary();
    }

    private void buildDictionary() {
        for (OntologyModel.OntInstance instance : model.allInstances()) {
            insertEntityTerm(instance.getNameCn(), instance.getCode(), instance.getClassCode(),
                    1.0D, Mention.VIA_INSTANCE_NAME);
            insertEntityTerm(instance.getCode(), instance.getCode(), instance.getClassCode(),
                    1.0D, Mention.VIA_INSTANCE_CODE);
        }
        for (OntologyModel.SynonymEntry entry : model.allSynonymEntries()) {
            if (entry.getInstanceCode() != null) {
                OntologyModel.OntInstance target = model.instanceByCode(entry.getInstanceCode());
                insertEntityTerm(entry.getTerm(), entry.getInstanceCode(),
                        target == null ? null : target.getClassCode(), 0.92D, Mention.VIA_SYNONYM);
            } else if (entry.getClassCode() != null) {
                insertClassTerm(entry.getTerm(), entry.getClassCode(), 0.92D, Mention.VIA_SYNONYM);
            }
        }
        for (OntologyModel.OntClass clazz : model.allClasses()) {
            insertClassTerm(clazz.getNameCn(), clazz.getCode(), 0.85D, Mention.VIA_CLASS_NAME);
        }
        for (Map.Entry<String, Integer> e : TIME_WORDS.entrySet()) {
            insertBuiltin(e.getKey(), Mention.TYPE_TIME, String.valueOf(e.getValue()));
        }
        for (Map.Entry<String, String> e : COMPARE_WORDS.entrySet()) {
            insertBuiltin(e.getKey(), Mention.TYPE_COMPARE, e.getValue());
        }
        for (Map.Entry<String, String> e : INTENT_WORDS.entrySet()) {
            insertBuiltin(e.getKey(), Mention.TYPE_INTENT, e.getValue());
        }
    }

    private void insertEntityTerm(String term, String instanceCode, String classCode, double score, String via) {
        List<String> candidates = new ArrayList<>(1);
        candidates.add(instanceCode);
        insert(term, new DictEntry(Mention.TYPE_ENTITY, via, score, instanceCode, classCode, candidates,
                null, null, null));
    }

    private void insertClassTerm(String term, String classCode, double score, String via) {
        insert(term, new DictEntry(Mention.TYPE_ENTITY, via, score, null, classCode, null,
                null, null, null));
    }

    private void insertBuiltin(String term, String type, String value) {
        Integer timeDays = Mention.TYPE_TIME.equals(type) ? Integer.valueOf(value) : null;
        String operator = Mention.TYPE_COMPARE.equals(type) ? value : null;
        String intent = Mention.TYPE_INTENT.equals(type) ? value : null;
        insert(term, new DictEntry(type, "builtin", 1.0D, null, null, null,
                timeDays, operator, intent));
    }

    private void insert(String term, DictEntry entry) {
        TrieNode node = root;
        for (int i = 0; i < term.length(); i++) {
            node = node.children.computeIfAbsent(term.charAt(i), c -> new TrieNode());
        }
        node.entries.add(entry);
        if (term.length() > maxTermLength) {
            maxTermLength = term.length();
        }
    }

    /** 识别全部提及，按出现位置升序 */
    public List<Mention> recognize(String question) {
        List<Mention> mentions = new ArrayList<>();
        if (question == null || question.isEmpty()) {
            return mentions;
        }
        String lower = question.toLowerCase();
        int i = 0;
        while (i < question.length()) {
            List<DictEntry> hit = longestMatch(question, lower, i);
            if (hit != null) {
                mentions.addAll(toMentions(hit, question, i));
                i = i + hit.get(0).termLength;
                continue;
            }
            int numberEnd = numberEnd(question, i);
            if (numberEnd > i) {
                String token = question.substring(i, numberEnd);
                Double value = parseNumber(token);
                if (value != null) {
                    mentions.add(new Mention.Builder(token, Mention.TYPE_NUMBER, i, numberEnd)
                            .score(1.0D).via("builtin").numericValue(value).build());
                }
                i = numberEnd;
                continue;
            }
            i = i + 1;
        }
        return mentions;
    }

    /** 从 from 起的最长词典命中（先按原文走 Trie，ASCII 场景再按小写重走一次） */
    private List<DictEntry> longestMatch(String text, String lowerText, int from) {
        for (int len = Math.min(maxTermLength, text.length() - from); len >= 1; len--) {
            List<DictEntry> entries = walk(text, from, len);
            if (entries == null) {
                entries = walk(lowerText, from, len);
            }
            if (entries != null) {
                for (DictEntry e : entries) {
                    e.termLength = len;
                }
                return entries;
            }
        }
        return null;
    }

    private List<DictEntry> walk(String text, int from, int len) {
        TrieNode node = root;
        for (int i = 0; i < len; i++) {
            node = node.children.get(text.charAt(from + i));
            if (node == null) {
                return null;
            }
        }
        return node.entries.isEmpty() ? null : node.entries;
    }

    private List<Mention> toMentions(List<DictEntry> entries, String text, int begin) {
        List<Mention> mentions = new ArrayList<>();
        DictEntry entity = null;
        for (DictEntry entry : entries) {
            if (Mention.TYPE_ENTITY.equals(entry.type)) {
                entity = mergeEntity(entity, entry);
            } else {
                mentions.add(new Mention.Builder(text.substring(begin, begin + entry.termLength), entry.type,
                        begin, begin + entry.termLength)
                        .score(entry.score).via(entry.via)
                        .timeDays(entry.timeDays).compareOperator(entry.compareOperator)
                        .intentKind(entry.intentKind).build());
            }
        }
        if (entity != null) {
            mentions.add(new Mention.Builder(text.substring(begin, begin + entity.termLength), Mention.TYPE_ENTITY,
                    begin, begin + entity.termLength)
                    .score(entity.score).via(entity.via)
                    .instanceCode(entity.instanceCode).classCode(entity.classCode)
                    .candidates(entity.candidateInstanceCodes).build());
        }
        return mentions;
    }

    /** 同词多条目命中实例时合并为单一实体提及并累计候选（跨类同名即歧义来源） */
    private DictEntry mergeEntity(DictEntry base, DictEntry extra) {
        if (base == null) {
            return extra.copy();
        }
        if (extra.instanceCode != null && base.candidateInstanceCodes != null
                && !base.candidateInstanceCodes.contains(extra.instanceCode)) {
            base.candidateInstanceCodes.add(extra.instanceCode);
        }
        if (base.instanceCode == null && extra.instanceCode != null) {
            base.instanceCode = extra.instanceCode;
            base.classCode = extra.classCode;
        }
        if (extra.score > base.score) {
            base.score = extra.score;
            base.via = extra.via;
        }
        if (base.classCode == null && extra.classCode != null) {
            base.classCode = extra.classCode;
        }
        return base;
    }

    /** 从 from 起的数字串末尾（阿拉伯数字含小数，或连续中文数字） */
    private int numberEnd(String text, int from) {
        char first = text.charAt(from);
        if (Character.isDigit(first)) {
            int end = from + 1;
            while (end < text.length() && Character.isDigit(text.charAt(end))) {
                end = end + 1;
            }
            if (end < text.length() && text.charAt(end) == '.' && end + 1 < text.length()
                    && Character.isDigit(text.charAt(end + 1))) {
                end = end + 1;
                while (end < text.length() && Character.isDigit(text.charAt(end))) {
                    end = end + 1;
                }
            }
            return end;
        }
        if (CN_DIGIT_CHARS.indexOf(first) >= 0) {
            int end = from + 1;
            while (end < text.length() && CN_DIGIT_CHARS.indexOf(text.charAt(end)) >= 0) {
                end = end + 1;
            }
            return end;
        }
        return from;
    }

    private Double parseNumber(String token) {
        char first = token.charAt(0);
        if (Character.isDigit(first)) {
            try {
                return Double.valueOf(Double.parseDouble(token));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        long cn = parseChineseNumber(token);
        return cn < 0L ? null : Double.valueOf(cn);
    }

    /** 中文数字解析（支持到万位），无法解析返回 -1 */
    private long parseChineseNumber(String token) {
        long result = 0L;
        long section = 0L;
        long number = 0L;
        boolean any = false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            int digit = cnDigit(c);
            if (digit >= 0) {
                number = digit;
                any = true;
            } else if (c == '万') {
                section = (section + number) * 10000L;
                result = result + section;
                section = 0L;
                number = 0L;
                any = true;
            } else {
                long unit = cnUnit(c);
                if (unit <= 0L) {
                    return -1L;
                }
                section = section + (number == 0L ? 1L : number) * unit;
                number = 0L;
                any = true;
            }
        }
        if (!any) {
            return -1L;
        }
        return result + section + number;
    }

    private int cnDigit(char c) {
        switch (c) {
            case '零': return 0;
            case '〇': return 0;
            case '一': return 1;
            case '二': return 2;
            case '两': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            case '七': return 7;
            case '八': return 8;
            case '九': return 9;
            default: return -1;
        }
    }

    private long cnUnit(char c) {
        switch (c) {
            case '十': return 10L;
            case '百': return 100L;
            case '千': return 1000L;
            default: return -1L;
        }
    }

    /** Trie 节点：children 为后继字符，entries 非空表示有词在此终止 */
    private static class TrieNode {
        private final Map<Character, TrieNode> children = new HashMap<>();
        private final List<DictEntry> entries = new ArrayList<>(1);
    }

    /** 词表条目（score/via/候选在合并同词条目时可变） */
    private static class DictEntry {
        private final String type;
        private String via;
        private double score;
        private String instanceCode;
        private String classCode;
        private List<String> candidateInstanceCodes;
        private final Integer timeDays;
        private final String compareOperator;
        private final String intentKind;
        private int termLength;

        DictEntry(String type, String via, double score, String instanceCode, String classCode,
                List<String> candidateInstanceCodes, Integer timeDays, String compareOperator,
                String intentKind) {
            this.type = type;
            this.via = via;
            this.score = score;
            this.instanceCode = instanceCode;
            this.classCode = classCode;
            this.candidateInstanceCodes = candidateInstanceCodes;
            this.timeDays = timeDays;
            this.compareOperator = compareOperator;
            this.intentKind = intentKind;
        }

        DictEntry copy() {
            List<String> candidates = candidateInstanceCodes == null ? null : new ArrayList<>(candidateInstanceCodes);
            DictEntry copied = new DictEntry(type, via, score, instanceCode, classCode, candidates, timeDays,
                    compareOperator, intentKind);
            copied.termLength = termLength;
            return copied;
        }
    }
}
