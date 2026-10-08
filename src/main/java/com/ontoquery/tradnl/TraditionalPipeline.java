package com.ontoquery.tradnl;

import com.ontoquery.llm.LlmClient;
import com.ontoquery.llm.MockLlmGenerator;
import com.ontoquery.llm.SqlExtractor;
import com.ontoquery.ontology.trace.TraceContext;
import com.ontoquery.ontology.trace.TraceContext.TraceStep;
import com.ontoquery.risk.SqlRiskLinter;
import com.ontoquery.sql.ResultTable;
import com.ontoquery.sql.SafeQueryExecutor;
import com.ontoquery.support.Confidence;
import com.ontoquery.support.ConfidenceFactor;
import com.ontoquery.support.PipelineResult;
import com.ontoquery.support.RiskFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 传统 NL2SQL 管线（五步编排）：
 * 模式读取 -> 列名匹配 -> 关联推断 -> LLM 端到端生成（含一次纠错重试）-> 风险检测。
 * LLM 不可用时降级 Mock 生成器，二者走完全相同下游，mode 字段如实标注。
 *
 * @author 月夜烛峰
 */
@Service
public class TraditionalPipeline {

    private static final Logger log = LoggerFactory.getLogger(TraditionalPipeline.class);

    private static final String ENGINE = "traditional";
    private static final String RETRY_HINT =
            "\n\n（上次输出未能抽取到 SELECT 语句，请严格只输出一条 MySQL 8 的 SELECT，不要任何其他文字）";

    /** 置信度公式参数 */
    private static final int DEDUCT_HIGH = 10;
    private static final int DEDUCT_MEDIUM = 6;
    private static final int DEDUCT_LOW = 3;
    private static final int CONFIDENCE_FLOOR = 30;
    private static final int CONFIDENCE_CEIL = 100;

    /** 原始输出在轨迹中的最大展示长度 */
    private static final int RAW_DISPLAY_LIMIT = 600;

    private final SchemaLoader schemaLoader;
    private final SchemaLinker schemaLinker;
    private final JoinInferer joinInferer;
    private final LlmClient llmClient;
    private final MockLlmGenerator mockGenerator;
    private final SafeQueryExecutor safeQueryExecutor;
    private final SqlRiskLinter riskLinter;

    public TraditionalPipeline(SchemaLoader schemaLoader, SchemaLinker schemaLinker, JoinInferer joinInferer,
                               LlmClient llmClient, MockLlmGenerator mockGenerator,
                               SafeQueryExecutor safeQueryExecutor, SqlRiskLinter riskLinter) {
        this.schemaLoader = schemaLoader;
        this.schemaLinker = schemaLinker;
        this.joinInferer = joinInferer;
        this.llmClient = llmClient;
        this.mockGenerator = mockGenerator;
        this.safeQueryExecutor = safeQueryExecutor;
        this.riskLinter = riskLinter;
    }

    public PipelineResult answer(String question) {
        return answer(question, false);
    }

    /** forceMock=true 时跳过 LLM 直接走确定性 Mock（基准快速模式） */
    public PipelineResult answer(String question, boolean forceMock) {
        long startTotal = System.nanoTime();
        TraceContext ctx = new TraceContext();
        PipelineResult out = new PipelineResult();
        out.setEngine(ENGINE);
        out.setQuestion(question);
        try {
            run(question, forceMock, ctx, out);
        } catch (RuntimeException e) {
            log.error("传统管线执行异常", e);
            out.setError("传统管线执行异常: " + e.getMessage());
        }
        out.setSteps(ctx.getSteps());
        out.setTotalElapsedMs(Math.max(1, (System.nanoTime() - startTotal) / 1_000_000));
        return out;
    }

