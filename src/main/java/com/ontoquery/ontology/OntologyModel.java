package com.ontoquery.ontology;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内存本体：类/实例/同义词/关系/属性/映射/规则，按 id 与 code 双索引。
 * 只读查询由管线各环节使用；OntologyRepository 启动时全量装配，测试手工构造 fixture。
 */
public class OntologyModel {

    /** 映射 kind 常量（ont_mapping.kind） */
    public static final String MAPPING_KIND_CLASS = "class";
    public static final String MAPPING_KIND_RELATION = "relation";
    public static final String MAPPING_KIND_ATTRIBUTE = "attribute";
    public static final String MAPPING_KIND_INSTANCE = "instance";

    private final Map<Long, OntClass> classesById = new LinkedHashMap<>();
    private final Map<String, OntClass> classesByCode = new LinkedHashMap<>();
    private final Map<Long, OntInstance> instancesById = new LinkedHashMap<>();
    private final Map<String, OntInstance> instancesByCode = new LinkedHashMap<>();
    private final Map<String, List<SynonymEntry>> synonymsByTerm = new LinkedHashMap<>();
    private final Map<String, List<SynonymEntry>> synonymsByInstance = new LinkedHashMap<>();
    private final Map<String, List<SynonymEntry>> synonymsByClass = new LinkedHashMap<>();
    private final Map<Long, OntRelation> relationsById = new LinkedHashMap<>();
    private final Map<String, OntRelation> relationsByCode = new LinkedHashMap<>();
    private final Map<String, OntAttribute> attributesByCode = new LinkedHashMap<>();
    private final Map<String, List<OntAttribute>> attributesByClass = new LinkedHashMap<>();
    private final Map<String, Map<String, OntMapping>> mappingsByKind = new HashMap<>();
    private final Map<String, OntRule> rulesByCode = new LinkedHashMap<>();

    private long idSequence = 0L;

    private long nextId() {
        idSequence = idSequence + 1;
        return idSequence;
    }

    // ---------------------------------------------------------------- 装配

    public OntClass addClass(String code, String nameCn, String parentCode, String color, Integer sortNo,
            String remark) {
        OntClass c = new OntClass(nextId(), code, nameCn, parentCode, color, sortNo, remark);
        classesById.put(c.getId(), c);
        classesByCode.put(c.getCode(), c);
        return c;
    }

    public OntInstance addInstance(String code, String classCode, String nameCn, String remark) {
        OntInstance i = new OntInstance(nextId(), code, classCode, nameCn, remark);
        instancesById.put(i.getId(), i);
        instancesByCode.put(i.getCode(), i);
        return i;
    }

    public SynonymEntry addSynonym(String term, String instanceCode, String classCode) {
        SynonymEntry s = new SynonymEntry(term, instanceCode, classCode);
        synonymsByTerm.computeIfAbsent(term, k -> new ArrayList<>()).add(s);
        if (instanceCode != null) {
            synonymsByInstance.computeIfAbsent(instanceCode, k -> new ArrayList<>()).add(s);
        }
        if (classCode != null) {
            synonymsByClass.computeIfAbsent(classCode, k -> new ArrayList<>()).add(s);
        }
        return s;
    }

    public OntRelation addRelation(String code, String nameCn, String domainClassCode, String rangeClassCode,
            String cardinality, String remark) {
        OntRelation r = new OntRelation(nextId(), code, nameCn, domainClassCode, rangeClassCode, cardinality,
                remark);
        relationsById.put(r.getId(), r);
        relationsByCode.put(r.getCode(), r);
        return r;
    }

    public OntAttribute addAttribute(String classCode, String code, String nameCn, String dataType, String unit,
            BigDecimal valueLow, BigDecimal valueHigh, String remark) {
        OntAttribute a = new OntAttribute(nextId(), classCode, code, nameCn, dataType, unit, valueLow, valueHigh,
                remark);
        attributesByCode.put(a.getCode(), a);
        attributesByClass.computeIfAbsent(classCode, k -> new ArrayList<>()).add(a);
        return a;
    }

    public OntMapping addMapping(String kind, String refCode, String tableName, String columnName,
            String joinCondition, String valueExpr) {
        OntMapping m = new OntMapping(kind, refCode, tableName, columnName, joinCondition, valueExpr);
        mappingsByKind.computeIfAbsent(kind, k -> new LinkedHashMap<>()).put(refCode, m);
        return m;
    }

    public OntRule addRule(String ruleCode, String ruleType, String nameCn, String config) {
        OntRule r = new OntRule(ruleCode, ruleType, nameCn, config);
        rulesByCode.put(r.getRuleCode(), r);
        return r;
    }

