-- ----------------------------------------------------------------------------
-- OntoQuery 数据库结构（库 ontoquery）
-- 规范依据：《Java开发手册（嵩山版）》MySQL 建表规约
--   表名/字段名小写下划线、表名单数；布尔语义列 is_xxx TINYINT UNSIGNED；
--   小数一律 DECIMAL；索引命名 pk_/uk_/idx_；每表必备 id/create_time/update_time；
--   禁止外键与级联、禁止存储过程（账号亦无 CREATE ROUTINE 权限）。
-- 幂等：可重复执行（DROP 后重建）。执行需带 --default-character-set=utf8mb4。
-- 作者：月夜烛峰
-- ----------------------------------------------------------------------------

CREATE DATABASE IF NOT EXISTS ontoquery
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

USE ontoquery;

-- ----------------------------------------------------------------------------
-- 一、本体元数据表（ont_*，由 OntologyRepository 启动时全量加载进内存）
-- ----------------------------------------------------------------------------

-- 本体类（概念层级）
DROP TABLE IF EXISTS ont_class;
CREATE TABLE ont_class (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    code        VARCHAR(64)     NOT NULL COMMENT '类编码，如 CLS_PERSON',
    name_cn     VARCHAR(128)    NOT NULL COMMENT '类中文名，如 患者',
    parent_id   BIGINT UNSIGNED          DEFAULT NULL COMMENT '父类 id，顶级为 NULL',
    color       VARCHAR(16)     NOT NULL DEFAULT '#8b5cf6' COMMENT '前端展示色',
    sort_no     INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '展示排序',
    remark      VARCHAR(512)             DEFAULT NULL COMMENT '说明',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_ont_class PRIMARY KEY (id),
    CONSTRAINT uk_ont_class_code UNIQUE (code),
    INDEX idx_ont_class_parent_id (parent_id)
) ENGINE = InnoDB COMMENT = '本体类';

-- 本体实例（类的具体成员，code 即业务标准编码）
DROP TABLE IF EXISTS ont_instance;
CREATE TABLE ont_instance (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    class_id    BIGINT UNSIGNED NOT NULL COMMENT '所属类 id',
    code        VARCHAR(64)     NOT NULL COMMENT '实例编码=业务标准码，如 E11 / D_METFORMIN / LAB_HBA1C',
    name_cn     VARCHAR(128)    NOT NULL COMMENT '标准名，如 2型糖尿病',
    remark      VARCHAR(512)             DEFAULT NULL COMMENT '说明',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_ont_instance PRIMARY KEY (id),
    CONSTRAINT uk_ont_instance_code UNIQUE (code),
    INDEX idx_ont_instance_class_id (class_id)
) ENGINE = InnoDB COMMENT = '本体实例';

-- 同义词表（term 指向实例或类，二者取一）
DROP TABLE IF EXISTS ont_synonym;
CREATE TABLE ont_synonym (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    term        VARCHAR(128)    NOT NULL COMMENT '同义词条，如 格华止',
    instance_id BIGINT UNSIGNED          DEFAULT NULL COMMENT '指向实例 id（与 class_id 二选一）',
    class_id    BIGINT UNSIGNED          DEFAULT NULL COMMENT '指向类 id（类级同义词）',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_ont_synonym PRIMARY KEY (id),
    CONSTRAINT uk_ont_synonym_term UNIQUE (term),
    INDEX idx_ont_synonym_instance_id (instance_id),
    INDEX idx_ont_synonym_class_id (class_id)
) ENGINE = InnoDB COMMENT = '本体同义词';