    private void run(String question, boolean forceMock, TraceContext ctx, PipelineResult out) {
        List<SchemaLinker.SchemaColumn> columns = stepSchema(ctx);
        stepLink(ctx, question, columns);
        stepJoin(ctx, columns);

        LlmClient.GenerationResult gen = stepGenerate(ctx, question, columns, forceMock);
        String sql = gen.getSql();
        ResultTable result = null;
        if (sql != null) {
            result = safeQueryExecutor.execute(sql);
        }

        List<RiskFinding> risks = stepRisk(ctx, sql, question);

        out.setMode(gen.getMode());
        out.setModeLabel(modeLabel(gen));
        out.setFallbackReason(gen.getFallbackReason());
        out.setSql(sql);
        out.setSqlStatus(sqlStatus(sql, result));
        out.setResult(result);
        out.setRisks(risks);
        out.setConfidence(buildConfidence(risks));
    }

    /** 第一步：模式读取 */
    private List<SchemaLinker.SchemaColumn> stepSchema(TraceContext ctx) {
        TraceStep step = ctx.begin("schema", "模式读取");
        List<SchemaLinker.SchemaColumn> columns = schemaLoader.loadSchemaColumns();
        String ddl = schemaLoader.loadDdl();
        List<List<String>> rows = new ArrayList<>(9);
        for (String table : schemaLoader.businessTables()) {
            long colCount = columns.stream().filter(c -> c.tableName().equals(table)).count();
            rows.add(List.of(table, String.valueOf(colCount) + " 列"));
        }
        step.kvTable(List.of("业务表", "列数"), rows);
        step.sqlPre(truncate(ddl, RAW_DISPLAY_LIMIT));
        ctx.end(step, "ok", "读取 " + schemaLoader.businessTables().size() + " 张业务表 DDL 与列注释");
        return columns;
    }

    /** 第二步：列名匹配 */
    private SchemaLinker.LinkResult stepLink(TraceContext ctx, String question,
                                             List<SchemaLinker.SchemaColumn> columns) {
        TraceStep step = ctx.begin("link", "列名匹配");
        SchemaLinker.LinkResult link = schemaLinker.link(question, columns);
        for (SchemaLinker.Match m : link.getMatches()) {
            step.kv(m.getToken(), "问题词", m.getTableName() + "." + m.getColumnName(), "字面命中(" + m.getKind() + ")");
        }
        for (String missed : link.getMissed()) {
            step.text("未解析词: " + missed + "（列名与注释中无字面命中，交由 LLM 猜测）", "warn");
        }
        String status = link.getMissed().isEmpty() ? "ok" : "warn";
        ctx.end(step, status, "命中 " + link.getMatches().size() + " 词，未解析 " + link.getMissed().size() + " 词");
        return link;
    }

    /** 第三步：关联推断 */
    private void stepJoin(TraceContext ctx, List<SchemaLinker.SchemaColumn> columns) {
        TraceStep step = ctx.begin("join", "关联推断");
        List<JoinInferer.JoinCandidate> joins = joinInferer.infer(columns);
        for (JoinInferer.JoinCandidate j : joins) {
            step.relationEdge(j.tableA() + "." + j.columnName(), j.tableB() + "." + j.columnName(), "同名列可关联");
        }
        ctx.end(step, "ok", "按同名列推断出 " + joins.size() + " 个候选关联（无基数语义）");
    }

    /** 第四步：LLM 生成（失败一次纠错重试；forceMock 直接确定性生成），并执行 */
    private LlmClient.GenerationResult stepGenerate(TraceContext ctx, String question,
                                                    List<SchemaLinker.SchemaColumn> columns, boolean forceMock) {
        TraceStep step = ctx.begin("generate", "LLM 生成");
        String ddl = schemaLoader.loadDdl();
        LlmClient.GenerationResult gen;
        if (forceMock) {
            String content = mockGenerator.generateContent(question);
            gen = new LlmClient.GenerationResult("mock", content, SqlExtractor.extract(content),
                    "mock", null, null, "基准快速模式强制 Mock");
        } else {
            gen = llmClient.generate(question, ddl);
        }
        if (gen.getSql() == null && "llm".equals(gen.getMode())) {
            step.text("首次输出未包含有效 SELECT，携带错误回执重试一次", "warn");
            LlmClient.GenerationResult retry = llmClient.generate(question + RETRY_HINT, ddl);
            if (retry.getSql() != null) {
                gen = retry;
            }
        }
        step.kv("生成模式", gen.getMode(), modeLabel(gen), "llm-cache 为缓存命中，mock 为降级模拟");
        if (gen.getContent() != null) {
            step.kv("模型原始输出", "", truncate(gen.getContent(), RAW_DISPLAY_LIMIT));
        }
        if (gen.getSql() == null) {
            step.text("未能从输出中抽取到有效 SELECT 语句", "error");
            ctx.fail(step, "抽取失败");
            return gen;
        }
        step.sqlPre(gen.getSql());
        ResultTable executed = safeQueryExecutor.execute(gen.getSql());
        if (executed.getError() != null) {
            step.text("执行失败: " + executed.getError(), "error");
            ctx.end(step, "error", "SQL 执行失败");
        } else {
            ctx.end(step, "ok", "执行成功，返回 " + executed.getRowCount() + " 行");
        }
        return gen;
    }

