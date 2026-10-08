package com.ontoquery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 传统 NL2SQL 侧 LLM 配置（DeepSeek，OpenAI 兼容协议）。
 * 属性值全部来自 application.yml，类内不设默认值。
 */
@ConfigurationProperties(prefix = "ontoquery.llm")
public class LlmProperties {

    /** false 时传统管线直接使用确定性 Mock 生成器 */
    private Boolean enabled;
    private String baseUrl;
    private String apiKey;
    private String model;
    private Long timeoutMs;
    private Long connectTimeoutMs;
    /** deepseek-flash 为推理模型，reasoning 计入 tokens，必须给足 */
    private Integer maxTokens;
    private Double temperature;
    private Integer retries;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(Long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer getRetries() {
        return retries;
    }

    public void setRetries(Integer retries) {
        this.retries = retries;
    }

    @Override
    public String toString() {
        return "LlmProperties{enabled=" + enabled + ", baseUrl='" + baseUrl + '\'' + ", model='" + model + '\''
                + ", timeoutMs=" + timeoutMs + ", connectTimeoutMs=" + connectTimeoutMs
                + ", maxTokens=" + maxTokens + ", temperature=" + temperature + ", retries=" + retries + '}';
    }
}
