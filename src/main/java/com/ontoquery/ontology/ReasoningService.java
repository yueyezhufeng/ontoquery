package com.ontoquery.ontology;

import com.ontoquery.ontology.trace.TraceContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 本体推理：四类规则逐项写入 trace。
 * (a) 同义归一（同义词表）；(b) 子类闭包（SUBCLASS_CLOSURE）；
 * (c) 时间就近挂靠（TIME_ATTACH_PROXIMITY）；(d) 值域校验（VALUE_RANGE）。
 * 另含歧义检测：同词命中多类实例且无法消歧记 warn 并计入置信度扣分。
 */
public class ReasoningService {

    private static final String RULE_SYNONYM = "同义词表";
    private static final String RULE_TIME_ATTACH = "TIME_ATTACH_PROXIMITY";
    private static final String RULE_VALUE_RANGE = "VALUE_RANGE";
    private static final String RULE_SUBCLASS = "SUBCLASS_CLOSURE";

    /** 可信值域系数：下限=正常下限*0.75，上限=正常上限*10/3（如 4~6 -> 3~20） */
    private static final String CREDIBLE_LOW_FACTOR = "0.75";
    private static final String CREDIBLE_HIGH_NUMERATOR = "10";
    private static final String CREDIBLE_HIGH_DENOMINATOR = "3";

    /** 默认 TopN（问题未给 N 时） */
    private static final long DEFAULT_TOP_N = 10L;

    /** 事件类就近匹配的补充词面（关系名/类名之外的人工别名） */
    private static final Map<String, List<String>> EVENT_MARKERS = Map.of(
            "CLS_DIAGNOSIS", List.of("诊断", "确诊"),
            "CLS_LAB_RESULT", List.of("检验", "化验", "检查"),
            "CLS_MEDICATION", List.of("用药", "开药", "开了", "处方", "服用", "药物", "使用"),
            "CLS_VISIT", List.of("就诊", "看病", "门诊", "住院"));

    /** 无 date 属性映射时的事件表日期列兜底（schema 固定列） */
    private static final Map<String, String> DATE_COLUMN_FALLBACK = Map.of(
            "CLS_DIAGNOSIS", "diagnosis_date",
            "CLS_LAB_RESULT", "test_date",
            "CLS_MEDICATION", "prescribe_date",
            "CLS_VISIT", "visit_date");

    /** 列举意图展示列兜底（事件类 -> 编码列 + 名称列，schema 固定列） */
    private static final Map<String, List<String>> LIST_DISPLAY_COLUMNS = Map.of(
            "CLS_DIAGNOSIS", List.of("disease_code", "disease_name"),
            "CLS_LAB_RESULT", List.of("test_code", "test_name"),
            "CLS_MEDICATION", List.of("drug_code", "drug_name"));

    /** 分组/TopN 目标属性的词面别名（属性名之外的人工别名，同 EVENT_MARKERS 惯例） */
    private static final Map<String, List<String>> ATTRIBUTE_MARKERS = Map.of(
            "ATTR_GENDER", List.of("性别", "男性", "女性"),
            "ATTR_INSURANCE_TYPE", List.of("医保类型", "医保", "保险类型"),
            "ATTR_REGION", List.of("地区", "区域", "常住地"),
            "ATTR_DEPT_CODE", List.of("科室"),
            "ATTR_DRUG_CODE", List.of("药品", "药种"),
            "ATTR_FREQUENCY", List.of("频次"),
            "ATTR_DISEASE_NAME", List.of("名称", "写法"),
            "ATTR_DISEASE_CODE", List.of("编码", "诊断"),
            "ATTR_IS_ABNORMAL", List.of("异常"));

    /** 否定提示词：提及前出现即视为排除语义（NOT 谓词） */
    private static final List<String> NEGATION_CUES = List.of("不含", "排除", "除外", "剔除", "去掉", "而非");

    /** 年龄分段桶别名（值域分段知识口径：青年 <45 / 中年 45-59 / 老年 >=60） */
    private static final String AGE_BUCKET_ALIAS = "bucket";
    private static final String AGE_BUCKET_EXPRESSION = "CASE WHEN TIMESTAMPDIFF(YEAR, {a}.birth_date, CURDATE()) < 45 "
            + "THEN '青年(<45)' WHEN TIMESTAMPDIFF(YEAR, {a}.birth_date, CURDATE()) < 60 THEN '中年(45-59)' "
            + "ELSE '老年(>=60)' END";

    /** 事件表枚举列的词面值过滤：问题词 -> 列 = 值（生成为附加谓词） */
    private static final List<ValueFilter> VALUE_FILTERS = List.of(
            new ValueFilter("CLS_VISIT", "visit_type", "门诊", "门诊"),
            new ValueFilter("CLS_VISIT", "visit_type", "住院", "住院"),
            new ValueFilter("CLS_VISIT", "visit_type", "急诊", "急诊"),
            new ValueFilter("CLS_MEDICATION", "frequency", "每日一次", "qd"),
            new ValueFilter("CLS_MEDICATION", "frequency", "每日两次", "bid"),
            new ValueFilter("CLS_MEDICATION", "frequency", "每日三次", "tid"),
            new ValueFilter("CLS_MEDICATION", "frequency", "qd", "qd"),
            new ValueFilter("CLS_MEDICATION", "frequency", "bid", "bid"),
            new ValueFilter("CLS_MEDICATION", "frequency", "tid", "tid"));

    private final OntologyModel model;

    public ReasoningService(OntologyModel model) {
        this.model = model;
    }

    /**
     * 就地丰富计划：同义归一、子类闭包、时间挂靠、值域校验、意图识别、
     * 分组/记录数/TopN 目标解析、枚举值过滤、歧义检测。
     */
    public void apply(QueryPlan plan, TraceContext.TraceStep step) {
        buildInstanceConstraints(plan, step);
        attachTime(plan, step);
        attachValue(plan, step);
        resolveIntent(plan, step);
        resolveIntentTargets(plan, step);
        detectAmbiguity(plan, step);
    }

