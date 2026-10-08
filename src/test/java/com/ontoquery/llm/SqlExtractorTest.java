package com.ontoquery.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SqlExtractor 单元测试：围栏剥离、前缀文字、多语句截取、无 SELECT。
 *
 * @author 月夜烛峰
 */
class SqlExtractorTest {

    @Test
    void testExtractFromFencedSql() {
        String content = "好的，以下是查询该问题的 SQL：\n\n```sql\nSELECT COUNT(*) FROM dim_patient;\n```";
        String sql = SqlExtractor.extract(content);
        assertEquals("SELECT COUNT(*) FROM dim_patient", sql, "应剥离围栏与前后缀文字");
    }

    @Test
    void testExtractBareSql() {
        String sql = SqlExtractor.extract("SELECT 1");
        assertEquals("SELECT 1", sql, "无围栏时直接返回语句");
    }

    @Test
    void testExtractWithProsePrefix() {
        String content = "分析如下：\nSELECT patient_id FROM dim_patient WHERE patient_id > 100;\n希望对你有帮助。";
        String sql = SqlExtractor.extract(content);
        assertEquals("SELECT patient_id FROM dim_patient WHERE patient_id > 100", sql,
                "应截取首个 SELECT 语句并去掉尾分号");
    }

    @Test
    void testExtractWithStatement() {
        String content = "```sql\nWITH t AS (SELECT 1 AS n)\nSELECT * FROM t\n```";
        String sql = SqlExtractor.extract(content);
        assertTrue(sql != null && sql.startsWith("WITH"), "WITH 开头的语句也应被抽取: " + sql);
    }

    @Test
    void testExtractNoSelectReturnsNull() {
        assertNull(SqlExtractor.extract("抱歉，我无法回答该问题。"), "无 SELECT 时应返回 null");
        assertNull(SqlExtractor.extract(null), "空输入应返回 null");
        assertNull(SqlExtractor.extract("   "), "纯空白应返回 null");
    }
}
