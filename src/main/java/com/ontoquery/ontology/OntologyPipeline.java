package com.ontoquery.ontology;

import com.ontoquery.ontology.trace.TraceContext;
import com.ontoquery.ontology.trace.TraceContext.TraceStep;
import com.ontoquery.sql.ResultTable;
import com.ontoquery.sql.SafeQueryExecutor;
import com.ontoquery.support.Confidence;
import com.ontoquery.support.Evidence;
import com.ontoquery.support.PipelineResult;
import com.ontoquery.support.RiskFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 本体增强管线编排：ner -> relation -> reasoning -> path -> verify 五步，
 * 每步 TraceContext 计时并写入 TraceItem，SQL 经 SafeQueryExecutor 白名单执行，
 * 产出 PipelineResult(engine=ontology, mode=deterministic)。
 */
@Service
public class OntologyPipeline {

    private static final Logger log = LoggerFactory.getLogger(OntologyPipeline.class);

    private static final String ENGINE = "ontology";
    private static final String MODE = "deterministic";
    private static final String MODE_LABEL = "确定性本体推理";
    private static final String RISK_CODE_ONTOLOGY = "ONTOLOGY_REASONING";

    private final OntologyRepository repository;
    private final SafeQueryExecutor executor;
    private final QueryVerifier verifier;
    private volatile Runtime runtime;

    public OntologyPipeline(OntologyRepository repository, SafeQueryExecutor executor) {
        this.repository = repository;
        this.executor = executor;
        this.verifier = new QueryVerifier(executor);
        this.runtime = buildRuntime(repository.getModel());
    }

    /** 热加载：按 repository 当前模型重建五组件并原子换引用。 */
    public synchronized void rebuildRuntime() {
        this.runtime = buildRuntime(repository.getModel());
    }

    private Runtime buildRuntime(OntologyModel model) {
        return new Runtime(model, new DictionaryNer(model), new RelationLinker(model),
                new ReasoningService(model), new PathPlanner(model), new PathToSqlBuilder(model));
    }

    /** 管线运行时：模型与派生组件整体替换，避免新旧混用。 */
    private static final class Runtime {
        private final OntologyModel model;
        private final DictionaryNer ner;
        private final RelationLinker linker;
        private final ReasoningService reasoningService;
        private final PathPlanner planner;
        private final PathToSqlBuilder sqlBuilder;

        Runtime(OntologyModel model, DictionaryNer ner, RelationLinker linker, ReasoningService reasoningService,
                PathPlanner planner, PathToSqlBuilder sqlBuilder) {
            this.model = model;
            this.ner = ner;
            this.linker = linker;
            this.reasoningService = reasoningService;
            this.planner = planner;
            this.sqlBuilder = sqlBuilder;
        }
    }

    /**
     * 执行五步管线；问题为空直接拒绝。
     */
    public PipelineResult answer(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        long startNano = System.nanoTime();
        TraceContext trace = new TraceContext();
        QueryPlan plan = new QueryPlan();
        plan.setQuestion(question);

        PipelineResult result = new PipelineResult();
        result.setEngine(ENGINE);
        result.setMode(MODE);
        result.setModeLabel(MODE_LABEL);
        result.setQuestion(question);
        result.setRisks(new ArrayList<>());

        String sql = null;
        try {
            Runtime rt = this.runtime;
            stepNer(trace, plan, rt);
            stepRelation(trace, plan, rt);
            stepReasoning(trace, plan, rt);
            sql = stepPath(trace, plan, result, rt);
            stepVerify(trace, plan, result, sql);
        } catch (RuntimeException e) {
            log.error("本体管线执行异常: {}", e.getMessage(), e);
            failRemaining(trace, e.getMessage());
            result.setError("本体管线执行失败: " + e.getMessage());
            if (result.getSqlStatus() == null) {
                result.setSqlStatus("none");
            }
            if (result.getResult() == null) {
                result.setResult(emptyTable());
            }
        }
        appendRisks(result, plan);
        result.setSteps(trace.getSteps());
        result.setTotalElapsedMs(Long.valueOf(Math.max(1L, (System.nanoTime() - startNano) / 1_000_000L)));
        return result;
    }

    // ------------------------------------------------------------ 第一步：词典识别

