package com.ontoquery.benchmark;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontoquery.ontology.OntologyPipeline;
import com.ontoquery.sql.SafeQueryExecutor;
import com.ontoquery.support.PipelineResult;
import com.ontoquery.tradnl.TraditionalPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基准运行器：逐题运行两条管线，golden SQL 与被测 SQL 同跑、标量精确比对。
 * 时间相对题不存固定答案，每次运行实时计算，天然免疫时间漂移。
 * 结果落库 benchmark_run / benchmark_item。
 *
 * @author 月夜烛峰
 */
@Service
public class BenchmarkRunner {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkRunner.class);

    private static final String QUESTIONS_LOCATION = "benchmark/questions.json";

    private final OntologyPipeline ontologyPipeline;
    private final TraditionalPipeline traditionalPipeline;
    private final SafeQueryExecutor safeQueryExecutor;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /** 基准题目（classpath JSON） */
    public record Question(Integer questionNo, String category, String question, String goldenSql) {
    }

    /** 单题运行明细 */
    public record ItemResult(Integer questionNo, String category, String question, String goldenCount,
                             String ontologySql, String ontologyCount, boolean ontologyOk,
                             String traditionalSql, String traditionalCount, boolean traditionalOk) {
    }

    public BenchmarkRunner(OntologyPipeline ontologyPipeline, TraditionalPipeline traditionalPipeline,
                           SafeQueryExecutor safeQueryExecutor, JdbcTemplate jdbcTemplate,
                           ObjectMapper objectMapper) {
        this.ontologyPipeline = ontologyPipeline;
        this.traditionalPipeline = traditionalPipeline;
        this.safeQueryExecutor = safeQueryExecutor;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public List<Question> loadQuestions() {
        try (InputStream in = new ClassPathResource(QUESTIONS_LOCATION).getInputStream()) {
            return objectMapper.readValue(in, new TypeReference<List<Question>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("读取基准题库失败: " + e.getMessage(), e);
        }
    }

    /** 运行基准；forceMock=true 传统侧强制 Mock（秒级完成） */
    public Map<String, Object> run(boolean forceMock) {
        List<Question> questions = loadQuestions();
        List<ItemResult> items = new ArrayList<>(questions.size());
        long ontologyElapsed = 0L;
        long traditionalElapsed = 0L;

        for (Question q : questions) {
            PipelineResult ontology = ontologyPipeline.answer(q.question());
            PipelineResult traditional = traditionalPipeline.answer(q.question(), forceMock);
            ontologyElapsed += ontology.getTotalElapsedMs() == null ? 0L : ontology.getTotalElapsedMs();
            traditionalElapsed += traditional.getTotalElapsedMs() == null ? 0L : traditional.getTotalElapsedMs();

            String golden = safeQueryExecutor.scalar(q.goldenSql());
            String ontologyCount = scalarOf(ontology);
            String traditionalCount = scalarOf(traditional);
            items.add(new ItemResult(q.questionNo(), q.category(), q.question(), golden,
                    ontology.getSql(), ontologyCount, sameScalar(golden, ontologyCount),
                    traditional.getSql(), traditionalCount, sameScalar(golden, traditionalCount)));
        }

        long ontologyCorrect = items.stream().filter(ItemResult::ontologyOk).count();
        long traditionalCorrect = items.stream().filter(ItemResult::traditionalOk).count();
        Map<String, Object> summary = buildSummary(null, forceMock ? "mock" : "llm", items,
                ontologyCorrect, traditionalCorrect, ontologyElapsed, traditionalElapsed);
        Long runId = persist(summary, items);
        summary.put("runId", runId);
        return summary;
    }

    /** 仅本体侧校验结果（运维变更验证用，不落库）。 */
    public record OntologyCheck(Integer accuracy, List<Integer> failedQuestionNos) {
    }

    /** 36 题仅跑本体管线，与 golden 标量比对；不写 benchmark_run。 */
    public OntologyCheck runOntologyOnly() {
        List<Question> questions = loadQuestions();
        List<Integer> failed = new ArrayList<>(questions.size());
        for (Question q : questions) {
            PipelineResult ontology = ontologyPipeline.answer(q.question());
            String golden = safeQueryExecutor.scalar(q.goldenSql());
            String actual = scalarOf(ontology);
            if (!sameScalar(golden, actual)) {
                failed.add(q.questionNo());
            }
        }
        double rate = (questions.size() - failed.size()) * 100.0 / questions.size();
        int accuracy = Long.valueOf(Math.round(rate)).intValue();
        return new OntologyCheck(Integer.valueOf(accuracy), failed);
    }

    /** 从管线结果取首行首列标量 */
    private String scalarOf(PipelineResult result) {
        if (result == null || result.getResult() == null || result.getResult().getError() != null
                || result.getResult().getRows() == null || result.getResult().getRows().isEmpty()
                || result.getResult().getRows().get(0).isEmpty()) {
            return null;
        }
        return result.getResult().getRows().get(0).get(0);
    }

    /** 标量比对：数值按值比较（BigDecimal.compareTo），否则字符串精确比对 */
    private boolean sameScalar(String golden, String actual) {
        if (golden == null || actual == null) {
            return false;
        }
        try {
            return new BigDecimal(golden.trim()).compareTo(new BigDecimal(actual.trim())) == 0;
        } catch (NumberFormatException e) {
            return golden.trim().equals(actual.trim());
        }
    }

    private Map<String, Object> buildSummary(Long runId, String runMode, List<ItemResult> items,
                                             long ontologyCorrect, long traditionalCorrect,
                                             long ontologyElapsed, long traditionalElapsed) {
        int total = items.size();
        Map<String, Long> ontologyByCategory = new LinkedHashMap<>(8);
        Map<String, Long> traditionalByCategory = new LinkedHashMap<>(8);
        Map<String, Long> totalByCategory = new LinkedHashMap<>(8);
        for (ItemResult item : items) {
            totalByCategory.merge(item.category(), 1L, Long::sum);
            if (item.ontologyOk()) {
                ontologyByCategory.merge(item.category(), 1L, Long::sum);
            }
            if (item.traditionalOk()) {
                traditionalByCategory.merge(item.category(), 1L, Long::sum);
            }
        }
        List<Map<String, Object>> categoryStats = new ArrayList<>(totalByCategory.size());
        for (Map.Entry<String, Long> e : totalByCategory.entrySet()) {
            Map<String, Object> stat = new LinkedHashMap<>(8);
            stat.put("category", e.getKey());
            stat.put("ontologyAccuracy", percent(ontologyByCategory.getOrDefault(e.getKey(), 0L), e.getValue()));
            stat.put("traditionalAccuracy", percent(traditionalByCategory.getOrDefault(e.getKey(), 0L), e.getValue()));
            categoryStats.add(stat);
        }

        Map<String, Object> summary = new LinkedHashMap<>(16);
        if (runId != null) {
            summary.put("runId", runId);
        }
        summary.put("runMode", runMode);
        summary.put("totalCount", total);
        summary.put("ontologyCorrect", ontologyCorrect);
        summary.put("traditionalCorrect", traditionalCorrect);
        summary.put("ontologyAccuracy", percent(ontologyCorrect, total));
        summary.put("traditionalAccuracy", percent(traditionalCorrect, total));
        summary.put("ontologyElapsedMs", ontologyElapsed);
        summary.put("traditionalElapsedMs", traditionalElapsed);
        summary.put("categoryStats", categoryStats);
        summary.put("items", itemMaps(items));
        return summary;
    }

    private List<Map<String, Object>> itemMaps(List<ItemResult> items) {
        List<Map<String, Object>> list = new ArrayList<>(items.size());
        for (ItemResult item : items) {
            Map<String, Object> m = new LinkedHashMap<>(16);
            m.put("questionNo", item.questionNo());
            m.put("category", item.category());
            m.put("question", item.question());
            m.put("goldenCount", item.goldenCount());
            m.put("ontologySql", item.ontologySql());
            m.put("ontologyCount", item.ontologyCount());
            m.put("ontologyOk", item.ontologyOk());
            m.put("traditionalSql", item.traditionalSql());
            m.put("traditionalCount", item.traditionalCount());
            m.put("traditionalOk", item.traditionalOk());
            list.add(m);
        }
        return list;
    }

    private long percent(long part, long total) {
        if (total == 0) {
            return 0;
        }
        return Math.round(part * 100.0 / total);
    }

    /** 落库并返回 run_id */
    private Long persist(Map<String, Object> summary, List<ItemResult> items) {
        String runMode = String.valueOf(summary.get("runMode"));
        jdbcTemplate.update(
                "INSERT INTO benchmark_run (run_mode, total_count, ontology_correct, traditional_correct,"
                        + " ontology_elapsed_ms, traditional_elapsed_ms) VALUES (?, ?, ?, ?, ?, ?)",
                runMode, summary.get("totalCount"), summary.get("ontologyCorrect"),
                summary.get("traditionalCorrect"), summary.get("ontologyElapsedMs"),
                summary.get("traditionalElapsedMs"));
        Long runId = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM benchmark_run WHERE run_mode = ?", Long.class, runMode);
        for (ItemResult item : items) {
            jdbcTemplate.update(
                    "INSERT INTO benchmark_item (run_id, question_no, category, question, golden_count,"
                            + " ontology_sql, ontology_count, ontology_ok, traditional_sql, traditional_count,"
                            + " traditional_ok) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    runId, item.questionNo(), item.category(), item.question(), item.goldenCount(),
                    item.ontologySql(), item.ontologyCount(), item.ontologyOk() ? 1 : 0,
                    item.traditionalSql(), item.traditionalCount(), item.traditionalOk() ? 1 : 0);
        }
        return runId;
    }

    /** 查询最近一次运行（无则 null） */
    public Map<String, Object> latest() {
        List<Map<String, Object>> runs = jdbcTemplate.queryForList(
                "SELECT id, run_mode, total_count, ontology_correct, traditional_correct,"
                        + " ontology_elapsed_ms, traditional_elapsed_ms FROM benchmark_run"
                        + " ORDER BY id DESC LIMIT 1");
        if (runs.isEmpty()) {
            return null;
        }
        Map<String, Object> run = runs.get(0);
        Long runId = ((Number) run.get("id")).longValue();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT question_no, category, question, golden_count, ontology_sql, ontology_count,"
                        + " ontology_ok, traditional_sql, traditional_count, traditional_ok"
                        + " FROM benchmark_item WHERE run_id = ? ORDER BY question_no", runId);
        List<ItemResult> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(new ItemResult(
                    ((Number) row.get("question_no")).intValue(),
                    String.valueOf(row.get("category")),
                    String.valueOf(row.get("question")),
                    (String) row.get("golden_count"),
                    (String) row.get("ontology_sql"),
                    (String) row.get("ontology_count"),
                    ((Number) row.get("ontology_ok")).intValue() == 1,
                    (String) row.get("traditional_sql"),
                    (String) row.get("traditional_count"),
                    ((Number) row.get("traditional_ok")).intValue() == 1));
        }
        long ontologyCorrect = items.stream().filter(ItemResult::ontologyOk).count();
        long traditionalCorrect = items.stream().filter(ItemResult::traditionalOk).count();
        Map<String, Object> summary = buildSummary(runId, String.valueOf(run.get("run_mode")), items,
                ontologyCorrect, traditionalCorrect,
                ((Number) run.get("ontology_elapsed_ms")).longValue(),
                ((Number) run.get("traditional_elapsed_ms")).longValue());
        log.info("读取最近基准 runId={}, 本体 {}/{}, 传统 {}/{}", runId, ontologyCorrect, items.size(),
                traditionalCorrect, items.size());
        return summary;
    }
}