    // ------------------------------------------------------------ 实体约束（含 a 同义归一 / b 子类闭包）

    private void buildInstanceConstraints(QueryPlan plan, TraceContext.TraceStep step) {
        for (Mention mention : plan.getMentions()) {
            if (!mention.isEntity()) {
                continue;
            }
            if (mention.getInstanceCode() != null) {
                buildInstanceConstraint(plan, step, mention);
            } else if (mention.getClassCode() != null) {
                buildClassConstraint(plan, step, mention);
            }
        }
    }

    private void buildInstanceConstraint(QueryPlan plan, TraceContext.TraceStep step, Mention mention) {
        OntologyModel.OntInstance instance = model.instanceByCode(mention.getInstanceCode());
        if (instance == null) {
            return;
        }
        if (Mention.VIA_SYNONYM.equals(mention.getVia())) {
            step.kv("同义归一", mention.getText(), instance.getCode() + " " + instance.getNameCn(), RULE_SYNONYM);
        }
        OntologyModel.OntMapping mapping = model.instanceMapping(instance.getCode());
        if (mapping == null || mapping.getTableName() == null) {
            plan.addWarning("实例 " + instance.getCode() + " 缺少映射，无法生成谓词");
            step.text("实例 " + instance.getNameCn() + "（" + instance.getCode() + "）缺少物理映射，已跳过", "warn");
            return;
        }
        OntologyModel.OntClass host = model.classOfTable(mapping.getTableName());
        if (host == null) {
            plan.addWarning("表 " + mapping.getTableName() + " 未映射到任何本体类");
            step.text("实例 " + instance.getNameCn() + " 的宿主表 " + mapping.getTableName() + " 无类映射", "warn");
            return;
        }
        QueryPlan.Constraint constraint = mergeableInstanceConstraint(plan, host.getCode(), instance.getCode(),
                mention);
        if (constraint == null) {
            constraint = new QueryPlan.Constraint(mention.getText(), mention.getBegin());
            constraint.setHostClassCode(host.getCode());
            constraint.setNegative(negatedBefore(plan.getQuestion(), mention.getBegin()));
            constraint.setLastMentionEnd(mention.getEnd());
            plan.getConstraints().add(constraint);
            if (constraint.isNegative()) {
                step.kv("否定识别", "不含/排除 " + mention.getText(), instance.getCode() + " 取 NOT 谓词",
                        "否定词典");
            }
        } else {
            constraint.setLastMentionEnd(mention.getEnd());
        }
        if (!constraint.getInstanceCodes().contains(instance.getCode())) {
            constraint.getInstanceCodes().add(instance.getCode());
        }
    }