-- 对象关系（类间语义关系，定义域 -> 值域）
DROP TABLE IF EXISTS ont_relation;
CREATE TABLE ont_relation (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    code             VARCHAR(64)     NOT NULL COMMENT '关系编码，如 REL_DIAGNOSED_WITH',
    name_cn          VARCHAR(128)    NOT NULL COMMENT '关系中文名，如 被诊断为',
    domain_class_id  BIGINT UNSIGNED NOT NULL COMMENT '定义域类 id',
    range_class_id   BIGINT UNSIGNED NOT NULL COMMENT '值域类 id',
    cardinality      VARCHAR(16)     NOT NULL DEFAULT '1:N' COMMENT '基数：1:1 / 1:N / N:1',
    remark           VARCHAR(512)             DEFAULT NULL COMMENT '说明',
    create_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_ont_relation PRIMARY KEY (id),
    CONSTRAINT uk_ont_relation_code UNIQUE (code),
    INDEX idx_ont_relation_domain (domain_class_id),
    INDEX idx_ont_relation_range (range_class_id)
) ENGINE = InnoDB COMMENT = '本体对象关系';

-- 数据属性（类的数值/文本属性，如 患者的检验结果值）
DROP TABLE IF EXISTS ont_attribute;
CREATE TABLE ont_attribute (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    class_id    BIGINT UNSIGNED NOT NULL COMMENT '所属类 id',
    code        VARCHAR(64)     NOT NULL COMMENT '属性编码，如 ATTR_RESULT_VALUE',
    name_cn     VARCHAR(128)    NOT NULL COMMENT '属性中文名，如 结果值',
    data_type   VARCHAR(32)     NOT NULL COMMENT 'decimal / date / varchar',
    unit        VARCHAR(32)              DEFAULT NULL COMMENT '单位，如 %',
    value_low   DECIMAL(10, 2)           DEFAULT NULL COMMENT '正常值下限（值域知识）',
    value_high  DECIMAL(10, 2)           DEFAULT NULL COMMENT '正常值上限（值域知识）',
    remark      VARCHAR(512)             DEFAULT NULL COMMENT '说明',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_ont_attribute PRIMARY KEY (id),
    CONSTRAINT uk_ont_attribute UNIQUE (class_id, code),
    INDEX idx_ont_attribute_class_id (class_id)
) ENGINE = InnoDB COMMENT = '本体数据属性';

-- 映射表（本体元素 -> 物理表/列/取值表达式，PathToSqlBuilder 据此生成 SQL）
--   kind=class     : 类 -> 物理表（table_name），锚表（如 CLS_PERSON -> dim_patient）
--   kind=relation  : 关系 -> 事件表 join 条件，join_condition 中 {t} 为事件表别名占位符、
--                    {a} 为锚表别名占位符，如 '{t}.patient_id = {a}.patient_id'
--   kind=attribute : 属性 -> 取值表达式，value_expr 中 {t} 为所在表别名占位符，如 '{t}.result_value'
--   kind=instance  : 实例 -> 过滤谓词，value_expr 中 {t} 为所在表别名占位符，如 "{t}.disease_code = 'E11'"
DROP TABLE IF EXISTS ont_mapping;
CREATE TABLE ont_mapping (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    kind           VARCHAR(16)     NOT NULL COMMENT 'class / relation / attribute / instance',
    ref_id         BIGINT UNSIGNED NOT NULL COMMENT '指向 ont_class/ont_relation/ont_attribute/ont_instance 的 id',
    table_name     VARCHAR(64)              DEFAULT NULL COMMENT 'kind=class/relation/attribute 时有效',
    column_name    VARCHAR(64)              DEFAULT NULL COMMENT 'kind=attribute 时有效',
    join_condition VARCHAR(512)             DEFAULT NULL COMMENT 'kind=relation 时有效，含 {t} {a} 占位符',
    value_expr     VARCHAR(512)             DEFAULT NULL COMMENT 'kind=instance/attribute 时有效，含 {t} 占位符',
    create_time    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_ont_mapping PRIMARY KEY (id),
    CONSTRAINT uk_ont_mapping UNIQUE (kind, ref_id),
    INDEX idx_ont_mapping_kind_ref (kind, ref_id)
) ENGINE = InnoDB COMMENT = '本体到数据源映射';

