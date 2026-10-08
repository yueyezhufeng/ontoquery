package com.ontoquery.ontology;

import java.util.ArrayList;
import java.util.List;

/**
 * 路径段 + 约束 -> EXISTS 模板 SQL。
 * 规则：锚 FROM dim_patient p；每个约束一个 EXISTS 子查询，别名 t0/t1/t2 按约束出现顺序；
 * 实例谓词用 ont_mapping.value_expr 替换 {t}；时间挂靠 DATE_SUB(CURDATE(), INTERVAL n DAY)；
 * 数值谓词挂属性列；意图四类：计数 COUNT(DISTINCT) / 分组 GROUP BY+COUNT / TopN ORDER BY+LIMIT / AVG。
 */
public class PathToSqlBuilder {

    private static final String ANCHOR_ALIAS = "p";
    private static final String COUNT_COLUMN = "patient_count";
    /** 分组/TopN 子查询内的计数列别名（黄金 SQL 同构） */
    private static final String COUNT_ALIAS = "cnt";
    /** "WHERE EXISTS (" 的宽度：内层 WHERE 对齐到此列 */
    private static final int EXISTS_INNER_INDENT = 14;
    /** 谓词 AND 对齐列（内层 WHERE 再进 2 格） */
    private static final int EXISTS_PREDICATE_INDENT = 16;
    /** 分组意图缺省分组列（患者维度表 region 列） */
    private static final String DEFAULT_GROUP_COLUMN = "region";
    private static final long DEFAULT_LIMIT = 10L;

    private final OntologyModel model;

    public PathToSqlBuilder(OntologyModel model) {
        this.model = model;
    }

    /**
     * 生成 SQL；无法满足意图前提（如 TopN/均值无数值条件）时返回可解释错误。
     */
    public BuildResult build(QueryPlan plan, PathPlanner.PathPlan paths) {
        BuildResult result = new BuildResult();
        if (!paths.isOk()) {
            result.setOk(false);
            result.setError(paths.getError());
            return result;
        }
        switch (plan.getIntent() == null ? Mention.INTENT_COUNT : plan.getIntent()) {
            case Mention.INTENT_AVG:
                return buildAvg(plan, paths);
            case Mention.INTENT_TOP_N:
                return buildTopN(plan, paths);
            case Mention.INTENT_GROUP:
                return buildGroup(plan, paths);
            case Mention.INTENT_LIST:
                return buildList(plan, paths);
            default:
                return buildCount(plan, paths);
        }
    }

    // ------------------------------------------------------------ 意图：实例清单

    /**
     * 实例清单：列举目标类宿主表为主表 DISTINCT 展示列；
     * 行过滤谓词（选择性闭包实例 + 时间/数值/枚举）直接挂主表行，
     * 其余约束经患者锚 EXISTS 关联（清单表行 -> 患者 -> 各事件约束）。
     */
    private BuildResult buildList(QueryPlan plan, PathPlanner.PathPlan paths) {
        if (plan.getListHostClassCode() == null || plan.getListColumns() == null
                || plan.getListColumns().isEmpty()) {
            return fail("列举意图需要可列举的目标类（如 药品/诊断），当前问题未识别到，无法生成清单 SQL");
        }
        OntologyModel.OntMapping classMapping = model.classMapping(plan.getListHostClassCode());
        if (classMapping == null || classMapping.getTableName() == null) {
            return fail("列举目标类缺少物理映射，无法生成清单 SQL");
        }
        String alias = "t";
        String table = classMapping.getTableName();
        List<String> columnRefs = new ArrayList<>(plan.getListColumns().size());
        for (String column : plan.getListColumns()) {
            columnRefs.add(alias + "." + column);
        }
        List<String> pieces = new ArrayList<>();
        if (!paths.getSegments().isEmpty()) {
            pieces.add(anchorExists(paths, alias));
        }
        if (plan.getListRowConstraint() != null) {
            pieces.addAll(constraintPredicates(plan.getListRowConstraint(), alias));
        }
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT DISTINCT ").append(String.join(", ", columnRefs));
        sql.append('\n').append("FROM ").append(table).append(' ').append(alias);
        if (!pieces.isEmpty()) {
            sql.append('\n').append("WHERE ").append(String.join("\n  AND ", pieces));
        }
        sql.append('\n').append("ORDER BY ").append(columnRefs.get(0));
        return ok(sql.toString());
    }

