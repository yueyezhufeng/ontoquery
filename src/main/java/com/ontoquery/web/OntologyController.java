package com.ontoquery.web;

import com.ontoquery.ontology.OntologyModel;
import com.ontoquery.ontology.OntologyRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 本体图谱端点（CONTRACT.md 第 4 节）：
 * tree 树递归 children；graph 节点 layer 0=分组 1=核心 2=事件 3=维度，edges kind=data/subclass；
 * entity 按 kind 判别类/实例，类详情 instances 上限 50 条 + instanceTotal。
 *
 * @author 月夜烛峰
 */
@RestController
@RequestMapping("/api/ontology")
public class OntologyController {

    /** 类详情 instances 返回上限 */
    private static final int INSTANCE_LIMIT = 50;

    private final OntologyRepository repository;

    public OntologyController(OntologyRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/tree")
    public List<TreeNodeDto> tree() {
        List<TreeNodeDto> roots = new ArrayList<>();
        for (OntologyModel.OntClass top : repository.getModel().topClasses()) {
            roots.add(toTreeNode(top));
        }
        return roots;
    }

    private TreeNodeDto toTreeNode(OntologyModel.OntClass clazz) {
        TreeNodeDto node = new TreeNodeDto();
        node.setCode(clazz.getCode());
        node.setName(clazz.getNameCn());
        node.setColor(clazz.getColor());
        node.setInstanceCount(Integer.valueOf(repository.getModel().instancesOf(clazz.getCode()).size()));
        List<TreeNodeDto> children = new ArrayList<>();
        for (OntologyModel.OntClass child : repository.getModel().childClasses(clazz.getCode())) {
            children.add(toTreeNode(child));
        }
        node.setChildren(children);
        return node;
    }

    @GetMapping("/graph")
    public GraphDto graph() {
        OntologyModel model = repository.getModel();
        GraphDto graph = new GraphDto();
        for (OntologyModel.OntClass clazz : model.allClasses()) {
            GraphNodeDto node = new GraphNodeDto();
            node.setCode(clazz.getCode());
            node.setName(clazz.getNameCn());
            node.setColor(clazz.getColor());
            node.setLayer(Integer.valueOf(model.classDepth(clazz.getCode())));
            node.setInstanceCount(Integer.valueOf(model.instancesOf(clazz.getCode()).size()));
            graph.getNodes().add(node);
        }
        for (OntologyModel.OntRelation relation : model.allRelations()) {
            GraphEdgeDto edge = new GraphEdgeDto();
            edge.setFrom(relation.getDomainClassCode());
            edge.setTo(relation.getRangeClassCode());
            edge.setLabel(relation.getNameCn());
            edge.setKind("data");
            graph.getEdges().add(edge);
        }
        for (OntologyModel.OntClass clazz : model.allClasses()) {
            if (clazz.getParentCode() == null) {
                continue;
            }
            GraphEdgeDto edge = new GraphEdgeDto();
            edge.setFrom(clazz.getCode());
            edge.setTo(clazz.getParentCode());
            edge.setLabel("subClassOf");
            edge.setKind("subclass");
            graph.getEdges().add(edge);
        }
        return graph;
    }

    @GetMapping("/entity/{code}")
    public Object entity(@PathVariable String code) {
        OntologyModel model = repository.getModel();
        OntologyModel.OntClass clazz = model.classByCode(code);
        if (clazz != null) {
            return classDetail(model, clazz);
        }
        OntologyModel.OntInstance instance = model.instanceByCode(code);
        if (instance != null) {
            return instanceDetail(model, instance);
        }
        throw new IllegalArgumentException("未找到本体元素: " + code);
    }

    private ClassDetailDto classDetail(OntologyModel model, OntologyModel.OntClass clazz) {
        ClassDetailDto detail = new ClassDetailDto();
        detail.setKind("class");
        detail.setCode(clazz.getCode());
        detail.setName(clazz.getNameCn());
        detail.setColor(clazz.getColor());
        detail.setRemark(clazz.getRemark());
        if (clazz.getParentCode() != null) {
            OntologyModel.OntClass parent = model.classByCode(clazz.getParentCode());
            RefDto parentRef = new RefDto();
            parentRef.setCode(clazz.getParentCode());
            parentRef.setName(parent == null ? clazz.getParentCode() : parent.getNameCn());
            detail.setParent(parentRef);
        }
        for (OntologyModel.OntAttribute attribute : model.attributesOf(clazz.getCode())) {
            AttributeDto attributeDto = new AttributeDto();
            attributeDto.setCode(attribute.getCode());
            attributeDto.setName(attribute.getNameCn());
            attributeDto.setDataType(attribute.getDataType());
            attributeDto.setUnit(attribute.getUnit());
            attributeDto.setValueLow(attribute.getValueLow());
            attributeDto.setValueHigh(attribute.getValueHigh());
            OntologyModel.OntMapping mapping = model.attributeMapping(attribute.getCode());
            if (mapping != null) {
                MappingRefDto mappingRef = new MappingRefDto();
                mappingRef.setTable(mapping.getTableName());
                mappingRef.setColumn(mapping.getColumnName());
                attributeDto.getMappings().add(mappingRef);
            }
            detail.getAttributes().add(attributeDto);
        }
        for (OntologyModel.OntRelation relation : model.relationsFrom(clazz.getCode())) {
            OntologyModel.OntClass target = model.classByCode(relation.getRangeClassCode());
            RelationDto relationDto = new RelationDto();
            relationDto.setCode(relation.getCode());
            relationDto.setName(relation.getNameCn());
            relationDto.setDirection("out");
            relationDto.setTarget(refOf(target, relation.getRangeClassCode()));
            detail.getRelations().add(relationDto);
        }
        for (OntologyModel.OntRelation relation : model.relationsTo(clazz.getCode())) {
            OntologyModel.OntClass target = model.classByCode(relation.getDomainClassCode());
            RelationDto relationDto = new RelationDto();
            relationDto.setCode(relation.getCode());
            relationDto.setName(relation.getNameCn());
            relationDto.setDirection("in");
            relationDto.setTarget(refOf(target, relation.getDomainClassCode()));
            detail.getRelations().add(relationDto);
        }
        detail.setSynonyms(model.synonymTermsOfClass(clazz.getCode()));
        OntologyModel.OntMapping classMapping = model.classMapping(clazz.getCode());
        if (classMapping != null) {
            FullMappingDto mappingDto = new FullMappingDto();
            mappingDto.setTable(classMapping.getTableName());
            mappingDto.setColumn(classMapping.getColumnName());
            mappingDto.setKind("class");
            detail.getMappings().add(mappingDto);
        }
        List<OntologyModel.OntInstance> instances = model.instancesOf(clazz.getCode());
        for (OntologyModel.OntInstance instance : instances.subList(0, Math.min(INSTANCE_LIMIT, instances.size()))) {
            InstanceRefDto instanceRef = new InstanceRefDto();
            instanceRef.setCode(instance.getCode());
            instanceRef.setName(instance.getNameCn());
            detail.getInstances().add(instanceRef);
        }
        detail.setInstanceTotal(Integer.valueOf(instances.size()));
        return detail;
    }

    private InstanceDetailDto instanceDetail(OntologyModel model, OntologyModel.OntInstance instance) {
        InstanceDetailDto detail = new InstanceDetailDto();
        detail.setKind("instance");
        detail.setCode(instance.getCode());
        detail.setName(instance.getNameCn());
        detail.setRemark(instance.getRemark());
        OntologyModel.OntClass clazz = model.classByCode(instance.getClassCode());
        ClassInfoDto classInfo = new ClassInfoDto();
        classInfo.setCode(instance.getClassCode());
        classInfo.setName(clazz == null ? instance.getClassCode() : clazz.getNameCn());
        classInfo.setColor(clazz == null ? null : clazz.getColor());
        detail.setClassInfo(classInfo);
        detail.setSynonyms(model.synonymTermsOfInstance(instance.getCode()));
        OntologyModel.OntMapping mapping = model.instanceMapping(instance.getCode());
        if (mapping != null) {
            FullMappingDto mappingDto = new FullMappingDto();
            mappingDto.setTable(mapping.getTableName());
            mappingDto.setColumn(mapping.getColumnName());
            mappingDto.setKind("instance");
            mappingDto.setValueExpr(mapping.getValueExpr());
            detail.getMappings().add(mappingDto);
        }
        return detail;
    }

    private RefDto refOf(OntologyModel.OntClass clazz, String code) {
        RefDto ref = new RefDto();
        ref.setCode(code);
        ref.setName(clazz == null ? code : clazz.getNameCn());
        return ref;
    }

    // ------------------------------------------------------------ 响应 DTO（字段名严格按契约）

    public static class TreeNodeDto {
        private String code;
        private String name;
        private String color;
        private Integer instanceCount;
        private List<TreeNodeDto> children = new ArrayList<>();

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public Integer getInstanceCount() { return instanceCount; }
        public void setInstanceCount(Integer instanceCount) { this.instanceCount = instanceCount; }
        public List<TreeNodeDto> getChildren() { return children; }
        public void setChildren(List<TreeNodeDto> children) { this.children = children; }
    }

    public static class GraphDto {
        private List<GraphNodeDto> nodes = new ArrayList<>();
        private List<GraphEdgeDto> edges = new ArrayList<>();

        public List<GraphNodeDto> getNodes() { return nodes; }
        public void setNodes(List<GraphNodeDto> nodes) { this.nodes = nodes; }
        public List<GraphEdgeDto> getEdges() { return edges; }
        public void setEdges(List<GraphEdgeDto> edges) { this.edges = edges; }
    }

    public static class GraphNodeDto {
        private String code;
        private String name;
        private String color;
        private Integer layer;
        private Integer instanceCount;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public Integer getLayer() { return layer; }
        public void setLayer(Integer layer) { this.layer = layer; }
        public Integer getInstanceCount() { return instanceCount; }
        public void setInstanceCount(Integer instanceCount) { this.instanceCount = instanceCount; }
    }

    public static class GraphEdgeDto {
        private String from;
        private String to;
        private String label;
        private String kind;

        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
    }

    public static class ClassDetailDto {
        private String kind;
        private String code;
        private String name;
        private String color;
        private String remark;
        private RefDto parent;
        private List<AttributeDto> attributes = new ArrayList<>();
        private List<RelationDto> relations = new ArrayList<>();
        private List<String> synonyms = new ArrayList<>();
        private List<FullMappingDto> mappings = new ArrayList<>();
        private List<InstanceRefDto> instances = new ArrayList<>();
        private Integer instanceTotal;

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
        public RefDto getParent() { return parent; }
        public void setParent(RefDto parent) { this.parent = parent; }
        public List<AttributeDto> getAttributes() { return attributes; }
        public void setAttributes(List<AttributeDto> attributes) { this.attributes = attributes; }
        public List<RelationDto> getRelations() { return relations; }
        public void setRelations(List<RelationDto> relations) { this.relations = relations; }
        public List<String> getSynonyms() { return synonyms; }
        public void setSynonyms(List<String> synonyms) { this.synonyms = synonyms; }
        public List<FullMappingDto> getMappings() { return mappings; }
        public void setMappings(List<FullMappingDto> mappings) { this.mappings = mappings; }
        public List<InstanceRefDto> getInstances() { return instances; }
        public void setInstances(List<InstanceRefDto> instances) { this.instances = instances; }
        public Integer getInstanceTotal() { return instanceTotal; }
        public void setInstanceTotal(Integer instanceTotal) { this.instanceTotal = instanceTotal; }
    }

    public static class AttributeDto {
        private String code;
        private String name;
        private String dataType;
        private String unit;
        private BigDecimal valueLow;
        private BigDecimal valueHigh;
        private List<MappingRefDto> mappings = new ArrayList<>();

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDataType() { return dataType; }
        public void setDataType(String dataType) { this.dataType = dataType; }
        public String getUnit() { return unit; }
        public void setUnit(String unit) { this.unit = unit; }
        public BigDecimal getValueLow() { return valueLow; }
        public void setValueLow(BigDecimal valueLow) { this.valueLow = valueLow; }
        public BigDecimal getValueHigh() { return valueHigh; }
        public void setValueHigh(BigDecimal valueHigh) { this.valueHigh = valueHigh; }
        public List<MappingRefDto> getMappings() { return mappings; }
        public void setMappings(List<MappingRefDto> mappings) { this.mappings = mappings; }
    }

    public static class MappingRefDto {
        private String table;
        private String column;

        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
        public String getColumn() { return column; }
        public void setColumn(String column) { this.column = column; }
    }

    public static class RelationDto {
        private String code;
        private String name;
        private String direction;
        private RefDto target;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }
        public RefDto getTarget() { return target; }
        public void setTarget(RefDto target) { this.target = target; }
    }

