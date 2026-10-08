package com.ontoquery.ontology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * 路径规划：以 CLS_PERSON 为锚，在数据关系图（有 relation 映射的边）上 BFS
 * 规划锚类到各约束宿主类的最短路径，输出路径段（锚类 -> 关系 -> 事件表）。
 * 约束按问题中出现顺序编号别名 t0/t1/t2；宿主即锚类时直接用锚别名 p。
 */
public class PathPlanner {

    private final OntologyModel model;

    public PathPlanner(OntologyModel model) {
        this.model = model;
    }

    /**
     * 规划全部约束路径；任一宿主不可达时整体失败（返回可解释错误）。
     */
    public PathPlan plan(QueryPlan queryPlan) {
        PathPlan result = new PathPlan();
        OntologyModel.OntClass anchor = model.classByCode(RelationLinker.ANCHOR_CLASS);
        if (anchor == null) {
            result.setOk(false);
            result.setError("本体缺少锚类 " + RelationLinker.ANCHOR_CLASS + "，无法规划路径");
            return result;
        }
        OntologyModel.OntMapping anchorMapping = model.classMapping(anchor.getCode());
        if (anchorMapping == null || anchorMapping.getTableName() == null) {
            result.setOk(false);
            result.setError("锚类 " + anchor.getNameCn() + " 缺少物理表映射，无法生成 SQL");
            return result;
        }
        result.setAnchorTable(anchorMapping.getTableName());
        List<QueryPlan.Constraint> ordered = sortedByOrderKey(queryPlan.getConstraints());
        if (ordered.isEmpty()) {
            result.setOk(true);
            return result;
        }
        List<PathSegment> segments = new ArrayList<>(ordered.size());
        List<String> unreachable = new ArrayList<>();
        int index = 0;
        for (QueryPlan.Constraint constraint : ordered) {
            PathSegment segment = new PathSegment();
            segment.setConstraint(constraint);
            segment.setOrdinal(index);
            OntologyModel.OntClass host = model.classByCode(constraint.getHostClassCode());
            String hostName = host == null ? String.valueOf(constraint.getHostClassCode()) : host.getNameCn();
            if (host == null) {
                unreachable.add(hostName);
                index = index + 1;
                continue;
            }
            if (RelationLinker.ANCHOR_CLASS.equals(host.getCode())) {
                segment.setAlias("p");
                segment.setTableName(anchorMapping.getTableName());
                segments.add(segment);
                index = index + 1;
                continue;
            }
            List<PathHop> hops = bfs(anchor.getCode(), host.getCode());
            if (hops == null) {
                unreachable.add(hostName);
                index = index + 1;
                continue;
            }
            PathHop last = hops.get(hops.size() - 1);
            segment.setAlias("t" + index);
            segment.setTableName(last.getTableName());
            segment.setJoinCondition(last.getJoinCondition());
            segment.setHops(hops);
            segments.add(segment);
            index = index + 1;
        }
        if (!unreachable.isEmpty()) {
            result.setOk(false);
            result.setError("无法从锚类规划到: " + String.join("、", unreachable)
                    + "（数据关系图不可达，问题超出本体覆盖范围）");
            return result;
        }
        result.setSegments(segments);
        result.setOk(true);
        return result;
    }

    /** 约束按触发词在问题中的位置排序（null 排最后，保持相对顺序稳定） */
    private List<QueryPlan.Constraint> sortedByOrderKey(List<QueryPlan.Constraint> constraints) {
        List<QueryPlan.Constraint> sorted = new ArrayList<>(constraints);
        sorted.sort((a, b) -> {
            int keyA = a.getOrderKey() == null ? Integer.MAX_VALUE : a.getOrderKey().intValue();
            int keyB = b.getOrderKey() == null ? Integer.MAX_VALUE : b.getOrderKey().intValue();
            return Integer.compare(keyA, keyB);
        });
        return sorted;
    }