    private void stepNer(TraceContext trace, QueryPlan plan, Runtime rt) {
        TraceStep step = trace.begin("ner", "词典识别");
        List<Mention> mentions = rt.ner.recognize(plan.getQuestion());
        plan.getMentions().addAll(mentions);
        int entity = 0;
        int time = 0;
        int compare = 0;
        int number = 0;
        int intent = 0;
        for (Mention mention : mentions) {
            switch (mention.getType()) {
                case Mention.TYPE_ENTITY:
                    entity = entity + 1;
                    step.entityChip(mention.getText(), describeEntity(rt, mention));
                    break;
                case Mention.TYPE_TIME:
                    time = time + 1;
                    step.kv("时间窗", mention.getText(), mention.getTimeDays() + " 天");
                    break;
                case Mention.TYPE_COMPARE:
                    compare = compare + 1;
                    step.kv("比较词", mention.getText(), mention.getCompareOperator());
                    break;
                case Mention.TYPE_NUMBER:
                    number = number + 1;
                    step.kv("数值", mention.getText(), ReasoningService.formatNumber(mention.getNumericValue()));
                    break;
                default:
                    intent = intent + 1;
                    step.kv("意图词", mention.getText(), String.valueOf(mention.getIntentKind()));
                    break;
            }
        }
        if (mentions.isEmpty()) {
            trace.end(step, "warn", "未识别到任何本体概念，问题疑似超纲");
            return;
        }
        trace.end(step, "ok", "识别 " + mentions.size() + " 个提及：实体 " + entity + " / 时间 " + time + " / 比较 "
                + compare + " / 数值 " + number + " / 意图 " + intent);
    }

    private String describeEntity(Runtime rt, Mention mention) {
        if (mention.getInstanceCode() != null) {
            OntologyModel.OntInstance instance = rt.model.instanceByCode(mention.getInstanceCode());
            return instance == null ? mention.getInstanceCode()
                    : instance.getCode() + " " + instance.getNameCn();
        }
        if (mention.getClassCode() != null) {
            OntologyModel.OntClass clazz = rt.model.classByCode(mention.getClassCode());
            return clazz == null ? mention.getClassCode() : clazz.getCode() + " " + clazz.getNameCn();
        }
        return mention.getText();
    }

    // ------------------------------------------------------------ 第二步：关系链接

    private void stepRelation(TraceContext trace, QueryPlan plan, Runtime rt) {
        TraceStep step = trace.begin("relation", "关系链接");
        rt.linker.link(plan);
        int dataEdges = 0;
        int semanticEdges = 0;
        for (QueryPlan.RelationEdge edge : plan.getEdges()) {
            step.relationEdge(edge.getFromClassName(), edge.getToClassName(), edge.getRelationName());
            if (edge.isSemantic()) {
                semanticEdges = semanticEdges + 1;
            } else {
                dataEdges = dataEdges + 1;
            }
        }
        if (plan.getEdges().isEmpty()) {
            trace.end(step, "warn", "实体未能挂到任何关系边");
            return;
        }
        trace.end(step, "ok", "建立 " + plan.getEdges().size() + " 条关系边（数据边 " + dataEdges + " / 语义边 "
                + semanticEdges + "）");
    }

    // ------------------------------------------------------------ 第三步：本体推理

    private void stepReasoning(TraceContext trace, QueryPlan plan, Runtime rt) {
        TraceStep step = trace.begin("reasoning", "本体推理");
        rt.reasoningService.apply(plan, step);
        String status = plan.getAmbiguityCount() > 0 || !plan.getWarnings().isEmpty() ? "warn" : "ok";
        trace.end(step, status, "形成 " + plan.getConstraints().size() + " 个约束，意图 "
                + plan.getIntent() + (plan.getAmbiguityCount() > 0
                        ? "，未决歧义 " + plan.getAmbiguityCount() + " 项" : ""));
    }

    // ------------------------------------------------------------ 第四步：路径规划与 SQL 生成

    private String stepPath(TraceContext trace, QueryPlan plan, PipelineResult result, Runtime rt) {
        TraceStep step = trace.begin("path", "路径规划与 SQL 生成");
        PathPlanner.PathPlan paths = rt.planner.plan(plan);
        if (!paths.isOk()) {
            step.text(paths.getError(), "error");
            trace.fail(step, paths.getError());
            result.setSqlStatus("none");
            result.setResult(emptyTable());
            return null;
        }
        PathToSqlBuilder.BuildResult build = rt.sqlBuilder.build(plan, paths);
        if (!build.isOk()) {
            step.text(build.getError(), "error");
            trace.fail(step, build.getError());
            result.setSqlStatus("none");
            result.setResult(emptyTable());
            return null;
        }
        for (PathPlanner.PathSegment segment : paths.getSegments()) {
            step.pathChain(pathChainValues(rt, segment));
        }
        step.sqlPre(build.getSql());
        trace.end(step, "ok", "规划 " + paths.getSegments().size() + " 条路径并生成 EXISTS 模板 SQL");
        return build.getSql();
    }

