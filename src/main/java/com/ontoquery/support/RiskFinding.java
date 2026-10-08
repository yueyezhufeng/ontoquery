package com.ontoquery.support;

import java.util.List;

/**
 * 风险项（CONTRACT.md 第 2 节）：本体侧 info 级提示，传统侧 linter 规则产出。
 */
public class RiskFinding {

    private String code;
    private String level;
    private String title;
    private String detail;
    private List<Evidence> evidence;

    public RiskFinding() {
    }

    public RiskFinding(String code, String level, String title, String detail, List<Evidence> evidence) {
        this.code = code;
        this.level = level;
        this.title = title;
        this.detail = detail;
        this.evidence = evidence;
    }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public List<Evidence> getEvidence() { return evidence; }
    public void setEvidence(List<Evidence> evidence) { this.evidence = evidence; }

    @Override
    public String toString() {
        return "RiskFinding{code='" + code + "', level='" + level + "', title='" + title + "'}";
    }
}
