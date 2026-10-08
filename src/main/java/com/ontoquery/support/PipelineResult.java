package com.ontoquery.support;

import com.ontoquery.ontology.trace.TraceContext;
import com.ontoquery.sql.ResultTable;

import java.util.List;

/**
 * 管线统一结果（CONTRACT.md 第 2 节），本体与传统两条管线共用。
 */
public class PipelineResult {

    private String engine;
    private String mode;
    private String modeLabel;
    private String fallbackReason;
    private String question;
    private List<TraceContext.TraceStep> steps;
    private String sql;
    /** ok | error | none */
    private String sqlStatus;
    private ResultTable result;
    private Confidence confidence;
    private List<RiskFinding> risks;
    private String error;
    private Long totalElapsedMs;

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getModeLabel() { return modeLabel; }
    public void setModeLabel(String modeLabel) { this.modeLabel = modeLabel; }
    public String getFallbackReason() { return fallbackReason; }
    public void setFallbackReason(String fallbackReason) { this.fallbackReason = fallbackReason; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public List<TraceContext.TraceStep> getSteps() { return steps; }
    public void setSteps(List<TraceContext.TraceStep> steps) { this.steps = steps; }
    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }
    public String getSqlStatus() { return sqlStatus; }
    public void setSqlStatus(String sqlStatus) { this.sqlStatus = sqlStatus; }
    public ResultTable getResult() { return result; }
    public void setResult(ResultTable result) { this.result = result; }
    public Confidence getConfidence() { return confidence; }
    public void setConfidence(Confidence confidence) { this.confidence = confidence; }
    public List<RiskFinding> getRisks() { return risks; }
    public void setRisks(List<RiskFinding> risks) { this.risks = risks; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Long getTotalElapsedMs() { return totalElapsedMs; }
    public void setTotalElapsedMs(Long totalElapsedMs) { this.totalElapsedMs = totalElapsedMs; }

    @Override
    public String toString() {
        return "PipelineResult{engine='" + engine + "', mode='" + mode + "', sqlStatus='" + sqlStatus
                + "', steps=" + (steps == null ? 0 : steps.size()) + ", totalElapsedMs=" + totalElapsedMs + '}';
    }
}