    // ---------------------------------------------------------------- 类查询

    public OntClass classByCode(String code) { return classesByCode.get(code); }

    public List<OntClass> allClasses() { return new ArrayList<>(classesByCode.values()); }

    /** 顶级类（无父类或父类不存在），按 sortNo 排序 */
    public List<OntClass> topClasses() {
        List<OntClass> tops = new ArrayList<>();
        for (OntClass c : classesByCode.values()) {
            if (c.getParentCode() == null || !classesByCode.containsKey(c.getParentCode())) {
                tops.add(c);
            }
        }
        tops.sort((a, b) -> Integer.compare(a.getSortNo(), b.getSortNo()));
        return tops;
    }

    /** 直接子类，按 sortNo 排序 */
    public List<OntClass> childClasses(String classCode) {
        List<OntClass> children = new ArrayList<>();
        for (OntClass c : classesByCode.values()) {
            if (classCode.equals(c.getParentCode())) {
                children.add(c);
            }
        }
        children.sort((a, b) -> Integer.compare(a.getSortNo(), b.getSortNo()));
        return children;
    }

    /** 子类闭包：自身 + 全部后代类 code */
    public Set<String> subclassClosure(String classCode) {
        Set<String> closure = new HashSet<>();
        collectClosure(classCode, closure);
        return closure;
    }

    private void collectClosure(String classCode, Set<String> closure) {
        if (classCode == null || !closure.add(classCode)) {
            return;
        }
        for (OntClass c : childClasses(classCode)) {
            collectClosure(c.getCode(), closure);
        }
    }

    /** 距离顶级的深度（分组 0 / 核心 1 / 事件 2 / 维度 3），顶级为 0 */
    public int classDepth(String classCode) {
        int depth = 0;
        OntClass c = classByCode(classCode);
        Set<String> visited = new HashSet<>();
        while (c != null && c.getParentCode() != null && visited.add(c.getParentCode())) {
            OntClass parent = classByCode(c.getParentCode());
            if (parent == null) {
                break;
            }
            depth = depth + 1;
            c = parent;
        }
        return depth;
    }

    // ---------------------------------------------------------------- 实例查询

    public OntInstance instanceByCode(String code) { return instancesByCode.get(code); }

    public List<OntInstance> allInstances() { return new ArrayList<>(instancesById.values()); }

    /** 该类直接实例（按 id 序） */
    public List<OntInstance> instancesOf(String classCode) {
        List<OntInstance> result = new ArrayList<>();
        for (OntInstance i : instancesById.values()) {
            if (classCode.equals(i.getClassCode())) {
                result.add(i);
            }
        }
        return result;
    }

    /** 子类闭包内全部实例 */
    public List<OntInstance> instancesOfClosure(String classCode) {
        Set<String> closure = subclassClosure(classCode);
        List<OntInstance> result = new ArrayList<>();
        for (OntInstance i : instancesById.values()) {
            if (closure.contains(i.getClassCode())) {
                result.add(i);
            }
        }
        return result;
    }

