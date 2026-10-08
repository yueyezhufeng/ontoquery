package com.ontoquery.llm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * LLM 响应缓存（llm_cache 表）。缓存键 = SHA256(model + '|' + question + '|' + SHA256(ddl))。
 * 相同模型、相同问题、相同表结构直接复用历史响应，保证演示可复现。
 *
 * @author 月夜烛峰
 */
@Component
public class LlmCache {

    private final JdbcTemplate jdbcTemplate;

    public LlmCache(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 缓存条目 */
    public static final class Entry {
        private final String responseText;
        private final String sqlText;

        public Entry(String responseText, String sqlText) {
            this.responseText = responseText;
            this.sqlText = sqlText;
        }

        public String getResponseText() {
            return responseText;
        }

        public String getSqlText() {
            return sqlText;
        }

        @Override
        public String toString() {
            return "Entry{responseTextLength=" + (responseText == null ? 0 : responseText.length())
                    + ", sqlTextLength=" + (sqlText == null ? 0 : sqlText.length()) + '}';
        }
    }

    public Optional<Entry> find(String cacheKey) {
        return jdbcTemplate.query(
                "SELECT response_text, sql_text FROM llm_cache WHERE cache_key = ?",
                (rs, rowNum) -> new Entry(rs.getString(1), rs.getString(2)), cacheKey).stream().findFirst();
    }

    public void save(String cacheKey, String model, String question, Integer promptTokens,
                     Integer completionTokens, String responseText, String sqlText) {
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM llm_cache WHERE cache_key = ?", Integer.class, cacheKey);
        if (existing != null && existing > 0) {
            jdbcTemplate.update(
                    "UPDATE llm_cache SET model = ?, question = ?, prompt_tokens = ?, completion_tokens = ?,"
                            + " response_text = ?, sql_text = ?, update_time = CURRENT_TIMESTAMP WHERE cache_key = ?",
                    model, question, promptTokens, completionTokens, responseText, sqlText, cacheKey);
            return;
        }
        jdbcTemplate.update(
                "INSERT INTO llm_cache (cache_key, model, question, prompt_tokens, completion_tokens,"
                        + " response_text, sql_text) VALUES (?, ?, ?, ?, ?, ?, ?)",
                cacheKey, model, question, promptTokens, completionTokens, responseText, sqlText);
    }

    public Integer count() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM llm_cache", Integer.class);
        return n == null ? 0 : n;
    }

    /** 清空缓存，返回清除条数 */
    public Integer clear() {
        return jdbcTemplate.update("DELETE FROM llm_cache");
    }

    /** SHA256 十六进制 */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 组装缓存键 */
    public static String buildKey(String model, String question, String ddl) {
        return sha256(model + "|" + question + "|" + sha256(ddl));
    }
}
