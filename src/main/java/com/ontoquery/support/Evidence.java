package com.ontoquery.support;

/**
 * 风险证据：实测 SQL 与结果摘要。
 */
public class Evidence {

    private String sql;
    private String result;

    public Evidence() {
    }

    public Evidence(String sql, String result) {
        this.sql = sql;
        this.result = result;
    }

    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    @Override
    public String toString() {
        return "Evidence{sql='" + sql + "', result='" + result + "'}";
    }
}
