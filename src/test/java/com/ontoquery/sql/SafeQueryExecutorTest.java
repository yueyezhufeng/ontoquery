package com.ontoquery.sql;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SafeQueryExecutor 校验单测：白名单正反例（SELECT/WITH 放行、DML/DDL 拒绝、
 * 字符串字面量豁免、注释剥离、堆叠语句、空语句）。
 */
class SafeQueryExecutorTest {

    private SafeQueryExecutor executor() {
        // validate 不触达数据源，仅校验逻辑；JdbcTemplate 无数据源即可构造
        return new SafeQueryExecutor(new JdbcTemplate());
    }

    @Test
    void plainSelectIsAllowed() {
        // 准备
        SafeQueryExecutor executor = executor();
        // 执行 + 断言
        assertDoesNotThrow(() -> executor.validate("SELECT COUNT(*) FROM dim_patient"),
                "普通 SELECT 应放行");
    }

    @Test
    void withCteIsAllowed() {
        // 准备
        SafeQueryExecutor executor = executor();
        // 执行 + 断言
        assertDoesNotThrow(() -> executor.validate("WITH t AS (SELECT 1 AS n) SELECT n FROM t"),
                "WITH 开头的 CTE 查询应放行");
    }

    @Test
    void dropTableIsRejected() {
        // 准备
        SafeQueryExecutor executor = executor();
        // 执行
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> executor.validate("DROP TABLE dim_patient"), "DROP 应被拒绝");
        // 断言
        assertTrue(ex.getMessage().contains("仅允许"), "错误信息应说明仅允许 SELECT: " + ex.getMessage());
    }

    @Test
    void deleteIsRejected() {
        // 准备
        SafeQueryExecutor executor = executor();
        // 执行 + 断言
        assertThrows(IllegalArgumentException.class, () -> executor.validate("DELETE FROM fact_visit"),
                "DELETE 应被拒绝");
    }

    @Test
    void updateLowercaseIsRejected() {
        // 准备
        SafeQueryExecutor executor = executor();
        // 执行 + 断言
        assertThrows(IllegalArgumentException.class,
                () -> executor.validate("update dim_patient set name = 'x'"), "小写 update 应同样被拒绝");
    }

    @Test
    void stackedStatementIsRejected() {
        // 准备
        SafeQueryExecutor executor = executor();
        // 执行
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> executor.validate("SELECT 1; DROP TABLE dim_patient"), "堆叠语句应被拒绝");
        // 断言
        assertTrue(ex.getMessage().contains("禁止的关键字"), "堆叠注入应命中关键字规则: " + ex.getMessage());
    }

    @Test
    void keywordInsideStringLiteralIsAllowed() {
        // 准备
        SafeQueryExecutor executor = executor();
        // 执行 + 断言
        assertDoesNotThrow(() -> executor.validate("SELECT disease_name FROM fact_diagnosis"
                        + " WHERE disease_name = '删除前记录'"),
                "字符串字面量内的关键字词面不应误触发拒绝");
    }

    @Test
    void keywordInsideCommentIsStrippedBeforeCheck() {
        // 准备
        SafeQueryExecutor executor = executor();
        // 执行 + 断言
        assertDoesNotThrow(() -> executor.validate("SELECT 1 /* drop table if exists */ FROM dim_patient"),
                "块注释内的关键字先剥离再校验，不应拒绝");
    }

    @Test
    void lowercaseWithPrefixIsAllowed() {
        // 准备
        SafeQueryExecutor executor = executor();
        // 执行 + 断言
        assertDoesNotThrow(() -> executor.validate("with t as (select 1 as n) select n from t"),
                "小写 with 开头的 CTE 应同样放行（前缀检查大小写不敏感）");
    }

    @Test
    void blankSqlIsRejected() {
        // 准备
        SafeQueryExecutor executor = executor();
        // 执行 + 断言
        assertThrows(IllegalArgumentException.class, () -> executor.validate("   "),
                "空白 SQL 应被拒绝");
    }
}
