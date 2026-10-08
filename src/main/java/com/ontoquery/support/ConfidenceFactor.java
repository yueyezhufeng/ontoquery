package com.ontoquery.support;

/**
 * 置信度因子：label 描述 + delta（负数为扣分，0 为不减分项）。
 */
public class ConfidenceFactor {

    private String label;
    private Integer delta;

    public ConfidenceFactor() {
    }

    public ConfidenceFactor(String label, Integer delta) {
        this.label = label;
        this.delta = delta;
    }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public Integer getDelta() { return delta; }
    public void setDelta(Integer delta) { this.delta = delta; }

    @Override
    public String toString() {
        return "ConfidenceFactor{label='" + label + "', delta=" + delta + '}';
    }
}