-- 推理规则（如时间就近挂靠、值域校验），config 为 JSON 文本
DROP TABLE IF EXISTS ont_rule;
CREATE TABLE ont_rule (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    rule_code   VARCHAR(64)     NOT NULL COMMENT '规则编码，如 TIME_ATTACH_PROXIMITY',
    rule_type   VARCHAR(32)     NOT NULL COMMENT 'time_attach / value_range / subclass_closure',
    name_cn     VARCHAR(128)    NOT NULL COMMENT '规则中文名',
    config      TEXT            NOT NULL COMMENT '规则配置 JSON',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_ont_rule PRIMARY KEY (id),
    CONSTRAINT uk_ont_rule_code UNIQUE (rule_code)
) ENGINE = InnoDB COMMENT = '本体推理规则';

-- ----------------------------------------------------------------------------
-- 二、业务数据表（dim_* 维度 / fact_* 事实；禁外键，仅索引）
-- ----------------------------------------------------------------------------

DROP TABLE IF EXISTS dim_department;
CREATE TABLE dim_department (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    dept_code   VARCHAR(32)     NOT NULL COMMENT '科室编码',
    dept_name   VARCHAR(64)     NOT NULL COMMENT '科室名称',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_dim_department PRIMARY KEY (id),
    CONSTRAINT uk_dim_department_code UNIQUE (dept_code)
) ENGINE = InnoDB COMMENT = '科室维度';

DROP TABLE IF EXISTS dim_disease;
CREATE TABLE dim_disease (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    disease_code VARCHAR(32)    NOT NULL COMMENT 'ICD-10 编码，如 E11',
    disease_name VARCHAR(128)   NOT NULL COMMENT '标准疾病名',
    icd_chapter VARCHAR(32)     NOT NULL COMMENT 'ICD 章，如 E00-E90 内分泌',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_dim_disease PRIMARY KEY (id),
    CONSTRAINT uk_dim_disease_code UNIQUE (disease_code)
) ENGINE = InnoDB COMMENT = '疾病维度';

DROP TABLE IF EXISTS dim_drug;
CREATE TABLE dim_drug (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    drug_code   VARCHAR(32)     NOT NULL COMMENT '药品编码，如 D_METFORMIN',
    drug_name   VARCHAR(128)    NOT NULL COMMENT '标准药品名',
    drug_category VARCHAR(64)   NOT NULL COMMENT '药理分类，如 双胍类口服降糖药',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_dim_drug PRIMARY KEY (id),
    CONSTRAINT uk_dim_drug_code UNIQUE (drug_code)
) ENGINE = InnoDB COMMENT = '药品维度';

DROP TABLE IF EXISTS dim_lab_test;
CREATE TABLE dim_lab_test (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    test_code   VARCHAR(32)     NOT NULL COMMENT '检验编码，如 LAB_HBA1C',
    test_name   VARCHAR(128)    NOT NULL COMMENT '标准检验名',
    specimen    VARCHAR(64)     NOT NULL COMMENT '标本类型，如 全血',
    unit        VARCHAR(32)     NOT NULL COMMENT '计量单位，如 %',
    ref_low     DECIMAL(10, 2)  NOT NULL COMMENT '参考范围下限',
    ref_high    DECIMAL(10, 2)  NOT NULL COMMENT '参考范围上限',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_dim_lab_test PRIMARY KEY (id),
    CONSTRAINT uk_dim_lab_test_code UNIQUE (test_code)
) ENGINE = InnoDB COMMENT = '检验项目维度';

DROP TABLE IF EXISTS dim_patient;
CREATE TABLE dim_patient (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    patient_id  BIGINT UNSIGNED NOT NULL COMMENT '患者业务编号（确定性生成）',
    patient_name VARCHAR(64)    NOT NULL COMMENT '患者姓名（合成：患者000123）',
    gender      CHAR(1)         NOT NULL COMMENT '性别：M / F',
    birth_date  DATE            NOT NULL COMMENT '出生日期',
    region      VARCHAR(64)     NOT NULL COMMENT '常住地区',
    insurance_type VARCHAR(32)  NOT NULL COMMENT '医保类型：城镇职工 / 城乡居民 / 自费',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_dim_patient PRIMARY KEY (id),
    CONSTRAINT uk_dim_patient_patient_id UNIQUE (patient_id),
    INDEX idx_dim_patient_region (region)
) ENGINE = InnoDB COMMENT = '患者维度';