    /** 患者锚 EXISTS 包裹：清单主表行归属的患者满足其余约束段（内层复用 existsBlock，整体缩进一级） */
    private String anchorExists(PathPlanner.PathPlan paths, String listAlias) {
        StringBuilder block = new StringBuilder();
        block.append("EXISTS (SELECT 1 FROM ").append(paths.getAnchorTable()).append(' ').append(ANCHOR_ALIAS)
                .append('\n');
        block.append(" ".repeat(EXISTS_INNER_INDENT)).append("WHERE ").append(ANCHOR_ALIAS).append(".patient_id = ")
                .append(listAlias).append(".patient_id");
        for (PathPlanner.PathSegment segment : paths.getSegments()) {
            block.append('\n').append(" ".repeat(EXISTS_PREDICATE_INDENT)).append("AND ")
                    .append(existsBlock(segment).replace("\n", "\n    "));
        }
        block.append(')');
        return block.toString();
    }

    // ------------------------------------------------------------ 意图：计数

    private BuildResult buildCount(QueryPlan plan, PathPlanner.PathPlan paths) {
        if (plan.getRecordCountClassCode() != null) {
            return buildRecordCount(plan);
        }
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(DISTINCT ").append(ANCHOR_ALIAS).append(".patient_id) AS ").append(COUNT_COLUMN);
        sql.append('\n').append("FROM ").append(paths.getAnchorTable()).append(' ').append(ANCHOR_ALIAS);
        appendWhereClauses(sql, paths, null);
        return ok(sql.toString());
    }

    /** 记录数模式：直接数事件表行数（SELECT COUNT(*) FROM 事件表 [WHERE 谓词]） */
    private BuildResult buildRecordCount(QueryPlan plan) {
        OntologyModel.OntMapping classMapping = model.classMapping(plan.getRecordCountClassCode());
        if (classMapping == null || classMapping.getTableName() == null) {
            return fail("记录数意图缺少事件类物理映射，无法生成 SQL");
        }
        String table = classMapping.getTableName();
        QueryPlan.Constraint constraint = constraintOfHost(plan, plan.getRecordCountClassCode());
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT COUNT(*) AS record_count\nFROM ").append(table);
        if (constraint != null) {
            List<String> predicates = constraintPredicates(constraint, table);
            if (!predicates.isEmpty()) {
                sql.append('\n').append("WHERE ").append(String.join("\n  AND ", predicates));
            }
        }
        return ok(sql.toString());
    }

    // ------------------------------------------------------------ 意图：分组计数

