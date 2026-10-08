package com.ontoquery.risk;

import com.ontoquery.support.Evidence;
import com.ontoquery.support.QuestionTokenizer;
import com.ontoquery.support.RiskFinding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 传统管线 SQL 风险检测（8 条规则），基于实际生成的 SQL 与真实数据证据：
 *  R1 FUZZY_LIKE        前导通配 LIKE 作用在 *_name 列（同义词漏配温床）
 *  R2 SYNONYM_EVIDENCE  LIKE 原词与标准码全量的实测差距（跑真实证据查询）
 *  R3 JOIN_FANOUT       JOIN + COUNT(*) 未去重（一对多行数膨胀）
 *  R4 TIME_MISATTACH    时间词应挂的事件日期列与 SQL 实际过滤列不符/缺失
 *  R5 NO_ON_CARTESIAN   JOIN 缺 ON 条件
 *  R6 MISSING_AGG       问"多少"但 SQL 无聚合
 *  R7 NON_MYSQL         非 MySQL 语法（ILIKE / :: / TOP n）
 *  R8 VALUE_RANGE_INFO  本体值域知识可参与而未参与（提示性）
 * 证据查询全部使用 PreparedStatement 参数绑定。
 *
 * @author 月夜烛峰
 */
@Component
public class SqlRiskLinter {

    private static final Logger log = LoggerFactory.getLogger(SqlRiskLinter.class);

    /** 别名.列 LIKE '%词%'（捕获表别名列与词） */
    private static final Pattern FUZZY_LIKE =
            Pattern.compile("(?i)(\\w+)\\.(\\w+)\\s+LIKE\\s+'%([^']+)%'");

    /** COUNT(*) 未去重 */
    private static final Pattern COUNT_STAR = Pattern.compile("(?i)COUNT\\s*\\(\\s*\\*\\s*\\)");

    /** 时间过滤（捕获列名，兼容 DATE_SUB / CURDATE() - INTERVAL 等写法） */
    private static final Pattern DATE_FILTER =
            Pattern.compile("(?i)(\\w+)\\.(\\w+_date)\\s*>=");

    /** JOIN 后无 ON */
    private static final Pattern JOIN_NO_ON = Pattern.compile("(?i)JOIN\\s+(?!\\()(?:(?!\\bON\\b).){40,}");

    /** 非 MySQL 语法 */
    private static final Pattern NON_MYSQL = Pattern.compile("(?i)\\bilike\\b|::|\\btop\\s+\\d+\\b");

    /** name 列 -> code 列 的同义词陷阱表映射 */
    private static final Map<String, String> NAME_CODE_COLUMNS = Map.of(
            "disease_name", "disease_code",
            "drug_name", "drug_code",
            "test_name", "test_code");

    /** 事件词 -> 应挂靠日期列 */
    private static final Map<String, String> EVENT_DATE_COLUMNS = Map.of(
            "诊断", "diagnosis_date",
            "检验", "test_date",
            "化验", "test_date",
            "用药", "prescribe_date",
            "开药", "prescribe_date",
            "处方", "prescribe_date");

    /** 已知检验值域知识（提示性规则用） */
    private static final Map<String, String> LAB_RANGES = Map.of(
            "hbA1c", "糖化血红蛋白正常参考区间 4-6%",
            "糖化", "糖化血红蛋白正常参考区间 4-6%",
            "血糖", "空腹血糖正常参考区间 3.9-6.1 mmol/L");

    private static final int EVIDENCE_VARIANT_LIMIT = 5;

    private final JdbcTemplate jdbcTemplate;

    public SqlRiskLinter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<RiskFinding> lint(String sql, String question) {
        List<RiskFinding> risks = new ArrayList<>(8);
        if (sql == null || sql.isBlank()) {
            return risks;
        }
        checkFuzzyLikeAndSynonym(sql, risks);
        checkJoinFanout(sql, risks);
        checkTimeMisattach(sql, question, risks);
        checkNoOn(sql, risks);
        checkMissingAgg(sql, question, risks);
        checkNonMysql(sql, risks);
        checkValueRangeInfo(sql, question, risks);
        return risks;
    }

    /** R1 + R2：模糊 LIKE 与同义词证据（真实查询实测漏配） */
    private void checkFuzzyLikeAndSynonym(String sql, List<RiskFinding> risks) {
        Matcher m = FUZZY_LIKE.matcher(sql);
        boolean fuzzyFound = false;
        while (m.find()) {
            String column = m.group(2).toLowerCase(Locale.ROOT);
            String term = m.group(3);
            if (!fuzzyFound) {
                fuzzyFound = true;
                risks.add(new RiskFinding("FUZZY_LIKE", "high", "前导通配模糊匹配",
                        String.format("%s LIKE '%%%s%%' 前导通配无法使用索引，且按字面匹配会漏掉同一实体的其他写法",
                                column, term),
                        List.of()));
            }
            String codeColumn = NAME_CODE_COLUMNS.get(column);
            if (codeColumn == null) {
                continue;
            }
            addSynonymEvidence(column, codeColumn, term, risks);
        }
    }

