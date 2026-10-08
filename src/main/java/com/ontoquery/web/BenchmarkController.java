package com.ontoquery.web;

import com.ontoquery.benchmark.BenchmarkRunner;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基准端点：运行与查询最近结果。
 *
 * @author 月夜烛峰
 */
@RestController
@RequestMapping("/api/benchmark")
public class BenchmarkController {

    private final BenchmarkRunner benchmarkRunner;

    public BenchmarkController(BenchmarkRunner benchmarkRunner) {
        this.benchmarkRunner = benchmarkRunner;
    }

    /** forceMock=true 时传统侧强制 Mock，秒级完成；缺省走真实 LLM（串行，较慢） */
    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam(defaultValue = "false") boolean forceMock) {
        return benchmarkRunner.run(forceMock);
    }

    /** 有历史时与 /run 同构（扁平）；无历史时契约要求 {"run": null} */
    @GetMapping("/latest")
    public Map<String, Object> latest() {
        Map<String, Object> run = benchmarkRunner.latest();
        if (run != null) {
            return run;
        }
        Map<String, Object> body = new LinkedHashMap<>(2);
        body.put("run", null);
        return body;
    }
}