DROP TABLE IF EXISTS fact_visit;
CREATE TABLE fact_visit (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    visit_id    BIGINT UNSIGNED NOT NULL COMMENT '就诊编号',
    patient_id  BIGINT UNSIGNED NOT NULL COMMENT '患者编号',
    dept_code   VARCHAR(32)     NOT NULL COMMENT '科室编码',
    visit_type  VARCHAR(16)     NOT NULL COMMENT '就诊类型：门诊 / 住院 / 急诊',
    visit_date  DATETIME        NOT NULL COMMENT '就诊时间',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_fact_visit PRIMARY KEY (id),
    CONSTRAINT uk_fact_visit_visit_id UNIQUE (visit_id),
    INDEX idx_fact_visit_patient_id (patient_id),
    INDEX idx_fact_visit_visit_date (visit_date)
) ENGINE = InnoDB COMMENT = '就诊事实';

DROP TABLE IF EXISTS fact_diagnosis;
CREATE TABLE fact_diagnosis (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    patient_id  BIGINT UNSIGNED NOT NULL COMMENT '患者编号',
    visit_id    BIGINT UNSIGNED NOT NULL COMMENT '就诊编号',
    disease_code VARCHAR(32)    NOT NULL COMMENT 'ICD-10 编码',
    disease_name VARCHAR(128)   NOT NULL COMMENT '诊断名称',
    diagnosis_type VARCHAR(16)  NOT NULL COMMENT '诊断类型：主要 / 并发 / 可疑',
    diagnosis_date DATE         NOT NULL COMMENT '诊断日期',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_fact_diagnosis PRIMARY KEY (id),
    INDEX idx_fact_diagnosis_patient_id (patient_id),
    INDEX idx_fact_diagnosis_disease_code (disease_code),
    INDEX idx_fact_diagnosis_diagnosis_date (diagnosis_date)
) ENGINE = InnoDB COMMENT = '诊断事实';

DROP TABLE IF EXISTS fact_lab_result;
CREATE TABLE fact_lab_result (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    patient_id  BIGINT UNSIGNED NOT NULL COMMENT '患者编号',
    visit_id    BIGINT UNSIGNED NOT NULL COMMENT '就诊编号',
    test_code   VARCHAR(32)     NOT NULL COMMENT '检验编码',
    test_name   VARCHAR(128)    NOT NULL COMMENT '检验名称',
    result_value DECIMAL(10, 2) NOT NULL COMMENT '结果值',
    unit        VARCHAR(32)     NOT NULL COMMENT '计量单位',
    is_abnormal TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '是否异常：1 是 / 0 否',
    ref_low     DECIMAL(10, 2)  NOT NULL COMMENT '参考范围下限',
    ref_high    DECIMAL(10, 2)  NOT NULL COMMENT '参考范围上限',
    test_date   DATE            NOT NULL COMMENT '检验日期',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_fact_lab_result PRIMARY KEY (id),
    INDEX idx_fact_lab_result_patient_id (patient_id),
    INDEX idx_fact_lab_result_test_code (test_code),
    INDEX idx_fact_lab_result_test_date (test_date)
) ENGINE = InnoDB COMMENT = '检验结果事实';

