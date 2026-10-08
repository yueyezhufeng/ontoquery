package com.ontoquery.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ontoquery.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek LLM 客户端（OpenAI 兼容 /chat/completions 协议）。
 * 职责：缓存命中 -> 真实调用（带重试）-> 失败降级 Mock，三种模式对外统一。
 * 线程安全：HttpClient 与 ObjectMapper 均线程安全。
 *
 * @author 月夜烛峰
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static final String SYSTEM_PROMPT =
            "你是资深 MySQL 8 数据分析师。根据给定的表结构回答用户问题，"
                    + "只输出一条 MySQL 8 的 SELECT 查询语句，不要任何解释文字，不要使用 markdown 代码块围栏。";

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final LlmProperties properties;
    private final LlmCache cache;
    private final MockLlmGenerator mockGenerator;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    /** 最近一次生成模式（llm / llm-cache / mock），供元数据端点展示 */
    private volatile String lastMode = "mock";

    public LlmClient(LlmProperties properties, LlmCache cache, MockLlmGenerator mockGenerator,
                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.cache = cache;
        this.mockGenerator = mockGenerator;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    /** 生成结果：mode 区分 llm / llm-cache / mock */
    public static final class GenerationResult {
        private final String mode;
        private final String content;
        private final String sql;
        private final String model;
        private final Integer promptTokens;
        private final Integer completionTokens;
        private final String fallbackReason;

        public GenerationResult(String mode, String content, String sql, String model,
                                Integer promptTokens, Integer completionTokens, String fallbackReason) {
            this.mode = mode;
            this.content = content;
            this.sql = sql;
            this.model = model;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.fallbackReason = fallbackReason;
        }

        public String getMode() {
            return mode;
        }

        public String getContent() {
            return content;
        }

        public String getSql() {
            return sql;
        }

        public String getModel() {
            return model;
        }

        public Integer getPromptTokens() {
            return promptTokens;
        }

        public Integer getCompletionTokens() {
            return completionTokens;
        }

        public String getFallbackReason() {
            return fallbackReason;
        }

        @Override
        public String toString() {
            return "GenerationResult{mode='" + mode + "', model='" + model + "', sqlLength="
                    + (sql == null ? 0 : sql.length()) + ", fallbackReason='" + fallbackReason + "'}";
        }
    }

    public String getLastMode() {
        return lastMode;
    }

    /** 主入口：缓存 -> 真实调用（重试）-> 降级 Mock */
    public GenerationResult generate(String question, String ddl) {
        lastMode = "mock";

        // 显式禁用或配置缺失：直接 Mock
        if (properties.getEnabled() == null || !properties.getEnabled()
                || properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            return mock("llm.enabled=false 或 api-key 为空（演示降级）", question);
        }

        String model = properties.getModel();
        String cacheKey = LlmCache.buildKey(model, question, ddl);

        // 缓存命中：直接复用，保证演示可复现
        LlmCache.Entry cached = cache.find(cacheKey).orElse(null);
        if (cached != null) {
            lastMode = "llm-cache";
            String sql = cached.getSqlText() != null ? cached.getSqlText() : SqlExtractor.extract(cached.getResponseText());
            return new GenerationResult("llm-cache", cached.getResponseText(), sql, model, null, null, null);
        }

        int retries = properties.getRetries() == null ? 0 : properties.getRetries();
        String lastError = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                ApiReply reply = callApi(question, ddl, model);
                String sql = SqlExtractor.extract(reply.content());
                cache.save(cacheKey, model, question, reply.promptTokens(), reply.completionTokens(),
                        reply.content(), sql);
                lastMode = "llm";
                return new GenerationResult("llm", reply.content(), sql, model,
                        reply.promptTokens(), reply.completionTokens(), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("LLM 调用被中断");
                return mock("调用被中断（" + (lastError == null ? "interrupted" : lastError) + "）", question);
            } catch (IOException | RuntimeException e) {
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn("LLM 调用失败（第 {} 次）: {}", attempt + 1, lastError);
            }
        }
        return mock("LLM 调用失败已重试 " + (retries + 1) + " 次: " + lastError, question);
    }

    private GenerationResult mock(String reason, String question) {
        String content = mockGenerator.generateContent(question);
        return new GenerationResult("mock", content, SqlExtractor.extract(content),
                properties.getModel(), null, null, reason);
    }

    private ApiReply callApi(String question, String ddl, String model)
            throws IOException, InterruptedException {
        String url = properties.getBaseUrl() + CHAT_COMPLETIONS_PATH;

        Map<String, Object> userMessage = new LinkedHashMap<>(4);
        userMessage.put("role", "user");
        userMessage.put("content", "以下是 MySQL 8 数据库的表结构（SHOW CREATE TABLE 输出）：\n\n"
                + ddl + "\n\n请依据以上表结构回答问题，只输出一条 MySQL 8 的 SELECT 语句。\n\n问题：" + question);

        Map<String, Object> body = new LinkedHashMap<>(8);
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "system", "content", SYSTEM_PROMPT), userMessage));
        body.put("temperature", properties.getTemperature());
        body.put("max_tokens", properties.getMaxTokens());
        body.put("stream", false);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + properties.getApiKey())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body),
                        java.nio.charset.StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("DeepSeek HTTP " + response.statusCode()
                    + ": " + abbreviate(response.body()));
        }
        return parseReply(response.body());
    }

    private ApiReply parseReply(String responseBody) throws IOException {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new IOException("响应缺少 choices: " + abbreviate(responseBody));
        }
        String content = choices.get(0).path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            throw new IOException("响应 content 为空（可能 max_tokens 不足或推理未完成）");
        }
        JsonNode usage = root.path("usage");
        Integer promptTokens = usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : null;
        Integer completionTokens = usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : null;
        return new ApiReply(content, promptTokens, completionTokens);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }

    /** API 应答三元组 */
    private record ApiReply(String content, Integer promptTokens, Integer completionTokens) {
    }
}
