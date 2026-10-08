package com.ontoquery.web;

import com.ontoquery.config.LlmProperties;
import com.ontoquery.llm.LlmCache;
import com.ontoquery.llm.LlmClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元数据端点：数据源表结构、LLM 状态、缓存管理。
 *
 * @author 月夜烛峰
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    private final JdbcTemplate jdbcTemplate;
    private final LlmProperties llmProperties;
    private final LlmCache llmCache;
    private final LlmClient llmClient;

    public MetaController(JdbcTemplate jdbcTemplate, LlmProperties llmProperties,
                          LlmCache llmCache, LlmClient llmClient) {
        this.jdbcTemplate = jdbcTemplate;
        this.llmProperties = llmProperties;
        this.llmCache = llmCache;
        this.llmClient = llmClient;
    }

    /** 全部数据表：名称、注释、精确行数、列清单 */
    @GetMapping("/tables")
    public List<Map<String, Object>> tables() {
        List<Map<String, Object>> tableInfos = jdbcTemplate.queryForList(
                "SELECT table_name AS name, table_comment AS comment FROM information_schema.tables"
                        + " WHERE table_schema = DATABASE() ORDER BY table_name");
        Map<String, List<Map<String, Object>>> columnsByTable = new LinkedHashMap<>(64);
        jdbcTemplate.query(
                "SELECT table_name, column_name, column_type, column_comment FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE() ORDER BY table_name, ordinal_position", rs -> {
            Map<String, Object> col = new LinkedHashMap<>(8);
            col.put("name", rs.getString("column_name"));
            col.put("type", rs.getString("column_type"));
            col.put("comment", rs.getString("column_comment"));
            columnsByTable.computeIfAbsent(rs.getString("table_name"), k -> new ArrayList<>(16)).add(col);
        });

        List<Map<String, Object>> result = new ArrayList<>(tableInfos.size());
        for (Map<String, Object> t : tableInfos) {
            String name = String.valueOf(t.get("name"));
            Long rowCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + name + "`", Long.class);
            Map<String, Object> item = new LinkedHashMap<>(8);
            item.put("name", name);
            item.put("comment", t.get("comment"));
            item.put("rowCount", rowCount);
            item.put("columns", columnsByTable.getOrDefault(name, List.of()));
            result.add(item);
        }
        return result;
    }

    /** LLM 配置与运行状态（不暴露 api-key） */
    @GetMapping("/llm-status")
    public Map<String, Object> llmStatus() {
        Map<String, Object> status = new LinkedHashMap<>(8);
        status.put("enabled", llmProperties.getEnabled());
        status.put("model", llmProperties.getModel());
        status.put("baseUrl", llmProperties.getBaseUrl());
        status.put("cacheEntries", llmCache.count());
        status.put("lastMode", llmClient.getLastMode());
        return status;
    }

    /** 清空 LLM 缓存（演示前重置用） */
    @PostMapping("/llm-cache/clear")
    public Map<String, Object> clearCache() {
        Map<String, Object> body = new LinkedHashMap<>(4);
        body.put("cleared", llmCache.clear());
        return body;
    }
}
