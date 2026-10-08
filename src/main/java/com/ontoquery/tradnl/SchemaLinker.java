package com.ontoquery.tradnl;

import com.ontoquery.support.QuestionTokenizer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 传统管线第二步：问题词与物理模式（列名 + 列注释）的重合分析。
 * 只有字面子串命中，没有同义词与领域知识——命中的进"已匹配"，未命中的进"未解析"，
 * 未解析词正是 LLM 后续"靠猜"的来源。
 *
 * @author 月夜烛峰
 */
@Component
public class SchemaLinker {

    /** 模式列（表名 + 列名 + 中文注释） */
    public record SchemaColumn(String tableName, String columnName, String columnComment) {
    }

    /** 匹配结果 */
    public static final class LinkResult {
        private final List<Match> matches = new ArrayList<>();
        private final List<String> missed = new ArrayList<>();

        public List<Match> getMatches() {
            return matches;
        }

        public List<String> getMissed() {
            return missed;
        }
    }

    /** 单个命中 */
    public static final class Match {
        private final String token;
        private final String tableName;
        private final String columnName;
        /** comment=注释命中，name=列名命中 */
        private final String kind;

        public Match(String token, String tableName, String columnName, String kind) {
            this.token = token;
            this.tableName = tableName;
            this.columnName = columnName;
            this.kind = kind;
        }

        public String getToken() {
            return token;
        }

        public String getTableName() {
            return tableName;
        }

        public String getColumnName() {
            return columnName;
        }

        public String getKind() {
            return kind;
        }

        @Override
        public String toString() {
            return "Match{token='" + token + "', column=" + tableName + "." + columnName + ", kind='" + kind + "'}";
        }
    }

    public LinkResult link(String question, List<SchemaColumn> schemaColumns) {
        LinkResult result = new LinkResult();
        for (String token : QuestionTokenizer.tokens(question)) {
            Match hit = null;
            for (SchemaColumn col : schemaColumns) {
                if (col.columnComment() != null && col.columnComment().contains(token)) {
                    hit = new Match(token, col.tableName(), col.columnName(), "comment");
                    break;
                }
                String lowerToken = token.toLowerCase();
                String lowerColumn = col.columnName().toLowerCase();
                if (lowerToken.length() >= 3 && lowerColumn.contains(lowerToken)) {
                    hit = new Match(token, col.tableName(), col.columnName(), "name");
                    break;
                }
            }
            if (hit != null) {
                result.getMatches().add(hit);
            } else {
                result.getMissed().add(token);
            }
        }
        return result;
    }
}
