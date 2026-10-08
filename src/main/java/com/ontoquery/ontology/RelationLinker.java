package com.ontoquery.ontology;

import java.util.ArrayList;
import java.util.List;

/**
 * 关系链接：按 Mention 的类做 domain/range 匹配，把实体挂到关系边。
 * 例：E11 属 CLS_DISEASE，经 REL_DIAGNOSIS_OF 反推宿主事件类 CLS_DIAGNOSIS（诊断记录），
 * 再经 REL_DIAGNOSED_WITH（患者 1:N 诊断记录）连到患者锚点；词面距离近的关系优先。
 */
public class RelationLinker {

    /** 锚类：全部路径规划以患者为起点 */
    public static final String ANCHOR_CLASS = "CLS_PERSON";

    private final OntologyModel model;

    public RelationLinker(OntologyModel model) {
        this.model = model;
    }

    /**
     * 为计划中的实体提及建立关系边（数据边 + 语义边），写入 plan.edges。
     */
    public void link(QueryPlan plan) {
        for (Mention mention : plan.getMentions()) {
            if (!mention.isEntity()) {
                continue;
            }
            String classCode = resolveClassCode(mention);
            if (classCode == null) {
                continue;
            }
            for (QueryPlan.RelationEdge edge : constraintChain(classCode, plan.getQuestion(), mention.getBegin())) {
                if (!containsEdge(plan.getEdges(), edge)) {
                    plan.getEdges().add(edge);
                }
            }
        }
    }

    /**
     * 实体类到锚点的完整链：
     * 类为事件类 -> [锚 --数据边--> 事件类]；
     * 类为维度类 -> [锚 --数据边--> 宿主事件类, 宿主事件类 --语义边--> 维度类]。
     */
    public List<QueryPlan.RelationEdge> constraintChain(String classCode, String question, Integer nearPosition) {
        List<QueryPlan.RelationEdge> chain = new ArrayList<>();
        OntologyModel.OntClass target = model.classByCode(classCode);
        if (target == null) {
            return chain;
        }
        OntologyModel.OntRelation dataRelation = dataRelationTo(classCode, question, nearPosition);
        if (dataRelation != null) {
            // 目标本身就是可从锚直达的事件类
            chain.add(edge(dataRelation, false));
            return chain;
        }
        // 维度类：先找语义边（值域=该类）定位宿主事件类，再从锚找数据边
        for (OntologyModel.OntRelation semantic : model.relationsTo(classCode)) {
            OntologyModel.OntRelation host = dataRelationTo(semantic.getDomainClassCode(), question, nearPosition);
            if (host != null) {
                chain.add(edge(host, false));
                chain.add(edge(semantic, true));
                break;
            }
        }
        return chain;
    }

    /** 锚类到某事件类的数据关系（存在 relation 映射才算可执行），词面距离近者优先 */
    private OntologyModel.OntRelation dataRelationTo(String eventClassCode, String question, Integer nearPosition) {
        OntologyModel.OntRelation best = null;
        long bestDistance = Long.MAX_VALUE;
        for (OntologyModel.OntRelation relation : model.relationsFrom(ANCHOR_CLASS)) {
            if (!eventClassCode.equals(relation.getRangeClassCode())) {
                continue;
            }
            if (model.relationMapping(relation.getCode()) == null) {
                continue;
            }
            long distance = keywordDistance(relation, eventClassCode, question, nearPosition);
            // 词面距离仅用于择优排序，不作为存在性门槛：全无词面证据（MAX_VALUE）时保序取首个可执行关系
            if (best == null || distance < bestDistance) {
                bestDistance = distance;
                best = relation;
            }
        }
        return best;
    }

    /** 关系词面在问题中与实体提及的距离（未出现为 Long.MAX_VALUE，稳定排序取首个） */
    private long keywordDistance(OntologyModel.OntRelation relation, String eventClassCode, String question,
            Integer nearPosition) {
        if (question == null || nearPosition == null) {
            return Long.MAX_VALUE;
        }
        long best = Long.MAX_VALUE;
        best = Math.min(best, distanceOf(question, relation.getNameCn(), nearPosition.intValue()));
        OntologyModel.OntClass eventClass = model.classByCode(eventClassCode);
        if (eventClass != null) {
            best = Math.min(best, distanceOf(question, eventClass.getNameCn(), nearPosition.intValue()));
        }
        return best;
    }

    private long distanceOf(String question, String keyword, int position) {
        if (keyword == null || keyword.isEmpty()) {
            return Long.MAX_VALUE;
        }
        long best = Long.MAX_VALUE;
        int idx = question.indexOf(keyword);
        while (idx >= 0) {
            long distance = Math.abs((long) idx - position);
            if (distance < best) {
                best = distance;
            }
            idx = question.indexOf(keyword, idx + 1);
        }
        return best;
    }

    private String resolveClassCode(Mention mention) {
        if (mention.getInstanceCode() != null) {
            OntologyModel.OntInstance instance = model.instanceByCode(mention.getInstanceCode());
            return instance == null ? null : instance.getClassCode();
        }
        return mention.getClassCode();
    }

    private QueryPlan.RelationEdge edge(OntologyModel.OntRelation relation, boolean semantic) {
        OntologyModel.OntClass from = model.classByCode(relation.getDomainClassCode());
        OntologyModel.OntClass to = model.classByCode(relation.getRangeClassCode());
        return new QueryPlan.RelationEdge(relation.getCode(), relation.getNameCn(), relation.getDomainClassCode(),
                from == null ? relation.getDomainClassCode() : from.getNameCn(), relation.getRangeClassCode(),
                to == null ? relation.getRangeClassCode() : to.getNameCn(), semantic);
    }

    private boolean containsEdge(List<QueryPlan.RelationEdge> edges, QueryPlan.RelationEdge candidate) {
        for (QueryPlan.RelationEdge edge : edges) {
            if (edge.getRelationCode().equals(candidate.getRelationCode())
                    && edge.getFromClassCode().equals(candidate.getFromClassCode())
                    && edge.getToClassCode().equals(candidate.getToClassCode())) {
                return true;
            }
        }
        return false;
    }
}