    /** 路径链：患者 ->(数据边)-> 事件表(别名) ->(语义边)-> 维度实例 */
    private List<String> pathChainValues(Runtime rt, PathPlanner.PathSegment segment) {
        List<String> values = new ArrayList<>();
        for (PathPlanner.PathHop hop : segment.getHops()) {
            values.add(hop.getFromClassName());
            values.add(hop.getRelationName());
            values.add(hop.getToClassName() + "（" + hop.getTableName() + " " + segment.getAlias() + "）");
        }
        QueryPlan.Constraint constraint = segment.getConstraint();
        for (String instanceCode : constraint.getInstanceCodes()) {
            OntologyModel.OntInstance instance = rt.model.instanceByCode(instanceCode);
            if (instance == null) {
                continue;
            }
            OntologyModel.OntClass host = rt.model.classByCode(constraint.getHostClassCode());
            String instanceClass = instance.getClassCode();
            if (host != null && !host.getCode().equals(instanceClass)) {
                OntologyModel.OntClass clazz = rt.model.classByCode(instanceClass);
                for (OntologyModel.OntRelation relation : rt.model.relationsTo(instanceClass)) {
                    if (host.getCode().equals(relation.getDomainClassCode())) {
                        values.add(relation.getNameCn());
                        values.add((clazz == null ? instanceClass : clazz.getNameCn()) + "（"
                                + instance.getCode() + "）");
                        break;
                    }
                }
            }
        }
        if (constraint.hasTime()) {
            values.add("时间挂靠");
            values.add(segment.getTableName() + "." + constraint.getDateColumn());
        }
        return values;
    }

    // ------------------------------------------------------------ 第五步：校验与置信度

    private void stepVerify(TraceContext trace, QueryPlan plan, PipelineResult result, String sql) {
        TraceStep step = trace.begin("verify", "校验与置信度");
        ResultTable table;
        if (sql == null) {
            table = emptyTable();
            result.setSqlStatus("none");
        } else {
            table = executorExecute(sql);
            result.setSql(sql);
            result.setSqlStatus(table.getError() == null ? "ok" : "error");
        }
        result.setResult(table);
        int warningCount = 0;
        if (sql != null && table.getError() == null) {
            for (String warning : verifier.verify(sql)) {
                warningCount = warningCount + 1;
                step.text(warning, "warn");
            }
        }
        if (table.getError() != null) {
            step.text("SQL 执行失败: " + table.getError(), "error");
        } else if (sql != null && Mention.INTENT_COUNT.equals(plan.getIntent()) && !table.getRows().isEmpty()) {
            step.text("计数结果: " + table.getRows().get(0).get(0), "ok");
        } else if (sql != null && Mention.INTENT_LIST.equals(plan.getIntent())) {
            step.text("清单结果: " + table.getRowCount() + " 行", "ok");
        }
        Double avgScore = averageEntityScore(plan);
        Confidence confidence = verifier.computeConfidence(avgScore, entityScoreLabel(plan, avgScore),
                plan.getAmbiguityCount(), Integer.valueOf(warningCount));
        result.setConfidence(confidence);
        step.kv("置信度", confidence.getFormula(), confidence.getScore() + " 分");
        trace.end(step, warningCount > 0 || table.getError() != null ? "warn" : "ok",
                table.getError() == null ? "校验完成，置信度 " + confidence.getScore() : "SQL 执行失败");
    }

    private ResultTable executorExecute(String sql) {
        return executor.execute(sql);
    }

    // ------------------------------------------------------------ 辅助

    private Double averageEntityScore(QueryPlan plan) {
        double total = 0D;
        int count = 0;
        for (Mention mention : plan.getMentions()) {
            if (mention.isEntity() && mention.getScore() != null) {
                total = total + mention.getScore().doubleValue();
                count = count + 1;
            }
        }
        return count == 0 ? null : Double.valueOf(total / count);
    }

    private String entityScoreLabel(QueryPlan plan, Double avgScore) {
        if (avgScore == null) {
            return "无实体提及（按满分计）";
        }
        String viaLabel = "精确实体命中";
        for (Mention mention : plan.getMentions()) {
            if (mention.isEntity() && Mention.VIA_SYNONYM.equals(mention.getVia())) {
                viaLabel = "同义词命中";
                break;
            }
            if (mention.isEntity() && Mention.VIA_CLASS_NAME.equals(mention.getVia())) {
                viaLabel = "类名推断";
            }
        }
        return "实体平均得分 " + java.math.BigDecimal.valueOf(avgScore.doubleValue()).stripTrailingZeros()
                .toPlainString() + "（" + viaLabel + "）";
    }

    private void appendRisks(PipelineResult result, QueryPlan plan) {
        for (String warning : plan.getWarnings()) {
            result.getRisks().add(new RiskFinding(RISK_CODE_ONTOLOGY, "info", "推理提示", warning,
                    new ArrayList<Evidence>()));
        }
    }

    private void failRemaining(TraceContext trace, String message) {
        if (trace.getSteps().isEmpty()) {
            return;
        }
        TraceStep last = trace.getSteps().get(trace.getSteps().size() - 1);
        if (last.getStatus() != null && last.getStatus().equals("ok")) {
            return;
        }
        if (last.getSummary() == null || last.getSummary().isEmpty()) {
            trace.fail(last, message);
        }
    }

    private ResultTable emptyTable() {
        ResultTable table = new ResultTable();
        table.setColumns(new ArrayList<>());
        table.setRows(new ArrayList<>());
        table.setRowCount(Integer.valueOf(0));
        table.setTruncated(Boolean.FALSE);
        return table;
    }
}