    /** 提及前 3 字符窗口内是否出现否定提示词 */
    private boolean negatedBefore(String question, Integer begin) {
        if (question == null || begin == null) {
            return false;
        }
        int from = Math.max(0, begin.intValue() - 3);
        String window = question.substring(from, begin.intValue());
        for (String cue : NEGATION_CUES) {
            if (window.contains(cue)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 实例提及的可并入约束：同宿主且（既有约束已涵盖该实例（通常是类闭包先行），或两次提及之间由「或」连接）。
     * 不满足时返回 null，形成独立约束（生成独立 EXISTS，语义为 AND，如「同时诊断为 A 和 B」）。
     */
    private QueryPlan.Constraint mergeableInstanceConstraint(QueryPlan plan, String hostClassCode,
            String instanceCode, Mention mention) {
        QueryPlan.Constraint candidate = constraintOfHost(plan, hostClassCode);
        if (candidate == null) {
            return null;
        }
        if (candidate.getInstanceCodes().contains(instanceCode)) {
            return candidate;
        }
        if (orConnected(plan.getQuestion(), candidate.getLastMentionEnd(), mention.getBegin())) {
            return candidate;
        }
        return null;
    }

    /** 两次提及之间的原文是否含「或」（合并为 OR 谓词的判定依据） */
    private boolean orConnected(String question, Integer fromEnd, Integer toBegin) {
        if (question == null || fromEnd == null || toBegin == null) {
            return false;
        }
        if (fromEnd.intValue() >= toBegin.intValue()) {
            return false;
        }
        return question.substring(fromEnd.intValue(), toBegin.intValue()).contains("或");
    }

    private void buildClassConstraint(QueryPlan plan, TraceContext.TraceStep step, Mention mention) {
        OntologyModel.OntClass clazz = model.classByCode(mention.getClassCode());
        if (clazz == null) {
            return;
        }
        if (RelationLinker.ANCHOR_CLASS.equals(clazz.getCode())) {
            // 锚类提及是查询主语（如「的患者中」），不构成过滤条件
            return;
        }
        List<OntologyModel.OntInstance> closure = model.instancesOfClosure(clazz.getCode());
        if (closure.isEmpty()) {
            if (isMeasureHostMention(plan, mention)) {
                // 类无实例但可作数值度量宿主（如 检验结果值大于7），值条件由值域校验阶段挂靠
                step.text("类 " + clazz.getNameCn() + " 闭包内无实例，按数值度量宿主处理", "info");
                return;
            }
            plan.addWarning("类 " + clazz.getNameCn() + " 无实例，问题超纲");
            step.text("类 " + clazz.getNameCn() + " 闭包内无实例，无法生成过滤条件", "warn");
            return;
        }
        step.kv("子类闭包", clazz.getNameCn(), closure.size() + " 个实例", RULE_SUBCLASS);
        List<List<String>> rows = new ArrayList<>(closure.size());
        for (OntologyModel.OntInstance instance : closure) {
            OntologyModel.OntClass owner = model.classByCode(instance.getClassCode());
            rows.add(List.of(instance.getCode(), instance.getNameCn(),
                    owner == null ? instance.getClassCode() : owner.getNameCn()));
        }
        step.kvTable(List.of("编码", "名称", "所属类"), rows);
        // 归集闭包内可映射实例（同宿主类），并入或新建约束（与实例提及同一合并语义）
        String hostClass = null;
        List<String> codes = new ArrayList<>();
        for (OntologyModel.OntInstance instance : closure) {
            OntologyModel.OntMapping mapping = model.instanceMapping(instance.getCode());
            if (mapping == null || mapping.getTableName() == null) {
                continue;
            }
            OntologyModel.OntClass host = model.classOfTable(mapping.getTableName());
            if (host == null) {
                continue;
            }
            if (hostClass == null) {
                hostClass = host.getCode();
            }
            if (hostClass.equals(host.getCode()) && !codes.contains(instance.getCode())) {
                codes.add(instance.getCode());
            }
        }
        if (hostClass == null || codes.isEmpty()) {
            plan.addWarning("类 " + clazz.getNameCn() + " 的实例均缺少物理映射");
            return;
        }
        QueryPlan.Constraint constraint = mergeableClassConstraint(plan, hostClass, codes, mention);
        if (constraint == null) {
            constraint = new QueryPlan.Constraint(mention.getText(), mention.getBegin());
            constraint.setHostClassCode(hostClass);
            constraint.setNegative(negatedBefore(plan.getQuestion(), mention.getBegin()));
            constraint.setFromClassMention(true);
            plan.getConstraints().add(constraint);
            if (constraint.isNegative()) {
                step.kv("否定识别", "不含/排除 " + mention.getText(), clazz.getNameCn() + " 闭包 "
                        + codes.size() + " 例取 NOT 谓词", "否定词典");
            }
        }
        constraint.setLastMentionEnd(mention.getEnd());
        for (String code : codes) {
            if (!constraint.getInstanceCodes().contains(code)) {
                constraint.getInstanceCodes().add(code);
            }
        }
    }

    /**
     * 类闭包的可并入约束：同宿主且（既有约束已涵盖全部闭包实例（如「降糖药」已含「口服降糖药」子集），
     * 或两次提及之间由「或」连接）。不满足时返回 null 形成独立约束。
     */
    private QueryPlan.Constraint mergeableClassConstraint(QueryPlan plan, String hostClassCode,
            List<String> codes, Mention mention) {
        QueryPlan.Constraint candidate = constraintOfHost(plan, hostClassCode);
        if (candidate == null) {
            return null;
        }
        if (!candidate.getInstanceCodes().isEmpty() && candidate.getInstanceCodes().containsAll(codes)) {
            return candidate;
        }
        if (orConnected(plan.getQuestion(), candidate.getLastMentionEnd(), mention.getBegin())) {
            return candidate;
        }
        return null;
    }

    // ------------------------------------------------------------ c 时间就近挂靠

    private void attachTime(QueryPlan plan, TraceContext.TraceStep step) {
        for (Mention mention : plan.getMentions()) {
            if (!Mention.TYPE_TIME.equals(mention.getType())) {
                continue;
            }
            List<EventCandidate> candidates = eventCandidates(plan.getQuestion(), mention);
            if (candidates.isEmpty()) {
                plan.addWarning("时间词 " + mention.getText() + " 未找到就近事件词，未挂靠");
                step.kv("时间挂靠", mention.getText() + "（" + mention.getTimeDays() + " 天）", "未找到事件词，未挂靠",
                        RULE_TIME_ATTACH);
                continue;
            }
            EventCandidate chosen = candidates.get(0);
            String dateColumn = resolveDateColumn(chosen.eventClassCode);
            if (dateColumn == null) {
                plan.addWarning("事件类 " + chosen.eventClassCode + " 无法确定日期列，时间未挂靠");
                continue;
            }
            OntologyModel.OntClass host = model.classByCode(chosen.eventClassCode);
            String hostTable = tableNameOf(chosen.eventClassCode);
            QueryPlan.Constraint constraint = constraintOfHost(plan, chosen.eventClassCode);
            if (constraint == null) {
                constraint = new QueryPlan.Constraint(mention.getText(), mention.getBegin());
                constraint.setHostClassCode(chosen.eventClassCode);
                plan.getConstraints().add(constraint);
            }
            constraint.setTimeDays(mention.getTimeDays());
            constraint.setDateColumn(dateColumn);
            constraint.setTimeText(mention.getText());
            String hostName = host == null ? chosen.eventClassCode : host.getNameCn();
            step.kv("时间挂靠", mention.getText() + "（" + mention.getTimeDays() + " 天）",
                    hostTable + "." + dateColumn + "（" + hostName + "）", RULE_TIME_ATTACH);
            for (EventCandidate rejected : candidates.subList(1, candidates.size())) {
                step.text("未挂到 " + tableNameOf(rejected.eventClassCode) + "." + dateColumnOf(rejected.eventClassCode)
                        + "：事件词「" + rejected.marker + "」距离 " + rejected.distance
                        + "，远于「" + chosen.marker + "」距离 " + chosen.distance, "info");
            }
            for (String eventClassCode : reachableEventClasses()) {
                boolean found = false;
                for (EventCandidate candidate : candidates) {
                    if (candidate.eventClassCode.equals(eventClassCode)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    step.text("未挂到 " + tableNameOf(eventClassCode) + "." + dateColumnOf(eventClassCode)
                            + "：问题中未出现该事件词", "info");
                }
            }
        }
    }

    /** 事件词候选：锚点可达的事件类按（先距离、再左侧、类序）排序 */
    private List<EventCandidate> eventCandidates(String question, Mention time) {
        List<EventCandidate> candidates = new ArrayList<>();
        if (question == null) {
            return candidates;
        }
        for (String eventClassCode : reachableEventClasses()) {
            MarkerHit hit = nearestMarker(question, eventClassCode, time.getBegin().intValue(),
                    time.getEnd().intValue());
            if (hit == null) {
                continue;
            }
            candidates.add(new EventCandidate(eventClassCode, hit.marker, hit.distance, hit.leftSide));
        }
        candidates.sort((a, b) -> {
            int distance = Long.compare(a.distance, b.distance);
            if (distance != 0) {
                return distance;
            }
            int side = Integer.compare(a.sideRank(), b.sideRank());
            if (side != 0) {
                return side;
            }
            return a.eventClassCode.compareTo(b.eventClassCode);
        });
        return candidates;
    }

    /** 锚点经数据关系可达的事件类 */
    private List<String> reachableEventClasses() {
        List<String> eventClasses = new ArrayList<>();
        for (OntologyModel.OntRelation relation : model.relationsFrom(RelationLinker.ANCHOR_CLASS)) {
            if (model.relationMapping(relation.getCode()) == null) {
                continue;
            }
            if (!eventClasses.contains(relation.getRangeClassCode())) {
                eventClasses.add(relation.getRangeClassCode());
            }
        }
        return eventClasses;
    }

    private MarkerHit nearestMarker(String question, String eventClassCode, int timeBegin, int timeEnd) {
        List<String> markers = new ArrayList<>();
        OntologyModel.OntClass eventClass = model.classByCode(eventClassCode);
        if (eventClass != null) {
            markers.add(eventClass.getNameCn());
        }
        for (Map.Entry<String, List<String>> entry : EVENT_MARKERS.entrySet()) {
            if (entry.getKey().equals(eventClassCode)) {
                markers.addAll(entry.getValue());
            }
        }
        MarkerHit best = null;
        for (String marker : markers) {
            int idx = question.indexOf(marker);
            while (idx >= 0) {
                int markerEnd = idx + marker.length();
                long distance;
                boolean leftSide;
                if (markerEnd <= timeBegin) {
                    distance = timeBegin - markerEnd;
                    leftSide = true;
                } else if (idx >= timeEnd) {
                    distance = idx - timeEnd;
                    leftSide = false;
                } else {
                    distance = 0L;
                    leftSide = true;
                }
                if (best == null || distance < best.distance) {
                    best = new MarkerHit(marker, distance, leftSide);
                }
                idx = question.indexOf(marker, idx + 1);
            }
        }
        return best;
    }

    private String resolveDateColumn(String eventClassCode) {
        for (OntologyModel.OntAttribute attribute : model.attributesOf(eventClassCode)) {
            if (!"date".equals(attribute.getDataType())) {
                continue;
            }
            OntologyModel.OntMapping mapping = model.attributeMapping(attribute.getCode());
            if (mapping != null && mapping.getColumnName() != null) {
                return mapping.getColumnName();
            }
        }
        return DATE_COLUMN_FALLBACK.get(eventClassCode);
    }

    private String dateColumnOf(String eventClassCode) {
        String column = resolveDateColumn(eventClassCode);
        return column == null ? "?" : column;
    }

    // ------------------------------------------------------------ d 值域校验

    private void attachValue(QueryPlan plan, TraceContext.TraceStep step) {
        for (Mention compare : plan.getMentions()) {
            if (!Mention.TYPE_COMPARE.equals(compare.getType())) {
                continue;
            }
            Mention number = numberAfter(plan, compare);
            if (number == null) {
                plan.addWarning("比较词 " + compare.getText() + " 后未找到数字，条件忽略");
                step.kv("值域校验", compare.getText(), "缺少数值，忽略", RULE_VALUE_RANGE);
                continue;
            }
            MeasureTarget target = measureTarget(plan, compare, number.getNumericValue());
            if (target == null) {
                plan.addWarning("比较条件 " + compare.getText() + number.getNumericValue()
                        + " 未找到可度量的检验/属性实体");
                step.kv("值域校验", compare.getText() + " " + formatNumber(number.getNumericValue()),
                        "未找到度量实体，忽略", RULE_VALUE_RANGE);
                continue;
            }
            QueryPlan.Constraint constraint = constraintOfHost(plan, target.hostClassCode);
            if (constraint == null) {
                constraint = new QueryPlan.Constraint(target.instanceName, compare.getBegin());
                constraint.setHostClassCode(target.hostClassCode);
                plan.getConstraints().add(constraint);
            }
            constraint.setValueOperator(compare.getCompareOperator());
            constraint.setValueNumber(number.getNumericValue());
            constraint.setValueColumn(target.valueColumn);
            constraint.setValueAttributeCode(target.attributeCode);
            step.kv("值域校验", target.instanceName + " " + compare.getCompareOperator() + " "
                    + formatNumber(number.getNumericValue()), target.rangeSummary, RULE_VALUE_RANGE);
            if (!target.credible) {
                plan.addWarning("数值 " + formatNumber(number.getNumericValue()) + " 超出可信区间 "
                        + target.credibleRangeText + "，单位可疑");
                step.text("数值 " + formatNumber(number.getNumericValue()) + " 超出可信区间 " + target.credibleRangeText
                        + "（正常 " + target.normalRangeText + "），疑似单位错误，已按原值生成", "warn");
            }
        }
    }

    /** 比较词之后最近的数字提及（允许隔 2 个字符以内） */
    private Mention numberAfter(QueryPlan plan, Mention compare) {
        Mention best = null;
        for (Mention mention : plan.getMentions()) {
            if (!Mention.TYPE_NUMBER.equals(mention.getType())) {
                continue;
            }
            if (mention.getBegin() < compare.getEnd()) {
                continue;
            }
            if (mention.getBegin() - compare.getEnd() > 2) {
                continue;
            }
            if (best == null || mention.getBegin() < best.getBegin()) {
                best = mention;
            }
        }
        return best;
    }

    /** 度量目标：就近实体（实例或可度量事件类）-> 宿主事件类 -> decimal 属性列，同时完成值域校验 */
    private MeasureTarget measureTarget(QueryPlan plan, Mention compare, Double pendingValue) {
        Mention nearest = nearestMeasureMention(plan, compare);
        if (nearest == null) {
            return null;
        }
        String hostClassCode;
        String instanceName;
        if (nearest.getInstanceCode() != null) {
            OntologyModel.OntInstance instance = model.instanceByCode(nearest.getInstanceCode());
            if (instance == null) {
                return null;
            }
            hostClassCode = hostOfInstance(instance.getCode());
            if (hostClassCode == null) {
                // 实体自身类即事件类（如直接说 结果值）
                hostClassCode = instance.getClassCode();
            }
            instanceName = instance.getNameCn();
        } else {
            // 类级提及作度量宿主（如 检验结果值大于7）：类即事件类
            hostClassCode = nearest.getClassCode();
            OntologyModel.OntClass clazz = model.classByCode(hostClassCode);
            instanceName = clazz == null ? nearest.getText() : clazz.getNameCn();
        }
        for (OntologyModel.OntAttribute attribute : model.attributesOf(hostClassCode)) {
            if (!"decimal".equals(attribute.getDataType())) {
                continue;
            }
            OntologyModel.OntMapping mapping = model.attributeMapping(attribute.getCode());
            if (mapping == null) {
                continue;
            }
            String column = mapping.getColumnName();
            if (column == null && mapping.getValueExpr() != null) {
                column = mapping.getValueExpr().replace("{t}.", "");
            }
            if (column == null) {
                continue;
            }
            MeasureTarget target = new MeasureTarget();
            target.hostClassCode = hostClassCode;
            target.instanceName = instanceName;
            target.attributeCode = attribute.getCode();
            target.valueColumn = column;
            target.valueNumber = pendingValue;
            evaluateRange(target, attribute);
            return target;
        }
        return null;
    }

    /** 比较词的度量宿主提及：实例提及与可度量事件类提及中距比较词最近者；
     *  类提及胜出时若存在同宿主的实例提及则让位（如 HbA1c检验结果值 -> HbA1c 实例） */
    private Mention nearestMeasureMention(QueryPlan plan, Mention compare) {
        Mention nearest = null;
        for (Mention mention : plan.getMentions()) {
            if (!mention.isEntity()) {
                continue;
            }
            boolean instanceCandidate = mention.getInstanceCode() != null;
            boolean classCandidate = !instanceCandidate && isMeasureClass(mention.getClassCode());
            if (!instanceCandidate && !classCandidate) {
                continue;
            }
            if (nearest == null || Math.abs(mention.getBegin() - compare.getBegin())
                    < Math.abs(nearest.getBegin() - compare.getBegin())) {
                nearest = mention;
            }
        }
        if (nearest != null && nearest.getInstanceCode() == null) {
            Mention specializing = null;
            for (Mention mention : plan.getMentions()) {
                if (!mention.isEntity() || mention.getInstanceCode() == null) {
                    continue;
                }
                if (!nearest.getClassCode().equals(hostOfInstance(mention.getInstanceCode()))) {
                    continue;
                }
                if (specializing == null || Math.abs(mention.getBegin() - compare.getBegin())
                        < Math.abs(specializing.getBegin() - compare.getBegin())) {
                    specializing = mention;
                }
            }
            if (specializing != null) {
                nearest = specializing;
            }
        }
        return nearest;
    }

    /** 类是否可作度量宿主：锚点可达的事件类且含 decimal 属性映射 */
    private boolean isMeasureClass(String classCode) {
        if (classCode == null || !reachableEventClasses().contains(classCode)) {
            return false;
        }
        for (OntologyModel.OntAttribute attribute : model.attributesOf(classCode)) {
            if ("decimal".equals(attribute.getDataType()) && model.attributeMapping(attribute.getCode()) != null) {
                return true;
            }
        }
        return false;
    }

    /** 类提及是否为某比较词的度量宿主（空闭包类的免责判定，避免误报"无法生成过滤条件"） */
    private boolean isMeasureHostMention(QueryPlan plan, Mention mention) {
        if (!isMeasureClass(mention.getClassCode())) {
            return false;
        }
        for (Mention compare : plan.getMentions()) {
            if (!Mention.TYPE_COMPARE.equals(compare.getType()) || numberAfter(plan, compare) == null) {
                continue;
            }
            if (nearestMeasureMention(plan, compare) == mention) {
                return true;
            }
        }
        return false;
    }

    private void evaluateRange(MeasureTarget target, OntologyModel.OntAttribute attribute) {
        if (attribute.getValueLow() == null || attribute.getValueHigh() == null) {
            target.normalRangeText = "未配置";
            target.credibleRangeText = "未配置";
            target.rangeSummary = "属性未配置正常值区间，按原值生成";
            target.credible = true;
            return;
        }
        BigDecimal value = BigDecimal.valueOf(currentValue(target));
        BigDecimal credibleLow = attribute.getValueLow().multiply(new BigDecimal(CREDIBLE_LOW_FACTOR));
        BigDecimal credibleHigh = attribute.getValueHigh()
                .multiply(new BigDecimal(CREDIBLE_HIGH_NUMERATOR))
                .divide(new BigDecimal(CREDIBLE_HIGH_DENOMINATOR), 2, RoundingMode.HALF_UP);
        target.normalRangeText = formatDecimal(attribute.getValueLow()) + "~" + formatDecimal(attribute.getValueHigh());
        target.credibleRangeText = formatDecimal(credibleLow) + "~" + formatDecimal(credibleHigh);
        boolean inCredible = value.compareTo(credibleLow) >= 0 && value.compareTo(credibleHigh) <= 0;
        target.credible = inCredible;
        boolean inNormal = value.compareTo(attribute.getValueLow()) >= 0
                && value.compareTo(attribute.getValueHigh()) <= 0;
        if (!inCredible) {
            target.rangeSummary = "超出可信区间 " + target.credibleRangeText + "，单位可疑";
        } else if (inNormal) {
            target.rangeSummary = "合法（正常区间 " + target.normalRangeText + "，可信区间 " + target.credibleRangeText
                    + "）";
        } else {
            target.rangeSummary = "合法但偏离正常值（正常 " + target.normalRangeText + "，可信 "
                    + target.credibleRangeText + "）";
        }
    }

    private double currentValue(MeasureTarget target) {
        return target.valueNumber == null ? 0D : target.valueNumber.doubleValue();
    }

    // ------------------------------------------------------------ 意图识别

    private void resolveIntent(QueryPlan plan, TraceContext.TraceStep step) {
        for (Mention mention : plan.getMentions()) {
            if (!Mention.TYPE_INTENT.equals(mention.getType())) {
                continue;
            }
            if (Mention.INTENT_COUNT.equals(mention.getIntentKind())) {
                continue;
            }
            if (Mention.INTENT_COUNT.equals(plan.getIntent())) {
                plan.setIntent(mention.getIntentKind());
                step.kv("意图识别", mention.getText(), intentLabel(mention.getIntentKind()), "意图词典");
            }
        }
        for (Mention mention : plan.getMentions()) {
            if (!Mention.TYPE_NUMBER.equals(mention.getType()) || plan.getQuestion() == null) {
                continue;
            }
            int begin = mention.getBegin().intValue();
            if (begin > 0 && plan.getQuestion().charAt(begin - 1) == '前') {
                if (Mention.INTENT_COUNT.equals(plan.getIntent()) || Mention.INTENT_GROUP.equals(plan.getIntent())) {
                    plan.setIntent(Mention.INTENT_TOP_N);
                }
                if (Mention.INTENT_TOP_N.equals(plan.getIntent())) {
                    plan.setTopN(mention.getNumericValue().longValue());
                    step.kv("意图识别", "前" + mention.getText(), "TopN（N=" + mention.getText() + "）", "意图词典");
                }
            }
        }
        if (Mention.INTENT_TOP_N.equals(plan.getIntent()) && plan.getTopN() == null) {
            plan.setTopN(DEFAULT_TOP_N);
        }
    }

    private String intentLabel(String intent) {
        switch (intent) {
            case Mention.INTENT_TOP_N: return "TopN 排名";
            case Mention.INTENT_AVG: return "均值";
            case Mention.INTENT_GROUP: return "分组计数";
            case Mention.INTENT_LIST: return "实例清单";
            default: return "计数";
        }
    }

    // ------------------------------------------------------------ 意图目标解析（分组列 / 记录数 / 按计数 TopN / 枚举值过滤）

    /**
     * 意图识别之后的目标解析：
     * 分组意图 -> 锚类属性匹配分组列；计数意图 + 「条/人次」+ 唯一事件类 -> 记录数模式；
     * TopN 无度量约束 -> 事件类属性按计数分组排名；枚举词面（门诊/住院/bid 等）-> 附加谓词。
     */
    private void resolveIntentTargets(QueryPlan plan, TraceContext.TraceStep step) {
        String question = plan.getQuestion();
        if (question == null) {
            return;
        }
        attachValueFilters(plan, step, question);
        if (Mention.INTENT_GROUP.equals(plan.getIntent())) {
            resolveGroupColumn(plan, step, question);
        } else if (Mention.INTENT_TOP_N.equals(plan.getIntent()) && !hasValueConstraint(plan)) {
            resolveTopNByCount(plan, step, question);
        } else if (Mention.INTENT_LIST.equals(plan.getIntent())) {
            resolveListTarget(plan, step);
        } else if (Mention.INTENT_COUNT.equals(plan.getIntent())) {
            resolveRecordCount(plan, step, question);
        }
    }

    /**
     * 列举意图：问题最靠后的类提及约束转清单目标；
     * 闭包覆盖宿主全量实例时免行过滤（全量类无选择性），否则实例谓词转为清单表行过滤，
     * 时间/数值/枚举谓词随行保留（如 近一个月开了哪些药品 -> 行级 prescribe_date 过滤）。
     */
    private void resolveListTarget(QueryPlan plan, TraceContext.TraceStep step) {
        QueryPlan.Constraint target = null;
        for (QueryPlan.Constraint constraint : plan.getConstraints()) {
            if (constraint.isFromClassMention()
                    && !RelationLinker.ANCHOR_CLASS.equals(constraint.getHostClassCode())) {
                target = constraint;
            }
        }
        if (target == null) {
            plan.addWarning("列举意图未识别到目标类（如 药品/诊断），无法生成清单");
            step.text("未找到类级提及作为列举目标", "warn");
            return;
        }
        List<String> columns = LIST_DISPLAY_COLUMNS.get(target.getHostClassCode());
        if (columns == null) {
            plan.addWarning("列举目标类 " + target.getHostClassCode() + " 未配置展示列，无法生成清单");
            step.text("类 " + target.getHostClassCode() + " 缺少展示列映射，无法生成清单", "warn");
            return;
        }
        List<String> allCodes = hostInstanceCodes(target.getHostClassCode());
        boolean selective = !target.getInstanceCodes().containsAll(allCodes);
        if (!selective) {
            // 全量闭包不构成行过滤，仅保留时间/数值/枚举谓词挂清单表行
            target.getInstanceCodes().clear();
        }
        if (!target.getInstanceCodes().isEmpty() || target.hasTime() || target.hasValue()
                || !target.getExtraPredicates().isEmpty()) {
            plan.setListRowConstraint(target);
        }
        plan.setListHostClassCode(target.getHostClassCode());
        plan.setListColumns(columns);
        plan.getConstraints().remove(target);
        step.kv("列举目标", target.getTriggerText(),
                tableNameOf(target.getHostClassCode()) + "." + String.join("/", columns)
                        + (selective ? "（闭包 " + target.getInstanceCodes().size() + " 例作行过滤）"
                                : "（闭包覆盖全量 " + allCodes.size() + " 例，免行过滤）"),
                RULE_SUBCLASS);
    }

    /** 宿主事件表上的全部实例编码（闭包选择性判定基准） */
    private List<String> hostInstanceCodes(String hostClassCode) {
        List<String> codes = new ArrayList<>();
        for (OntologyModel.OntInstance instance : model.allInstances()) {
            if (hostClassCode.equals(hostOfInstance(instance.getCode())) && !codes.contains(instance.getCode())) {
                codes.add(instance.getCode());
            }
        }
        return codes;
    }

    /** 分组意图：年龄分段词面 -> CASE 桶表达式；否则锚类/事件类 varchar 属性按词面匹配分组列 */
    private void resolveGroupColumn(QueryPlan plan, TraceContext.TraceStep step, String question) {
        if (resolveAgeBucketGroup(plan, step, question)) {
            return;
        }
        for (OntologyModel.OntAttribute attribute : model.attributesOf(RelationLinker.ANCHOR_CLASS)) {
            String column = mappedColumn(attribute);
            if (column == null) {
                continue;
            }
            String matchedWord = attributeWordInQuestion(attribute, question);
            if (matchedWord == null) {
                continue;
            }
            plan.setGroupColumn(column);
            plan.setGroupAttributeCode(attribute.getCode());
            step.kv("分组属性", matchedWord, tableNameOf(RelationLinker.ANCHOR_CLASS) + "." + column,
                    "属性映射");
            return;
        }
        for (String eventClassCode : focusedEventClasses(plan, question)) {
            for (OntologyModel.OntAttribute attribute : model.attributesOf(eventClassCode)) {
                String column = mappedColumn(attribute);
                if (column == null) {
                    continue;
                }
                String matchedWord = attributeWordInQuestion(attribute, question);
                if (matchedWord == null) {
                    continue;
                }
                plan.setGroupColumn(column);
                plan.setGroupAttributeCode(attribute.getCode());
                plan.setGroupHostClassCode(eventClassCode);
                step.kv("分组属性", matchedWord, tableNameOf(eventClassCode) + "." + column + "（按记录数）",
                        "属性映射");
                return;
            }
        }
        step.text("未解析到分组属性，按缺省列 region 分组", "warn");
    }

    /** 年龄分段分组：问题含「年龄」或同时含「青年/老年」时按值域分段知识生成 CASE 桶表达式 */
    private boolean resolveAgeBucketGroup(QueryPlan plan, TraceContext.TraceStep step, String question) {
        boolean byAgeWord = question.contains("年龄");
        boolean byBucketWords = question.contains("青年") && question.contains("老年");
        if (!byAgeWord && !byBucketWords) {
            return false;
        }
        plan.setGroupColumn(AGE_BUCKET_ALIAS);
        plan.setGroupExpression(AGE_BUCKET_EXPRESSION);
        step.kv("值域分段", "年龄", "青年(<45) / 中年(45-59) / 老年(>=60)", "值域分段知识");
        return true;
    }

    /** 记录数意图：问「多少条/人次」且问题聚焦唯一事件类时，计数对象改为事件表行数 */
    private void resolveRecordCount(QueryPlan plan, TraceContext.TraceStep step, String question) {
        if (!question.contains("条") && !question.contains("人次")) {
            return;
        }
        List<String> focused = focusedEventClasses(plan, question);
        if (focused.size() != 1) {
            return;
        }
        String eventClassCode = focused.get(0);
        plan.setRecordCountClassCode(eventClassCode);
        OntologyModel.OntClass eventClass = model.classByCode(eventClassCode);
        step.kv("计数对象", "记录数", tableNameOf(eventClassCode) + " 行数（"
                + (eventClass == null ? eventClassCode : eventClass.getNameCn()) + "）", "事件类聚焦");
    }

    /** 按计数 TopN：事件类属性匹配分组列（如 就诊量最高的前五科室 -> fact_visit.dept_code） */
    private void resolveTopNByCount(QueryPlan plan, TraceContext.TraceStep step, String question) {
        for (String eventClassCode : focusedEventClasses(plan, question)) {
            for (OntologyModel.OntAttribute attribute : model.attributesOf(eventClassCode)) {
                String column = mappedColumn(attribute);
                if (column == null) {
                    continue;
                }
                String matchedWord = attributeWordInQuestion(attribute, question);
                if (matchedWord == null) {
                    continue;
                }
                plan.setTopNGroupClassCode(eventClassCode);
                plan.setTopNGroupColumn(column);
                step.kv("TopN 分组", matchedWord, tableNameOf(eventClassCode) + "." + column + " 按计数排名",
                        "属性映射");
                return;
            }
        }
    }

    /** 枚举值过滤：门诊/住院/急诊/qid 等词面 -> 事件表列值谓词 */
    private void attachValueFilters(QueryPlan plan, TraceContext.TraceStep step, String question) {
        for (ValueFilter filter : VALUE_FILTERS) {
            if (!question.contains(filter.word)) {
                continue;
            }
            QueryPlan.Constraint constraint = constraintOfHost(plan, filter.classCode);
            if (constraint == null) {
                constraint = new QueryPlan.Constraint(filter.word, Integer.valueOf(question.indexOf(filter.word)));
                constraint.setHostClassCode(filter.classCode);
                plan.getConstraints().add(constraint);
            }
            String predicate = "{t}." + filter.column + " = '" + filter.value + "'";
            if (constraint.getExtraPredicates().contains(predicate)) {
                continue;
            }
            constraint.getExtraPredicates().add(predicate);
            step.kv("值过滤", filter.word, tableNameOf(filter.classCode) + "." + filter.column + " = '"
                    + filter.value + "'", "枚举值词典");
        }
    }

    /**
     * 属性列名（经 kind=attribute 映射；无映射属性不可用作分组/过滤目标）。
     * 复合表达式属性（如 ATTR_AGE 的 TIMESTAMPDIFF 推导）不可作分组列，返回 null。
     */
    private String mappedColumn(OntologyModel.OntAttribute attribute) {
        OntologyModel.OntMapping mapping = model.attributeMapping(attribute.getCode());
        if (mapping == null) {
            return null;
        }
        String valueExpr = mapping.getValueExpr();
        boolean plainColumnExpr = valueExpr == null || valueExpr.matches("\\{t}\\.\\w+");
        if (mapping.getColumnName() != null && plainColumnExpr) {
            return mapping.getColumnName();
        }
        if (plainColumnExpr && valueExpr != null) {
            return valueExpr.replace("{t}.", "");
        }
        return null;
    }

    /** 属性词面命中问题：属性名或别名出现在问题中，返回命中的词 */
    private String attributeWordInQuestion(OntologyModel.OntAttribute attribute, String question) {
        if (attribute.getNameCn() != null && question.contains(attribute.getNameCn())) {
            return attribute.getNameCn();
        }
        for (String marker : ATTRIBUTE_MARKERS.getOrDefault(attribute.getCode(), List.of())) {
            if (question.contains(marker)) {
                return marker;
            }
        }
        return null;
    }

    /** 问题聚焦的事件类：已有约束宿主 + 问题中出现其事件词的事件类（去重，类序稳定） */
    private List<String> focusedEventClasses(QueryPlan plan, String question) {
        List<String> focused = new ArrayList<>();
        for (String eventClassCode : reachableEventClasses()) {
            boolean inConstraints = false;
            for (QueryPlan.Constraint constraint : plan.getConstraints()) {
                if (eventClassCode.equals(constraint.getHostClassCode())) {
                    inConstraints = true;
                    break;
                }
            }
            if (inConstraints || eventWordInQuestion(eventClassCode, question)) {
                if (!focused.contains(eventClassCode)) {
                    focused.add(eventClassCode);
                }
            }
        }
        return focused;
    }

    /** 事件类词面（类名 + EVENT_MARKERS 别名）是否出现在问题中 */
    private boolean eventWordInQuestion(String eventClassCode, String question) {
        OntologyModel.OntClass eventClass = model.classByCode(eventClassCode);
        if (eventClass != null && eventClass.getNameCn() != null && question.contains(eventClass.getNameCn())) {
            return true;
        }
        for (String marker : EVENT_MARKERS.getOrDefault(eventClassCode, List.of())) {
            if (question.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasValueConstraint(QueryPlan plan) {
        for (QueryPlan.Constraint constraint : plan.getConstraints()) {
            if (constraint.hasValue()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 歧义检测

    private void detectAmbiguity(QueryPlan plan, TraceContext.TraceStep step) {
        int count = 0;
        for (Mention mention : plan.getMentions()) {
            if (!mention.isEntity() || mention.getCandidateInstanceCodes() == null) {
                continue;
            }
            if (mention.getCandidateInstanceCodes().size() <= 1) {
                continue;
            }
            List<String> classNames = new ArrayList<>();
            for (String code : mention.getCandidateInstanceCodes()) {
                OntologyModel.OntInstance instance = model.instanceByCode(code);
                OntologyModel.OntClass clazz = instance == null ? null : model.classByCode(instance.getClassCode());
                classNames.add(clazz == null ? "?" : clazz.getNameCn());
            }
            count = count + 1;
            step.text("「" + mention.getText() + "」同词命中 " + mention.getCandidateInstanceCodes().size()
                    + " 个跨类实例（" + String.join("/", classNames) + "），无法消歧，默认取 "
                    + mention.getInstanceCode(), "warn");
        }
        plan.setAmbiguityCount(Integer.valueOf(count));
        if (count == 0) {
            step.text("无未决歧义", "ok");
        }
    }

    // ------------------------------------------------------------ 公共工具

    /** 宿主事件类：实例映射表名 -> 类映射反查 */
    public String hostOfInstance(String instanceCode) {
        OntologyModel.OntMapping mapping = model.instanceMapping(instanceCode);
        if (mapping == null || mapping.getTableName() == null) {
            return null;
        }
        OntologyModel.OntClass host = model.classOfTable(mapping.getTableName());
        return host == null ? null : host.getCode();
    }

    public String tableNameOf(String classCode) {
        OntologyModel.OntMapping mapping = model.classMapping(classCode);
        return mapping == null ? null : mapping.getTableName();
    }

    private QueryPlan.Constraint constraintOfHost(QueryPlan plan, String hostClassCode) {
        for (QueryPlan.Constraint constraint : plan.getConstraints()) {
            if (hostClassCode.equals(constraint.getHostClassCode())) {
                return constraint;
            }
        }
        return null;
    }

    /** 数值展示：7.0 -> 7，7.5 -> 7.5 */
    public static String formatNumber(Double value) {
        return BigDecimal.valueOf(value.doubleValue()).stripTrailingZeros().toPlainString();
    }

    private String formatDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** 事件词命中 */
    private static class MarkerHit {
        private final String marker;
        private final long distance;
        private final boolean leftSide;

        MarkerHit(String marker, long distance, boolean leftSide) {
            this.marker = marker;
            this.distance = distance;
            this.leftSide = leftSide;
        }
    }

    /** 枚举值过滤条目：事件类 + 列 + 问题词面 + 列值 */
    private static final class ValueFilter {
        private final String classCode;
        private final String column;
        private final String word;
        private final String value;

        ValueFilter(String classCode, String column, String word, String value) {
            this.classCode = classCode;
            this.column = column;
            this.word = word;
            this.value = value;
        }
    }

    /** 事件类候选（时间挂靠排序用） */
    private static class EventCandidate {
        private final String eventClassCode;
        private final String marker;
        private final long distance;
        private final boolean leftSide;

        EventCandidate(String eventClassCode, String marker, long distance, boolean leftSide) {
            this.eventClassCode = eventClassCode;
            this.marker = marker;
            this.distance = distance;
            this.leftSide = leftSide;
        }

        int sideRank() {
            return leftSide ? 0 : 1;
        }
    }

    /** 度量目标（值域校验结果随行） */
    private static class MeasureTarget {
        private String hostClassCode;
        private String instanceName;
        private String attributeCode;
        private String valueColumn;
        private Double valueNumber;
        private String normalRangeText;
        private String credibleRangeText;
        private String rangeSummary;
        private boolean credible = true;
    }
}