DROP TABLE IF EXISTS fact_medication;
CREATE TABLE fact_medication (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    patient_id  BIGINT UNSIGNED NOT NULL COMMENT '患者编号',
    visit_id    BIGINT UNSIGNED NOT NULL COMMENT '就诊编号',
    drug_code   VARCHAR(32)     NOT NULL COMMENT '药品编码',
    drug_name   VARCHAR(128)    NOT NULL COMMENT '药品名称',
    dosage      DECIMAL(10, 2)  NOT NULL COMMENT '单次剂量',
    unit        VARCHAR(32)     NOT NULL COMMENT '剂量单位，如 mg',
    frequency   VARCHAR(16)     NOT NULL COMMENT '频次：qd / bid / tid',
    prescribe_date DATE         NOT NULL COMMENT '开药日期',
    create_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_fact_medication PRIMARY KEY (id),
    INDEX idx_fact_medication_patient_id (patient_id),
    INDEX idx_fact_medication_drug_code (drug_code),
    INDEX idx_fact_medication_prescribe_date (prescribe_date)
) ENGINE = InnoDB COMMENT = '用药事实';

-- ----------------------------------------------------------------------------
-- 三、系统表
-- ----------------------------------------------------------------------------

-- LLM 响应缓存（cache_key = SHA256(model|question|ddlHash)）
-- 说明：response_text 超过 5000 字符按规约应独立成表，此处为演示系统从简（该列不参与索引）。
DROP TABLE IF EXISTS llm_cache;
CREATE TABLE llm_cache (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    cache_key         CHAR(64)        NOT NULL COMMENT 'SHA256 十六进制缓存键',
    model             VARCHAR(64)     NOT NULL COMMENT '模型名',
    question          TEXT            NOT NULL COMMENT '原始问题',
    prompt_tokens     INT UNSIGNED             DEFAULT NULL COMMENT '提示词 tokens',
    completion_tokens INT UNSIGNED             DEFAULT NULL COMMENT '生成 tokens',
    response_text     MEDIUMTEXT      NOT NULL COMMENT '原始响应 content',
    sql_text          TEXT                     DEFAULT NULL COMMENT '抽取出的 SQL',
    create_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_llm_cache PRIMARY KEY (id),
    CONSTRAINT uk_llm_cache_key UNIQUE (cache_key)
) ENGINE = InnoDB COMMENT = 'LLM 响应缓存';

DROP TABLE IF EXISTS benchmark_run;
CREATE TABLE benchmark_run (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    run_mode            VARCHAR(16)     NOT NULL COMMENT '运行模式：mock / llm',
    total_count         INT UNSIGNED    NOT NULL COMMENT '题目总数',
    ontology_correct    INT UNSIGNED    NOT NULL COMMENT '本体侧正确数',
    traditional_correct INT UNSIGNED    NOT NULL COMMENT '传统侧正确数',
    ontology_elapsed_ms BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '本体侧总耗时 ms',
    traditional_elapsed_ms BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '传统侧总耗时 ms',
    create_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_benchmark_run PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT = '基准运行汇总';

DROP TABLE IF EXISTS benchmark_item;
CREATE TABLE benchmark_item (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
    run_id            BIGINT UNSIGNED NOT NULL COMMENT '运行 id',
    question_no       INT UNSIGNED    NOT NULL COMMENT '题号',
    category          VARCHAR(32)     NOT NULL COMMENT '题目类别',
    question          TEXT            NOT NULL COMMENT '题目',
    golden_count      VARCHAR(1024)            DEFAULT NULL COMMENT '基准 SQL 计数结果（标量字符串）',
    ontology_sql      TEXT                     DEFAULT NULL COMMENT '本体侧生成 SQL',
    ontology_count    VARCHAR(1024)            DEFAULT NULL COMMENT '本体侧计数结果',
    ontology_ok       TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '本体侧是否正确：1 是 / 0 否',
    traditional_sql   TEXT                     DEFAULT NULL COMMENT '传统侧生成 SQL',
    traditional_count VARCHAR(1024)            DEFAULT NULL COMMENT '传统侧计数结果',
    traditional_ok    TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '传统侧是否正确：1 是 / 0 否',
    create_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT pk_benchmark_item PRIMARY KEY (id),
    INDEX idx_benchmark_item_run_id (run_id)
) ENGINE = InnoDB COMMENT = '基准运行明细';
