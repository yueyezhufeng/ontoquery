package com.ontoquery.ontology.ops;

import java.util.List;

/**
 * 本体运维请求体集合（契约第 9 节）。
 *
 * @author 月夜烛峰
 */
public final class OpsRequests {

    private OpsRequests() {
    }

    /** 创建实例：可携带同义词与物理映射 */
    public static class CreateInstanceBody {
        private String classCode;
        private String code;
        private String nameCn;
        private String remark;
        private List<String> synonyms;
        private MappingBody mapping;

        public String getClassCode() { return classCode; }
        public void setClassCode(String v) { this.classCode = v; }
        public String getCode() { return code; }
        public void setCode(String v) { this.code = v; }
        public String getNameCn() { return nameCn; }
        public void setNameCn(String v) { this.nameCn = v; }
        public String getRemark() { return remark; }
        public void setRemark(String v) { this.remark = v; }
        public List<String> getSynonyms() { return synonyms; }
        public void setSynonyms(List<String> v) { this.synonyms = v; }
        public MappingBody getMapping() { return mapping; }
        public void setMapping(MappingBody v) { this.mapping = v; }

        @Override
        public String toString() {
            return "CreateInstanceBody{classCode='" + classCode + "', code='" + code
                    + "', nameCn='" + nameCn + "', synonyms=" + synonyms + ", mapping=" + mapping + "}";
        }
    }

    /** 实例映射（表/列/取值表达式） */
    public static class MappingBody {
        private String table;
        private String column;
        private String valueExpr;

        public String getTable() { return table; }
        public void setTable(String v) { this.table = v; }
        public String getColumn() { return column; }
        public void setColumn(String v) { this.column = v; }
        public String getValueExpr() { return valueExpr; }
        public void setValueExpr(String v) { this.valueExpr = v; }

        @Override
        public String toString() {
            return "MappingBody{table='" + table + "', column='" + column + "', valueExpr='" + valueExpr + "'}";
        }
    }

    /** 改名/备注（code 不可改；null 字段不改，空串可清空 remark） */
    public static class UpdateInstanceBody {
        private String code;
        private String nameCn;
        private String remark;

        public String getCode() { return code; }
        public void setCode(String v) { this.code = v; }
        public String getNameCn() { return nameCn; }
        public void setNameCn(String v) { this.nameCn = v; }
        public String getRemark() { return remark; }
        public void setRemark(String v) { this.remark = v; }

        @Override
        public String toString() {
            return "UpdateInstanceBody{code='" + code + "', nameCn='" + nameCn + "'}";
        }
    }

    /** 按 code 定位 */
    public static class CodeBody {
        private String code;

        public String getCode() { return code; }
        public void setCode(String v) { this.code = v; }

        @Override
        public String toString() { return "CodeBody{code='" + code + "'}"; }
    }

    /** 加同义词：term + 目标实例 code */
    public static class SynonymBody {
        private String term;
        private String targetCode;

        public String getTerm() { return term; }
        public void setTerm(String v) { this.term = v; }
        public String getTargetCode() { return targetCode; }
        public void setTargetCode(String v) { this.targetCode = v; }

        @Override
        public String toString() { return "SynonymBody{term='" + term + "', targetCode='" + targetCode + "'}"; }
    }

    /** 删同义词：仅 term */
    public static class TermBody {
        private String term;

        public String getTerm() { return term; }
        public void setTerm(String v) { this.term = v; }

        @Override
        public String toString() { return "TermBody{term='" + term + "'}"; }
    }

    /** 改实例映射 */
    public static class MappingUpdateBody {
        private String instanceCode;
        private String table;
        private String column;
        private String valueExpr;

        public String getInstanceCode() { return instanceCode; }
        public void setInstanceCode(String v) { this.instanceCode = v; }
        public String getTable() { return table; }
        public void setTable(String v) { this.table = v; }
        public String getColumn() { return column; }
        public void setColumn(String v) { this.column = v; }
        public String getValueExpr() { return valueExpr; }
        public void setValueExpr(String v) { this.valueExpr = v; }

        @Override
        public String toString() {
            return "MappingUpdateBody{instanceCode='" + instanceCode + "', table='" + table + "'}";
        }
    }
}
