package com.ontoquery.llm;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 LLM 原始输出中抽取第一条可执行 SELECT：
 * 剥离 markdown 围栏与前后缀解释文字，截取首个 SELECT/WITH 语句。
 * 传统管线的 SQL 不经此校验不得进入执行器。
 *
 * @author 月夜烛峰
 */
public final class SqlExtractor {

    /** 围栏代码块（```sql ... ``` 或 ``` ... ```） */
    private static final Pattern FENCE = Pattern.compile("(?is)```(?:sql)?\\s*(.*?)```");

    /** 首条 SELECT/WITH 语句（到分号或串尾） */
    private static final Pattern STATEMENT = Pattern.compile("(?is)\\b(select|with)\\b.*?(?:;|$)");

    private SqlExtractor() {
    }

    /** 抽取失败返回 null（含完全不含 SELECT 的情况） */
    public static String extract(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String source = content;
        Matcher fence = FENCE.matcher(content);
        if (fence.find()) {
            source = fence.group(1);
        }
        Matcher stmt = STATEMENT.matcher(source);
        if (!stmt.find()) {
            return null;
        }
        String sql = stmt.group().trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1);
        }
        return sql;
    }
}