    /**
     * 分组计数：事件表分组列按记录数统计（外层 GROUP_CONCAT 折叠为标量）；
     * 否则按锚类分组列（或分组表达式，如年龄分段 CASE 桶）统计患者数。
     */
    private BuildResult buildGroup(QueryPlan plan, PathPlanner.PathPlan paths) {
        if (plan.getGroupHostClassCode() != null) {
            return buildGroupByEventColumn(plan);
        }
        String groupColumn = plan.getGroupColumn() == null ? DEFAULT_GROUP_COLUMN : plan.getGroupColumn();
        String selectExpr = ANCHOR_ALIAS + "." + groupColumn;
        String groupBy = selectExpr;
        if (plan.getGroupExpression() != null) {
            selectExpr = plan.getGroupExpression().replace("{a}", ANCHOR_ALIAS);
            groupBy = groupColumn;
        }
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT CONCAT('[', GROUP_CONCAT(CONCAT(").append(groupColumn).append(", ':', ")
                .append(COUNT_ALIAS).append(") ORDER BY ").append(groupColumn)
                .append(" SEPARATOR ', '), ']') AS group_result");
        sql.append('\n').append("FROM (SELECT ").append(selectExpr).append(" AS ").append(groupColumn);
        sql.append(", COUNT(DISTINCT ").append(ANCHOR_ALIAS).append(".patient_id) AS ").append(COUNT_ALIAS);
        sql.append('\n').append("FROM ").append(paths.getAnchorTable()).append(' ').append(ANCHOR_ALIAS);
        appendWhereClauses(sql, paths, null);
        sql.append('\n').append("GROUP BY ").append(groupBy).append(") sub");
        return ok(sql.toString());
    }

    /** 事件表分组：按事件表列分组数记录数（如 各名称写法 / 按是否异常 / 各药品分别开了多少条） */
    private BuildResult buildGroupByEventColumn(QueryPlan plan) {
        OntologyModel.OntMapping classMapping = model.classMapping(plan.getGroupHostClassCode());
        if (classMapping == null || classMapping.getTableName() == null || plan.getGroupColumn() == null) {
            return fail("事件表分组缺少物理映射，无法生成分组 SQL");
        }
        String table = classMapping.getTableName();
        String column = plan.getGroupColumn();
        List<String> predicates = eventTablePredicates(plan, plan.getGroupHostClassCode(), table);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT CONCAT('[', GROUP_CONCAT(CONCAT(").append(column).append(", ':', ").append(COUNT_ALIAS)
                .append(") ORDER BY ").append(COUNT_ALIAS).append(" DESC, ").append(column)
                .append(" SEPARATOR ', '), ']') AS group_result");
        sql.append('\n').append("FROM (SELECT ").append(column).append(", COUNT(*) AS ").append(COUNT_ALIAS);
        sql.append('\n').append("FROM ").append(table);
        if (!predicates.isEmpty()) {
            sql.append('\n').append("WHERE ").append(String.join("\n  AND ", predicates));
        }
        sql.append('\n').append("GROUP BY ").append(column).append(") sub");
        return ok(sql.toString());
    }

    /**
     * 事件表单表模板的谓词集：否定约束输出行级 NOT 谓词；
     * 存在否定约束时，同宿主肯定约束改写为患者队列相关 EXISTS（如「E11 患者的其他诊断（不含糖尿病类）」）。
     */
    private List<String> eventTablePredicates(QueryPlan plan, String hostClassCode, String table) {
        List<QueryPlan.Constraint> hostConstraints = new ArrayList<>();
        boolean hasNegative = false;
        for (QueryPlan.Constraint constraint : plan.getConstraints()) {
            if (hostClassCode.equals(constraint.getHostClassCode())) {
                hostConstraints.add(constraint);
                hasNegative = hasNegative || constraint.isNegative();
            }
        }
        List<String> predicates = new ArrayList<>();
        int cohortSeq = 0;
        for (QueryPlan.Constraint constraint : hostConstraints) {
            if (constraint.isNegative()) {
                predicates.addAll(constraintPredicates(constraint, ""));
            } else if (hasNegative) {
                String alias = "c" + cohortSeq;
                cohortSeq = cohortSeq + 1;
                predicates.add(cohortExists(constraint, table, alias));
            } else {
                predicates.addAll(constraintPredicates(constraint, ""));
            }
        }
        return predicates;
    }

    /** 患者队列相关 EXISTS：事件表行所属患者满足队列约束（如 患者被诊断为 E11） */
    private String cohortExists(QueryPlan.Constraint constraint, String table, String alias) {
        StringBuilder sql = new StringBuilder();
        sql.append("EXISTS (SELECT 1 FROM ").append(table).append(' ').append(alias);
        sql.append('\n').append("          WHERE ").append(alias).append(".patient_id = ").append(table)
                .append(".patient_id");
        List<String> inner = constraintPredicates(constraint, alias);
        for (String predicate : inner) {
            sql.append('\n').append("            AND ").append(predicate);
        }
        sql.append(')');
        return sql.toString();
    }

    // ------------------------------------------------------------ 意图：TopN

    private BuildResult buildTopN(QueryPlan plan, PathPlanner.PathPlan paths) {
        if (plan.getTopNGroupClassCode() != null) {
            return buildTopNByCount(plan);
        }
        PathPlanner.PathSegment valueSegment = valueSegment(paths);
        if (valueSegment == null) {
            return fail("TopN 需要可排序的数值条件（如 HbA1c 大于 7），当前问题缺少度量约束，无法生成排名 SQL");
        }
        String valueColumn = measureColumnOf(valueSegment);
        String valueExpr = valueSegment.getAlias() + "." + valueColumn;
        long limit = plan.getTopN() == null ? DEFAULT_LIMIT : plan.getTopN().longValue();
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(ANCHOR_ALIAS).append(".patient_id AS patient_id");
        sql.append(", ").append(valueExpr).append(" AS ").append(valueColumn);
        sql.append('\n').append("FROM ").append(paths.getAnchorTable()).append(' ').append(ANCHOR_ALIAS);
        sql.append('\n').append("JOIN ").append(valueSegment.getTableName()).append(' ')
                .append(valueSegment.getAlias());
        sql.append(" ON ").append(substitute(valueSegment.getJoinCondition(), valueSegment.getAlias(),
                ANCHOR_ALIAS));
        appendWhereClauses(sql, paths, valueSegment);
        sql.append('\n').append("ORDER BY ").append(valueExpr).append(" DESC");
        sql.append('\n').append("LIMIT ").append(limit);
        return ok(sql.toString());
    }

    /** 按计数 TopN：事件表分组列按记录数排名（如 就诊量最高的前五科室；含否定排除时改写队列 EXISTS） */
    private BuildResult buildTopNByCount(QueryPlan plan) {
        OntologyModel.OntMapping classMapping = model.classMapping(plan.getTopNGroupClassCode());
        if (classMapping == null || classMapping.getTableName() == null
                || plan.getTopNGroupColumn() == null) {
            return fail("按计数 TopN 缺少事件类分组列映射，无法生成排名 SQL");
        }
        String table = classMapping.getTableName();
        String column = plan.getTopNGroupColumn();
        long limit = plan.getTopN() == null ? DEFAULT_LIMIT : plan.getTopN().longValue();
        List<String> predicates = eventTablePredicates(plan, plan.getTopNGroupClassCode(), table);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT CONCAT('[', GROUP_CONCAT(CONCAT(").append(column).append(", ':', ").append(COUNT_ALIAS)
                .append(") ORDER BY ").append(COUNT_ALIAS).append(" DESC, ").append(column)
                .append(" SEPARATOR ', '), ']') AS top_result");
        sql.append('\n').append("FROM (SELECT ").append(column).append(", COUNT(*) AS ").append(COUNT_ALIAS);
        sql.append('\n').append("FROM ").append(table);
        if (!predicates.isEmpty()) {
            sql.append('\n').append("WHERE ").append(String.join("\n  AND ", predicates));
        }
        sql.append('\n').append("GROUP BY ").append(column);
        sql.append('\n').append("ORDER BY ").append(COUNT_ALIAS).append(" DESC, ").append(column);
        sql.append('\n').append("LIMIT ").append(limit).append(") sub");
        return ok(sql.toString());
    }

    // ------------------------------------------------------------ 意图：均值

    private BuildResult buildAvg(QueryPlan plan, PathPlanner.PathPlan paths) {
        PathPlanner.PathSegment valueSegment = valueSegment(paths);
        if (valueSegment == null) {
            return fail("均值问题需要可度量的数值条件（如 HbA1c 大于 7），当前问题缺少度量约束，无法生成均值 SQL");
        }
        String column = measureColumnOf(valueSegment);
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ROUND(AVG(").append(valueSegment.getAlias()).append('.').append(column)
                .append("), 2) AS avg_").append(column);
        sql.append('\n').append("FROM ").append(paths.getAnchorTable()).append(' ').append(ANCHOR_ALIAS);
        sql.append('\n').append("JOIN ").append(valueSegment.getTableName()).append(' ')
                .append(valueSegment.getAlias());
        sql.append(" ON ").append(substitute(valueSegment.getJoinCondition(), valueSegment.getAlias(),
                ANCHOR_ALIAS));
        appendWhereClauses(sql, paths, valueSegment);
        return ok(sql.toString());
    }

    // ------------------------------------------------------------ WHERE 组装

    /**
     * 组装 WHERE：普通约束为 EXISTS 块；joinedSegment（TopN/均值主表）为 JOIN 后的顶层谓词；
     * 锚类直挂约束直接写锚别名谓词。无任何条件时不输出 WHERE。
     */
    private void appendWhereClauses(StringBuilder sql, PathPlanner.PathPlan paths,
            PathPlanner.PathSegment joinedSegment) {
        List<String> pieces = new ArrayList<>();
        for (PathPlanner.PathSegment segment : paths.getSegments()) {
            if (joinedSegment == segment) {
                List<String> predicates = predicatesOf(segment);
                if (!predicates.isEmpty()) {
                    pieces.add(String.join("\n  AND ", predicates));
                }
                continue;
            }
            if (ANCHOR_ALIAS.equals(segment.getAlias())) {
                List<String> predicates = predicatesOf(segment);
                if (!predicates.isEmpty()) {
                    pieces.add(String.join("\n  AND ", predicates));
                }
                continue;
            }
            pieces.add(existsBlock(segment));
        }
        if (pieces.isEmpty()) {
            return;
        }
        boolean first = true;
        for (String piece : pieces) {
            if (first) {
                sql.append('\n').append("WHERE ").append(piece);
                first = false;
            } else {
                sql.append('\n').append("  AND ").append(piece);
            }
        }
    }

    /**
     * 单约束 EXISTS 块（多跳路径嵌套 EXISTS，每跳 {t}=本跳别名、{a}=上一跳别名）。
     */
    private String existsBlock(PathPlanner.PathSegment segment) {
        return existsBlock(segment, segment.getHops().size() - 1, segment.getAlias());
    }

    private String existsBlock(PathPlanner.PathSegment segment, int hopIndex, String alias) {
        PathPlanner.PathHop hop = segment.getHops().get(hopIndex);
        String previousAlias = hopIndex == 0 ? ANCHOR_ALIAS : segment.getAlias() + "_" + (hopIndex - 1);
        String join = substitute(hop.getJoinCondition(), alias, previousAlias);
        StringBuilder block = new StringBuilder();
        block.append("EXISTS (SELECT 1 FROM ").append(hop.getTableName()).append(' ').append(alias).append('\n');
        block.append(" ".repeat(EXISTS_INNER_INDENT)).append("WHERE ").append(join);
        if (hopIndex == segment.getHops().size() - 1) {
            for (String predicate : predicatesOf(segment)) {
                block.append('\n').append(" ".repeat(EXISTS_PREDICATE_INDENT)).append("AND ").append(predicate);
            }
        } else {
            String nested = existsBlock(segment, hopIndex + 1, segment.getAlias() + "_" + (hopIndex + 1));
            block.append('\n').append(" ".repeat(EXISTS_PREDICATE_INDENT)).append("AND ").append(nested);
        }
        block.append(')');
        return block.toString();
    }

    /** 谓词顺序：实例过滤 -> 时间挂靠 -> 数值比较 -> 附加谓词（与黄金 SQL 一致） */
    private List<String> predicatesOf(PathPlanner.PathSegment segment) {
        return constraintPredicates(segment.getConstraint(), segment.getAlias());
    }

    /** 约束谓词：实例过滤 -> 时间挂靠 -> 数值比较 -> 附加谓词（{t} 替换为别名；空别名输出裸列名） */
    private List<String> constraintPredicates(QueryPlan.Constraint constraint, String alias) {
        List<String> predicates = new ArrayList<>();
        if (!constraint.getInstanceCodes().isEmpty()) {
            List<String> instancePredicates = new ArrayList<>(constraint.getInstanceCodes().size());
            for (String instanceCode : constraint.getInstanceCodes()) {
                OntologyModel.OntMapping mapping = model.instanceMapping(instanceCode);
                if (mapping != null && mapping.getValueExpr() != null) {
                    instancePredicates.add(substituteColumn(mapping.getValueExpr(), alias));
                }
            }
            if (instancePredicates.size() == 1) {
                predicates.add(constraint.isNegative()
                        ? "NOT (" + instancePredicates.get(0) + ")" : instancePredicates.get(0));
            } else if (!instancePredicates.isEmpty()) {
                String joined = "(" + String.join(" OR ", instancePredicates) + ")";
                predicates.add(constraint.isNegative() ? "NOT " + joined : joined);
            }
        }
        if (constraint.hasTime()) {
            predicates.add(columnRef(constraint.getDateColumn(), alias)
                    + " >= DATE_SUB(CURDATE(), INTERVAL " + constraint.getTimeDays() + " DAY)");
        }
        if (constraint.hasValue()) {
            predicates.add(columnRef(constraint.getValueColumn(), alias) + " "
                    + constraint.getValueOperator() + " " + ReasoningService.formatNumber(constraint.getValueNumber()));
        }
        for (String extra : constraint.getExtraPredicates()) {
            predicates.add(substituteColumn(extra, alias));
        }
        return predicates;
    }

    /** 模板列引用替换：{t}.col -> 别名.col；空别名 -> 裸列名（单表无别名场景） */
    private String substituteColumn(String template, String alias) {
        if (alias.isEmpty()) {
            return template.replace("{t}.", "");
        }
        return template.replace("{t}", alias);
    }

    /** 列引用：空别名输出裸列名，否则 输出 别名.列名 */
    private String columnRef(String column, String alias) {
        return alias.isEmpty() ? column : alias + "." + column;
    }

    private QueryPlan.Constraint constraintOfHost(QueryPlan plan, String hostClassCode) {
        for (QueryPlan.Constraint constraint : plan.getConstraints()) {
            if (hostClassCode.equals(constraint.getHostClassCode())) {
                return constraint;
            }
        }
        return null;
    }

    /** join_condition 占位替换：{t}=事件表别名，{a}=锚/上跳别名 */
    private String substitute(String joinCondition, String eventAlias, String anchorAlias) {
        if (joinCondition == null) {
            return eventAlias + ".patient_id = " + anchorAlias + ".patient_id";
        }
        return joinCondition.replace("{t}", eventAlias).replace("{a}", anchorAlias);
    }

    /**
     * TopN/均值主表：优先取含数值条件的段；否则取最后一个其宿主类有 decimal 属性映射的段
     * （如「平均HbA1c」未写比较词时按 result_value 度量）。
     */
    private PathPlanner.PathSegment valueSegment(PathPlanner.PathPlan paths) {
        for (PathPlanner.PathSegment segment : paths.getSegments()) {
            if (segment.getConstraint().hasValue() && segment.getHops().size() == 1) {
                return segment;
            }
        }
        PathPlanner.PathSegment fallback = null;
        for (PathPlanner.PathSegment segment : paths.getSegments()) {
            if (!segment.getConstraint().getInstanceCodes().isEmpty() && segment.getHops().size() == 1
                    && decimalColumnOf(segment.getConstraint().getHostClassCode()) != null) {
                fallback = segment;
            }
        }
        return fallback;
    }

    /** 排序/聚合列：约束自带数值列，或宿主类首个 decimal 属性映射列 */
    private String measureColumnOf(PathPlanner.PathSegment segment) {
        if (segment.getConstraint().hasValue()) {
            return segment.getConstraint().getValueColumn();
        }
        return decimalColumnOf(segment.getConstraint().getHostClassCode());
    }

    private String decimalColumnOf(String classCode) {
        for (OntologyModel.OntAttribute attribute : model.attributesOf(classCode)) {
            if (!"decimal".equals(attribute.getDataType())) {
                continue;
            }
            OntologyModel.OntMapping mapping = model.attributeMapping(attribute.getCode());
            if (mapping == null) {
                continue;
            }
            if (mapping.getColumnName() != null) {
                return mapping.getColumnName();
            }
            if (mapping.getValueExpr() != null) {
                return mapping.getValueExpr().replace("{t}.", "");
            }
        }
        return null;
    }

    private BuildResult ok(String sql) {
        BuildResult result = new BuildResult();
        result.setOk(true);
        result.setSql(sql);
        return result;
    }

    private BuildResult fail(String error) {
        BuildResult result = new BuildResult();
        result.setOk(false);
        result.setError(error);
        return result;
    }

    /** 生成结果 */
    public static class BuildResult {
        private boolean ok;
        private String sql;
        private String error;

        public boolean isOk() { return ok; }
        public void setOk(boolean ok) { this.ok = ok; }
        public String getSql() { return sql; }
        public void setSql(String sql) { this.sql = sql; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
    }
}