    /** R2：对该 LIKE 词跑真实证据查询，量化漏配 */
    private void addSynonymEvidence(String nameColumn, String codeColumn, String term, List<RiskFinding> risks) {
        String table = tableOf(nameColumn);
        if (table == null) {
            return;
        }
        try {
            String likePattern = "%" + term + "%";
            Long matched = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + nameColumn + " LIKE ?",
                    Long.class, likePattern);
            List<Map<String, Object>> dominant = jdbcTemplate.queryForList(
                    "SELECT " + codeColumn + " AS code, COUNT(*) AS cnt FROM " + table
                            + " WHERE " + codeColumn + " IN (SELECT " + codeColumn + " FROM " + table
                            + " WHERE " + nameColumn + " LIKE ?) GROUP BY " + codeColumn + " ORDER BY cnt DESC LIMIT 1",
                    likePattern);
            if (dominant.isEmpty()) {
                return;
            }
            String code = String.valueOf(dominant.get(0).get("code"));
            Long fullCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + codeColumn + " = ?", Long.class, code);
            Integer variantCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(DISTINCT " + nameColumn + ") FROM " + table + " WHERE " + codeColumn + " = ?",
                    Integer.class, code);
            List<Map<String, Object>> variants = jdbcTemplate.queryForList(
                    "SELECT " + nameColumn + " AS name, COUNT(*) AS cnt FROM " + table
                            + " WHERE " + codeColumn + " = ? GROUP BY " + nameColumn
                            + " ORDER BY cnt DESC LIMIT " + EVIDENCE_VARIANT_LIMIT, code);

            StringBuilder variantText = new StringBuilder(64);
            for (Map<String, Object> v : variants) {
                if (variantText.length() > 0) {
                    variantText.append(" / ");
                }
                variantText.append(v.get("name")).append(" ").append(v.get("cnt"));
            }
            long matchedCount = matched == null ? 0L : matched;
            long total = fullCount == null ? 0L : fullCount;
            long miss = Math.max(0, total - matchedCount);
            String evidenceSql = "SELECT " + nameColumn + ", COUNT(*) FROM " + table
                    + " WHERE " + codeColumn + " = '" + code + "' GROUP BY " + nameColumn;
            risks.add(new RiskFinding("SYNONYM_EVIDENCE", "high", "同义词漏配（实测）",
                    String.format("LIKE '%%%s%%' 实测命中 %d 行；该实体按标准码 %s 全量 %d 行，"
                            + "存在 %d 种写法，漏配 %d 行（%.0f%%）",
                            term, matchedCount, code, total,
                            variantCount == null ? 0 : variantCount, miss,
                            total == 0 ? 0 : miss * 100.0 / total),
                    List.of(new Evidence(evidenceSql, variantText.toString()))));
        } catch (RuntimeException e) {
            log.warn("同义词证据查询失败: {}", e.getMessage());
        }
    }

    /** R3：JOIN + COUNT(*) 未去重 */
    private void checkJoinFanout(String sql, List<RiskFinding> risks) {
        boolean hasJoin = sql.toUpperCase(Locale.ROOT).contains("JOIN");
        boolean hasDistinct = sql.toUpperCase(Locale.ROOT).contains("DISTINCT");
        if (hasJoin && !hasDistinct && COUNT_STAR.matcher(sql).find()) {
            risks.add(new RiskFinding("JOIN_FANOUT", "high", "多表 JOIN 计数膨胀",
                    "JOIN 链上存在一对多关系（患者-诊断、患者-检验），COUNT(*) 统计的是连接后的行数而非患者数，"
                            + "结果随关联表数量膨胀",
                    List.of()));
        }
    }

    /** R4：时间词挂错列或缺失（SQL 过滤了多个事件日期列时，期望列在其中即视为挂靠正确） */
    private void checkTimeMisattach(String sql, String question, List<RiskFinding> risks) {
        QuestionTokenizer.TimeWindow time = QuestionTokenizer.extractTimeWindow(question);
        if (time == null) {
            return;
        }
        String expected = expectedDateColumn(question);
        Matcher m = DATE_FILTER.matcher(sql);
        Set<String> actualColumns = new LinkedHashSet<>(4);
        while (m.find()) {
            actualColumns.add(m.group(2).toLowerCase(Locale.ROOT));
        }
        if (actualColumns.isEmpty()) {
            risks.add(new RiskFinding("TIME_MISATTACH", "medium", "时间条件缺失",
                    String.format("问题包含时间词「%s」（应过滤 %s），SQL 中未生成任何时间过滤条件", time.getLabel(), expected),
                    List.of()));
            return;
        }
        if (expected != null && !actualColumns.contains(expected)) {
            risks.add(new RiskFinding("TIME_MISATTACH", "medium", "时间修饰挂错列",
                    String.format("问题时间词「%s」修饰的事件应过滤 %s，SQL 实际过滤了 %s，"
                            + "语义随数据分布产生偏差", time.getLabel(), expected, String.join(" / ", actualColumns)),
                    List.of()));
        }
    }

    /** 时间词就近的事件词对应的日期列：先向左找最近事件词，左侧没有再向右找 */
    private String expectedDateColumn(String question) {
        QuestionTokenizer.TimeWindow time = QuestionTokenizer.extractTimeWindow(question);
        if (time == null) {
            return null;
        }
        int timeIdx = Math.max(question.indexOf(time.getLabel()), 0);
        String before = question.substring(0, timeIdx);
        String after = question.substring(timeIdx + time.getLabel().length());
        String expected = nearestEventColumn(before, true);
        if (expected == null) {
            expected = nearestEventColumn(after, false);
        }
        return expected;
    }

    /** 片段内最近事件词的日期列（last=true 取最靠右，否则取最靠左） */
    private String nearestEventColumn(String segment, boolean last) {
        String expected = null;
        int bestIdx = -1;
        for (Map.Entry<String, String> e : EVENT_DATE_COLUMNS.entrySet()) {
            int idx = last ? segment.lastIndexOf(e.getKey()) : segment.indexOf(e.getKey());
            if (idx < 0) {
                continue;
            }
            boolean nearer = last ? idx > bestIdx : bestIdx < 0 || idx < bestIdx;
            if (nearer) {
                bestIdx = idx;
                expected = e.getValue();
            }
        }
        return expected;
    }

    /** R5：JOIN 缺 ON */
    private void checkNoOn(String sql, List<RiskFinding> risks) {
        if (JOIN_NO_ON.matcher(sql).find()) {
            risks.add(new RiskFinding("NO_ON_CARTESIAN", "high", "JOIN 缺少关联条件",
                    "存在未带 ON 条件的 JOIN，将产生笛卡尔积", List.of()));
        }
    }

    /** R6：问数量但无聚合 */
    private void checkMissingAgg(String sql, String question, List<RiskFinding> risks) {
        boolean asksCount = question.contains("多少") || question.contains("几") || question.contains("数量");
        boolean hasAgg = sql.toUpperCase(Locale.ROOT).contains("COUNT(")
                || sql.toUpperCase(Locale.ROOT).contains("SUM(")
                || sql.toUpperCase(Locale.ROOT).contains("AVG(");
        if (asksCount && !hasAgg) {
            risks.add(new RiskFinding("MISSING_AGG", "medium", "缺少聚合",
                    "问题询问数量，SQL 未包含聚合函数", List.of()));
        }
    }

    /** R7：非 MySQL 语法 */
    private void checkNonMysql(String sql, List<RiskFinding> risks) {
        if (NON_MYSQL.matcher(sql).find()) {
            risks.add(new RiskFinding("NON_MYSQL", "medium", "非 MySQL 语法",
                    "包含 PostgreSQL 或 T-SQL 语法（ILIKE / :: / TOP n），MySQL 无法执行", List.of()));
        }
    }

    /** R8：值域知识未参与（提示性） */
    private void checkValueRangeInfo(String sql, String question, List<RiskFinding> risks) {
        boolean comparesValue = sql.toUpperCase(Locale.ROOT).contains("RESULT_VALUE");
        if (!comparesValue) {
            return;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : LAB_RANGES.entrySet()) {
            if (lower.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                risks.add(new RiskFinding("VALUE_RANGE_INFO", "low", "值域知识未参与",
                        e.getValue() + "；本体侧会用值域规则校验阈值合理性，传统侧仅按字面比较",
                        List.of()));
                return;
            }
        }
    }

    /** name 列 -> 所属表 */
    private String tableOf(String nameColumn) {
        switch (nameColumn) {
            case "disease_name":
                return "fact_diagnosis";
            case "drug_name":
                return "fact_medication";
            case "test_name":
                return "fact_lab_result";
            default:
                return null;
        }
    }
}
