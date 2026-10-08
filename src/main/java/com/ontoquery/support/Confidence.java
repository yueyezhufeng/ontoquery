package com.ontoquery.support;

import java.util.List;

/**
 * 置信度（CONTRACT.md 第 2 节）：score + 公式还原 + 逐项因子。
 */
public class Confidence {

    private Integer score;
    private String formula;
    private List<ConfidenceFactor> factors;

    public Confidence() {
    }

    public Confidence(Integer score, String formula, List<ConfidenceFactor> factors) {
        this.score = score;
        this.formula = formula;
        this.factors = factors;
    }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public String getFormula() { return formula; }
    public void setFormula(String formula) { this.formula = formula; }
    public List<ConfidenceFactor> getFactors() { return factors; }
    public void setFactors(List<ConfidenceFactor> factors) { this.factors = factors; }

    @Override
    public String toString() {
        return "Confidence{score=" + score + ", formula='" + formula + "', factors="
                + (factors == null ? 0 : factors.size()) + '}';
    }
}