    /** BFS 最短路径；返回锚->目标的跳列表，不可达返回 null */
    private List<PathHop> bfs(String fromCode, String toCode) {
        Queue<String> queue = new LinkedList<>();
        Set<String> visited = new HashSet<>();
        List<GraphEdge> graph = dataEdges();
        Map<String, BfsNode> parent = new HashMap<>();
        queue.add(fromCode);
        visited.add(fromCode);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (toCode.equals(current)) {
                return buildPath(parent, toCode);
            }
            for (GraphEdge edge : graph) {
                String next = edge.traverse(current);
                if (next == null || !visited.add(next)) {
                    continue;
                }
                parent.put(next, new BfsNode(current, edge));
                queue.add(next);
            }
        }
        return null;
    }

    private List<PathHop> buildPath(Map<String, BfsNode> parent, String toCode) {
        LinkedList<PathHop> hops = new LinkedList<>();
        String current = toCode;
        while (parent.containsKey(current)) {
            BfsNode node = parent.get(current);
            OntologyModel.OntRelation relation = model.relationByCode(node.edge.relationCode);
            OntologyModel.OntMapping mapping = model.relationMapping(node.edge.relationCode);
            PathHop hop = new PathHop();
            hop.setRelationCode(node.edge.relationCode);
            hop.setRelationName(relation == null ? node.edge.relationCode : relation.getNameCn());
            hop.setFromClassCode(node.from);
            hop.setToClassCode(current);
            OntologyModel.OntClass from = model.classByCode(node.from);
            OntologyModel.OntClass to = model.classByCode(current);
            hop.setFromClassName(from == null ? node.from : from.getNameCn());
            hop.setToClassName(to == null ? current : to.getNameCn());
            hop.setTableName(mapping == null ? null : mapping.getTableName());
            hop.setJoinCondition(mapping == null ? null : mapping.getJoinCondition());
            hops.addFirst(hop);
            current = node.from;
        }
        return hops;
    }

    /** 数据边（有 relation 映射的关系），双向可遍历 */
    private List<GraphEdge> dataEdges() {
        List<GraphEdge> edges = new ArrayList<>();
        for (OntologyModel.OntRelation relation : model.allRelations()) {
            OntologyModel.OntMapping mapping = model.relationMapping(relation.getCode());
            if (mapping == null) {
                continue;
            }
            edges.add(new GraphEdge(relation.getDomainClassCode(), relation.getRangeClassCode(),
                    relation.getCode()));
        }
        return edges;
    }

    /** 图边：无向遍历（domain<->range） */
    private static class GraphEdge {
        private final String domain;
        private final String range;
        private final String relationCode;

        GraphEdge(String domain, String range, String relationCode) {
            this.domain = domain;
            this.range = range;
            this.relationCode = relationCode;
        }

        String traverse(String node) {
            if (domain.equals(node)) {
                return range;
            }
            if (range.equals(node)) {
                return domain;
            }
            return null;
        }
    }

    private static class BfsNode {
        private final String from;
        private final GraphEdge edge;

        BfsNode(String from, GraphEdge edge) {
            this.from = from;
            this.edge = edge;
        }
    }

    /** 规划结果 */
    public static class PathPlan {
        private boolean ok;
        private String error;
        private String anchorTable;
        private List<PathSegment> segments = new ArrayList<>();

        public boolean isOk() { return ok; }
        public void setOk(boolean ok) { this.ok = ok; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public String getAnchorTable() { return anchorTable; }
        public void setAnchorTable(String anchorTable) { this.anchorTable = anchorTable; }
        public List<PathSegment> getSegments() { return segments; }
        public void setSegments(List<PathSegment> segments) { this.segments = segments; }
    }

    /** 路径段：一个约束的锚->事件表路径与别名 */
    public static class PathSegment {
        private QueryPlan.Constraint constraint;
        private int ordinal;
        private String alias;
        private String tableName;
        private String joinCondition;
        private List<PathHop> hops = new ArrayList<>();

        public QueryPlan.Constraint getConstraint() { return constraint; }
        public void setConstraint(QueryPlan.Constraint constraint) { this.constraint = constraint; }
        public int getOrdinal() { return ordinal; }
        public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
        public String getAlias() { return alias; }
        public void setAlias(String alias) { this.alias = alias; }
        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        public String getJoinCondition() { return joinCondition; }
        public void setJoinCondition(String joinCondition) { this.joinCondition = joinCondition; }
        public List<PathHop> getHops() { return hops; }
        public void setHops(List<PathHop> hops) { this.hops = hops; }
    }

    /** 路径跳：锚类 ->(关系)-> 事件表 */
    public static class PathHop {
        private String relationCode;
        private String relationName;
        private String fromClassCode;
        private String fromClassName;
        private String toClassCode;
        private String toClassName;
        private String tableName;
        private String joinCondition;

        public String getRelationCode() { return relationCode; }
        public void setRelationCode(String relationCode) { this.relationCode = relationCode; }
        public String getRelationName() { return relationName; }
        public void setRelationName(String relationName) { this.relationName = relationName; }
        public String getFromClassCode() { return fromClassCode; }
        public void setFromClassCode(String fromClassCode) { this.fromClassCode = fromClassCode; }
        public String getFromClassName() { return fromClassName; }
        public void setFromClassName(String fromClassName) { this.fromClassName = fromClassName; }
        public String getToClassCode() { return toClassCode; }
        public void setToClassCode(String toClassCode) { this.toClassCode = toClassCode; }
        public String getToClassName() { return toClassName; }
        public void setToClassName(String toClassName) { this.toClassName = toClassName; }
        public String getTableName() { return tableName; }
        public void setTableName(String tableName) { this.tableName = tableName; }
        public String getJoinCondition() { return joinCondition; }
        public void setJoinCondition(String joinCondition) { this.joinCondition = joinCondition; }
    }
}
