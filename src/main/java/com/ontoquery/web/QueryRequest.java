package com.ontoquery.web;

/**
 * 查询请求体：{"question": "..."}。
 *
 * @author 月夜烛峰
 */
public class QueryRequest {

    private String question;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    @Override
    public String toString() {
        return "QueryRequest{question='" + question + "'}";
    }
}