    /** 第五步：风险检测 */
    private List<RiskFinding> stepRisk(TraceContext ctx, String sql, String question) {
        TraceStep step = ctx.begin("risk", "风险检测");
        List<RiskFinding> risks = sql == null ? List.of() : riskLinter.lint(sql, question);
        for (RiskFinding r : risks) {
            step.text("[" + r.getLevel().toUpperCase() + "] " + r.getCode() + " " + r.getTitle(), levelOf(r));
        }
        boolean hasHigh = risks.stream().anyMatch(r -> "high".equals(r.getLevel()));
        String status = risks.isEmpty() ? "ok" : (hasHigh ? "error" : "warn");
        ctx.end(step, status, "检出 " + risks.size() + " 项风险（high "
                + risks.stream().filter(r -> "high".equals(r.getLevel())).count() + " 项）");
        return risks;
    }

    private String levelOf(RiskFinding r) {
        switch (r.getLevel()) {
            case "high":
                return "error";
            case "medium":
                return "warn";
            default:
                return "info";
        }
    }

    private String modeLabel(LlmClient.GenerationResult gen) {
        switch (gen.getMode()) {
            case "llm":
                return "LLM: " + gen.getModel();
            case "llm-cache":
                return "LLM 缓存命中（" + gen.getModel() + "）";
            default:
                return "模拟模式（降级）";
        }
    }

    private String sqlStatus(String sql, ResultTable result) {
        if (sql == null) {
            return "none";
        }
        return result != null && result.getError() == null ? "ok" : "error";
    }

    /** 置信度：clamp(100 - 10*high - 6*medium - 3*low, 30, 100) */
    private Confidence buildConfidence(List<RiskFinding> risks) {
        long high = risks.stream().filter(r -> "high".equals(r.getLevel())).count();
        long medium = risks.stream().filter(r -> "medium".equals(r.getLevel())).count();
        long low = risks.stream().filter(r -> "low".equals(r.getLevel())).count();
        int score = CONFIDENCE_CEIL - DEDUCT_HIGH * (int) high - DEDUCT_MEDIUM * (int) medium - DEDUCT_LOW * (int) low;
        int clamped = Math.max(CONFIDENCE_FLOOR, Math.min(CONFIDENCE_CEIL, score));
        List<ConfidenceFactor> factors = new ArrayList<>(risks.size() + 1);
        for (RiskFinding r : risks) {
            int delta = "high".equals(r.getLevel()) ? -DEDUCT_HIGH
                    : "medium".equals(r.getLevel()) ? -DEDUCT_MEDIUM : -DEDUCT_LOW;
            factors.add(new ConfidenceFactor(r.getTitle() + "（" + r.getCode() + "）", delta));
        }
        if (factors.isEmpty()) {
            factors.add(new ConfidenceFactor("未检出风险", 0));
        }
        String formula = String.format("%d - %d*%d - %d*%d - %d*%d -> %d",
                CONFIDENCE_CEIL, DEDUCT_HIGH, high, DEDUCT_MEDIUM, medium, DEDUCT_LOW, low, clamped);
        return new Confidence(clamped, formula, factors);
    }

    private String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + " ...（截断）";
    }
}