    public static class RefDto {
        private String code;
        private String name;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class FullMappingDto {
        private String table;
        private String column;
        private String kind;
        private String valueExpr;

        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
        public String getColumn() { return column; }
        public void setColumn(String column) { this.column = column; }
        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getValueExpr() { return valueExpr; }
        public void setValueExpr(String valueExpr) { this.valueExpr = valueExpr; }
    }

    public static class InstanceRefDto {
        private String code;
        private String name;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class InstanceDetailDto {
        private String kind;
        private String code;
        private String name;
        private ClassInfoDto classInfo;
        private List<String> synonyms = new ArrayList<>();
        private List<FullMappingDto> mappings = new ArrayList<>();
        private String remark;

        public String getKind() { return kind; }
        public void setKind(String kind) { this.kind = kind; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public ClassInfoDto getClassInfo() { return classInfo; }
        public void setClassInfo(ClassInfoDto classInfo) { this.classInfo = classInfo; }
        public List<String> getSynonyms() { return synonyms; }
        public void setSynonyms(List<String> synonyms) { this.synonyms = synonyms; }
        public List<FullMappingDto> getMappings() { return mappings; }
        public void setMappings(List<FullMappingDto> mappings) { this.mappings = mappings; }
        public String getRemark() { return remark; }
        public void setRemark(String remark) { this.remark = remark; }
    }

    public static class ClassInfoDto {
        private String code;
        private String name;
        private String color;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getColor() { return color; }
        public void setColor(String color) { this.color = color; }
    }
}
