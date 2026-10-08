package com.ontoquery.web;

import com.ontoquery.compare.CompareService;
import com.ontoquery.ontology.OntologyPipeline;
import com.ontoquery.tradnl.TraditionalPipeline;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 问答端点：本体管线 / 传统管线 / 双管线对比。
 *
 * @author 月夜烛峰
 */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    private final OntologyPipeline ontologyPipeline;
    private final TraditionalPipeline traditionalPipeline;
    private final CompareService compareService;

    public QueryController(OntologyPipeline ontologyPipeline, TraditionalPipeline traditionalPipeline,
                           CompareService compareService) {
        this.ontologyPipeline = ontologyPipeline;
        this.traditionalPipeline = traditionalPipeline;
        this.compareService = compareService;
    }

    @PostMapping("/ontology")
    public Object ontology(@RequestBody QueryRequest request) {
        requireQuestion(request);
        return ontologyPipeline.answer(request.getQuestion());
    }

    @PostMapping("/traditional")
    public Object traditional(@RequestBody QueryRequest request) {
        requireQuestion(request);
        return traditionalPipeline.answer(request.getQuestion());
    }

    @PostMapping("/compare")
    public Map<String, Object> compare(@RequestBody QueryRequest request) {
        requireQuestion(request);
        return compareService.compare(request.getQuestion());
    }

    private void requireQuestion(QueryRequest request) {
        if (request == null || request.getQuestion() == null || request.getQuestion().isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
    }
}
