package com.ontoquery.sql;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 查询结果（值一律字符串化，前端零加工渲染）。
 */
public class ResultTable {

    private List<String> columns = new ArrayList<>();
    private List<List<String>> rows = new ArrayList<>();
    private Integer rowCount;
    /** 结果超过 501 行被截断（最大展示 500） */
    private Boolean truncated;
    private Long elapsedMs;
    private String error;

    public static ResultTable failure(String error) {
        ResultTable t = new ResultTable();
        t.error = error;
        return t;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public List<List<String>> getRows() {
        return rows;
    }

    public void setRows(List<List<String>> rows) {
        this.rows = rows;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public void setRowCount(Integer rowCount) {
        this.rowCount = rowCount;
    }

    public Boolean getTruncated() {
        return truncated;
    }

    public void setTruncated(Boolean truncated) {
        this.truncated = truncated;
    }

    public Long getElapsedMs() {
        return elapsedMs;
    }

    public void setElapsedMs(Long elapsedMs) {
        this.elapsedMs = elapsedMs;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    @Override
    public String toString() {
        return "ResultTable{columns=" + columns.size() + ", rowCount=" + rowCount + ", truncated=" + truncated
                + ", elapsedMs=" + elapsedMs + ", error='" + error + "'}";
    }
}