    /** 同名实例（跨类同名即歧义来源） */
    public List<OntInstance> instancesNamed(String nameCn) {
        List<OntInstance> result = new ArrayList<>();
        for (OntInstance i : instancesById.values()) {
            if (i.getNameCn().equals(nameCn)) {
                result.add(i);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- 同义词查询

    public List<SynonymEntry> synonymsOfTerm(String term) {
        return synonymsByTerm.getOrDefault(term, Collections.emptyList());
    }

    /** 全部同义词条（按词条插入序） */
    public List<SynonymEntry> allSynonymEntries() {
        List<SynonymEntry> result = new ArrayList<>();
        for (List<SynonymEntry> entries : synonymsByTerm.values()) {
            result.addAll(entries);
        }
        return result;
    }

    public List<String> synonymTermsOfInstance(String instanceCode) {
        List<String> terms = new ArrayList<>();
        for (SynonymEntry s : synonymsByInstance.getOrDefault(instanceCode, Collections.emptyList())) {
            terms.add(s.getTerm());
        }
        return terms;
    }

    public List<String> synonymTermsOfClass(String classCode) {
        List<String> terms = new ArrayList<>();
        for (SynonymEntry s : synonymsByClass.getOrDefault(classCode, Collections.emptyList())) {
            terms.add(s.getTerm());
        }
        return terms;
    }

    // ---------------------------------------------------------------- 关系查询

    public OntRelation relationByCode(String code) { return relationsByCode.get(code); }

    public List<OntRelation> allRelations() { return new ArrayList<>(relationsById.values()); }

    /** 以某类为定义域的关系 */
    public List<OntRelation> relationsFrom(String classCode) {
        List<OntRelation> result = new ArrayList<>();
        for (OntRelation r : relationsById.values()) {
            if (classCode.equals(r.getDomainClassCode())) {
                result.add(r);
            }
        }
        return result;
    }

    /** 以某类为值域的关系 */
    public List<OntRelation> relationsTo(String classCode) {
        List<OntRelation> result = new ArrayList<>();
        for (OntRelation r : relationsById.values()) {
            if (classCode.equals(r.getRangeClassCode())) {
                result.add(r);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- 属性与映射查询

    public OntAttribute attributeByCode(String code) { return attributesByCode.get(code); }

    public List<OntAttribute> attributesOf(String classCode) {
        return attributesByClass.getOrDefault(classCode, Collections.emptyList());
    }

    public OntMapping mapping(String kind, String refCode) {
        Map<String, OntMapping> byRef = mappingsByKind.get(kind);
        return byRef == null ? null : byRef.get(refCode);
    }

    /** 类 -> 物理表（kind=class 映射） */
    public OntMapping classMapping(String classCode) { return mapping(MAPPING_KIND_CLASS, classCode); }

    /** 实例 -> 过滤谓词（kind=instance 映射，valueExpr 含 {t} 占位） */
    public OntMapping instanceMapping(String instanceCode) { return mapping(MAPPING_KIND_INSTANCE, instanceCode); }

    /** 关系 -> 事件表 join 条件（kind=relation 映射） */
    public OntMapping relationMapping(String relationCode) { return mapping(MAPPING_KIND_RELATION, relationCode); }

    /** 属性 -> 取值表达式（kind=attribute 映射） */
    public OntMapping attributeMapping(String attributeCode) { return mapping(MAPPING_KIND_ATTRIBUTE, attributeCode); }

    /** 反查：物理表对应的类（class 映射表名匹配） */
    public OntClass classOfTable(String tableName) {
        for (OntClass c : classesByCode.values()) {
            OntMapping m = classMapping(c.getCode());
            if (m != null && tableName.equals(m.getTableName())) {
                return c;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 规则查询

    public OntRule ruleByCode(String ruleCode) { return rulesByCode.get(ruleCode); }

    public List<OntRule> allRules() { return new ArrayList<>(rulesByCode.values()); }

    @Override
    public String toString() {
        return "OntologyModel{classes=" + classesByCode.size() + ", instances=" + instancesByCode.size()
                + ", synonyms=" + synonymsByTerm.size() + ", relations=" + relationsByCode.size()
                + ", attributes=" + attributesByCode.size() + ", rules=" + rulesByCode.size() + '}';
    }

    // ---------------------------------------------------------------- 元素定义

    /** 本体类 */
    public static class OntClass {
        private final Long id;
        private final String code;
        private final String nameCn;
        private final String parentCode;
        private final String color;
        private final Integer sortNo;
        private final String remark;

        OntClass(Long id, String code, String nameCn, String parentCode, String color, Integer sortNo,
                String remark) {
            this.id = id;
            this.code = code;
            this.nameCn = nameCn;
            this.parentCode = parentCode;
            this.color = color;
            this.sortNo = sortNo;
            this.remark = remark;
        }

        public Long getId() { return id; }
        public String getCode() { return code; }
        public String getNameCn() { return nameCn; }
        public String getParentCode() { return parentCode; }
        public String getColor() { return color; }
        public Integer getSortNo() { return sortNo; }
        public String getRemark() { return remark; }

        @Override
        public String toString() {
            return "OntClass{code='" + code + "', nameCn='" + nameCn + "', parentCode='" + parentCode + "'}";
        }
    }

    /** 本体实例 */
    public static class OntInstance {
        private final Long id;
        private final String code;
        private final String classCode;
        private final String nameCn;
        private final String remark;

        OntInstance(Long id, String code, String classCode, String nameCn, String remark) {
            this.id = id;
            this.code = code;
            this.classCode = classCode;
            this.nameCn = nameCn;
            this.remark = remark;
        }

        public Long getId() { return id; }
        public String getCode() { return code; }
        public String getClassCode() { return classCode; }
        public String getNameCn() { return nameCn; }
        public String getRemark() { return remark; }

        @Override
        public String toString() {
            return "OntInstance{code='" + code + "', nameCn='" + nameCn + "', classCode='" + classCode + "'}";
        }
    }

    /** 同义词条：指向实例或类（二者取一） */
    public static class SynonymEntry {
        private final String term;
        private final String instanceCode;
        private final String classCode;

        SynonymEntry(String term, String instanceCode, String classCode) {
            this.term = term;
            this.instanceCode = instanceCode;
            this.classCode = classCode;
        }

        public String getTerm() { return term; }
        public String getInstanceCode() { return instanceCode; }
        public String getClassCode() { return classCode; }

        @Override
        public String toString() {
            return "SynonymEntry{term='" + term + "', instanceCode='" + instanceCode + "', classCode='" + classCode
                    + "'}";
        }
    }

    /** 对象关系（定义域 -> 值域） */
    public static class OntRelation {
        private final Long id;
        private final String code;
        private final String nameCn;
        private final String domainClassCode;
        private final String rangeClassCode;
        private final String cardinality;
        private final String remark;

        OntRelation(Long id, String code, String nameCn, String domainClassCode, String rangeClassCode,
                String cardinality, String remark) {
            this.id = id;
            this.code = code;
            this.nameCn = nameCn;
            this.domainClassCode = domainClassCode;
            this.rangeClassCode = rangeClassCode;
            this.cardinality = cardinality;
            this.remark = remark;
        }

        public Long getId() { return id; }
        public String getCode() { return code; }
        public String getNameCn() { return nameCn; }
        public String getDomainClassCode() { return domainClassCode; }
        public String getRangeClassCode() { return rangeClassCode; }
        public String getCardinality() { return cardinality; }
        public String getRemark() { return remark; }

        @Override
        public String toString() {
            return "OntRelation{code='" + code + "', domain='" + domainClassCode + "', range='" + rangeClassCode
                    + "'}";
        }
    }

    /** 数据属性（含值域知识 valueLow/valueHigh） */
    public static class OntAttribute {
        private final Long id;
        private final String classCode;
        private final String code;
        private final String nameCn;
        private final String dataType;
        private final String unit;
        private final BigDecimal valueLow;
        private final BigDecimal valueHigh;
        private final String remark;

        OntAttribute(Long id, String classCode, String code, String nameCn, String dataType, String unit,
                BigDecimal valueLow, BigDecimal valueHigh, String remark) {
            this.id = id;
            this.classCode = classCode;
            this.code = code;
            this.nameCn = nameCn;
            this.dataType = dataType;
            this.unit = unit;
            this.valueLow = valueLow;
            this.valueHigh = valueHigh;
            this.remark = remark;
        }

        public Long getId() { return id; }
        public String getClassCode() { return classCode; }
        public String getCode() { return code; }
        public String getNameCn() { return nameCn; }
        public String getDataType() { return dataType; }
        public String getUnit() { return unit; }
        public BigDecimal getValueLow() { return valueLow; }
        public BigDecimal getValueHigh() { return valueHigh; }
        public String getRemark() { return remark; }

        @Override
        public String toString() {
            return "OntAttribute{code='" + code + "', classCode='" + classCode + "', dataType='" + dataType + "'}";
        }
    }

    /** 本体到物理数据源映射（kind 见 MAPPING_KIND_* 常量） */
    public static class OntMapping {
        private final String kind;
        private final String refCode;
        private final String tableName;
        private final String columnName;
        private final String joinCondition;
        private final String valueExpr;

        OntMapping(String kind, String refCode, String tableName, String columnName, String joinCondition,
                String valueExpr) {
            this.kind = kind;
            this.refCode = refCode;
            this.tableName = tableName;
            this.columnName = columnName;
            this.joinCondition = joinCondition;
            this.valueExpr = valueExpr;
        }

        public String getKind() { return kind; }
        public String getRefCode() { return refCode; }
        public String getTableName() { return tableName; }
        public String getColumnName() { return columnName; }
        public String getJoinCondition() { return joinCondition; }
        public String getValueExpr() { return valueExpr; }

        @Override
        public String toString() {
            return "OntMapping{kind='" + kind + "', refCode='" + refCode + "', table='" + tableName + "'}";
        }
    }

    /** 推理规则 */
    public static class OntRule {
        private final String ruleCode;
        private final String ruleType;
        private final String nameCn;
        private final String config;

        OntRule(String ruleCode, String ruleType, String nameCn, String config) {
            this.ruleCode = ruleCode;
            this.ruleType = ruleType;
            this.nameCn = nameCn;
            this.config = config;
        }

        public String getRuleCode() { return ruleCode; }
        public String getRuleType() { return ruleType; }
        public String getNameCn() { return nameCn; }
        public String getConfig() { return config; }

        @Override
        public String toString() {
            return "OntRule{ruleCode='" + ruleCode + "', ruleType='" + ruleType + "'}";
        }
    }
}
