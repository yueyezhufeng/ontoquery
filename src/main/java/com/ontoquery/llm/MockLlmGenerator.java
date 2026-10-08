package com.ontoquery.llm;

import com.ontoquery.support.QuestionTokenizer;
import org.springframework.stereotype.Component;

/**
 * 确定性 Mock LLM 生成器：模拟"典型 LLM 直接抄用户原词"的缺陷输出。
 * 刻意保留四类真实缺陷，供风险检测与对比演示：
 *  1. LIKE 用户原词 —— 同义词漏配（数据中同一实体存在多种写法）
 *  2. LEFT JOIN 链 + COUNT(*) 未去重 —— 多表行数膨胀
 *  3. 时间条件挂到检验日期列 —— 时间修饰歧义挂错列
 *  4. 输出带解释前缀与 markdown 围栏 —— 需要 SqlExtractor 清洗
 * 输出与真实 LLM 走完全相同的下游（抽取-校验-执行-检测），保证公平对比。
 *
 * @author 月夜烛峰
 */
@Component
public class MockLlmGenerator {

    /** 生成"带缺陷风格"的原始响应（含前缀与围栏，交由 SqlExtractor 清洗） */
    public String generateContent(String question) {
        return "好的，以下是查询该问题的 SQL：\n\n```sql\n" + buildSql(question) + "\n```";
    }

    /** 按问题槽位拼装缺陷 SQL（确定性：同问题同输出） */
    public String buildSql(String question) {
        String disease = QuestionTokenizer.extractDiseasePhrase(question);
        String drug = QuestionTokenizer.extractDrugPhrase(question);
        String lab = QuestionTokenizer.extractLabPhrase(question);
        QuestionTokenizer.CompareSlot compare = QuestionTokenizer.extractCompare(question);
        QuestionTokenizer.TimeWindow time = QuestionTokenizer.extractTimeWindow(question);

        StringBuilder sql = new StringBuilder(512);
        sql.append("SELECT COUNT(*) AS patient_count\n")
           .append("FROM dim_patient p\n");

        if (disease != null) {
            sql.append("LEFT JOIN fact_diagnosis d ON d.patient_id = p.patient_id\n");
        }
        if (lab != null || compare != null) {
            sql.append("LEFT JOIN fact_lab_result l ON l.patient_id = p.patient_id\n");
        }
        if (drug != null) {
            sql.append("LEFT JOIN fact_medication m ON m.patient_id = p.patient_id\n");
        }

        boolean whereStarted = false;
        if (disease != null) {
            sql.append("WHERE d.disease_name LIKE '%").append(disease).append("%'");
            whereStarted = true;
        }
        if (time != null && (lab != null || compare != null)) {
            // 缺陷三：时间条件挂到检验日期列（正确应挂诊断日期）
            sql.append(whereStarted ? "\n  AND " : "WHERE ")
               .append("l.test_date >= DATE_SUB(CURDATE(), INTERVAL ")
               .append(time.getDays()).append(" DAY)");
            whereStarted = true;
        }
        if (lab != null) {
            sql.append(whereStarted ? "\n  AND " : "WHERE ")
               .append("l.test_name LIKE '%").append(lab).append("%'");
            whereStarted = true;
        }
        if (compare != null) {
            sql.append(whereStarted ? "\n  AND " : "WHERE ")
               .append("l.result_value ").append(compare.getOp()).append(' ').append(compare.getValue());
            whereStarted = true;
        }
        if (drug != null) {
            sql.append(whereStarted ? "\n  AND " : "WHERE ")
               .append("m.drug_name LIKE '%").append(drug).append("%'");
        }
        if (!whereStarted) {
            sql.append("WHERE p.patient_id > 0");
        }
        return sql.toString();
    }
}
