package com.ontoquery.ontology.ops;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValueExprValidatorTest {

    @Test
    void 合法表达式通过() {
        assertNull(ValueExprValidator.syntaxRejectReason("{t}.disease_code = 'E11'"), "等值字符串应通过");
        assertNull(ValueExprValidator.syntaxRejectReason("{t}.result_value > 7"), "数值比较应通过");
        assertNull(ValueExprValidator.syntaxRejectReason("{t}.drug_code <> 'D_1'"), "不等号应通过");
        assertNull(ValueExprValidator.syntaxRejectReason("{t}.value  >=  4.5"), "中间多空格应通过");
    }

    @Test
    void 子查询注入被拒绝() {
        assertNotNull(ValueExprValidator.syntaxRejectReason("{t}.drug_code = (SELECT 1)"), "子查询必须拒绝");
    }

    @Test
    void 大写列名被拒绝() {
        assertNotNull(ValueExprValidator.syntaxRejectReason("{t}.DRUG_CODE = 'X'"), "列名仅允许小写");
    }

    @Test
    void 缺少占位符或引号逃逸被拒绝() {
        assertNotNull(ValueExprValidator.syntaxRejectReason("drug_code = 'X'"), "缺 {t} 前缀拒绝");
        assertNotNull(ValueExprValidator.syntaxRejectReason("{t}.a = 'x' OR '1'='1"), "引号逃逸拒绝");
        assertNotNull(ValueExprValidator.syntaxRejectReason(null), "null 拒绝");
        assertNotNull(ValueExprValidator.syntaxRejectReason("  "), "空白拒绝");
    }
}
