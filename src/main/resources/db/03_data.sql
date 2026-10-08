-- ----------------------------------------------------------------------------
-- OntoQuery 业务数据确定性生成（dim_* / fact_*）
-- 约束：禁 RAND / 存储过程 / 视图 / 递归 CTE / 外键 / 临时表（账号无 CREATE TEMPORARY TABLES 权限）；
--       digit 内联派生表（0-9 UNION ALL）CROSS JOIN 展开 +
--       算术散列；日期锚 CURDATE()。幂等：TRUNCATE 后重插，重复执行结果完全一致。
-- 维度数据与 02_ontology_seed.sql 严格对齐（id 同源：疾病 1-80 / 药品 81-140 /
-- 检验 141-170 / 科室 171-182）。
-- 核心队列设计（E11 = 2型糖尿病）：
--   患者 20000 名，patient_id = 1..20000；E11 队列 patient_id % 10 = 0（2000 名，k = patient_id/10）；
--   E11 诊断日期 = CURDATE() - (patient_id % 40) 天，disease_name 按 k%4 散布四种写法；
--   二甲双胍队列 k % 3 = 0（666 名），drug_name 按 (k DIV 3)%3 散布三种写法；
--   HbA1c 全队列 2000 行，result_value = 6.5 + (k%30)/10，test_name 按 (k*2)%3 散布。
-- 作者：月夜烛峰
-- ----------------------------------------------------------------------------

USE ontoquery;

TRUNCATE TABLE dim_department;
TRUNCATE TABLE dim_disease;
TRUNCATE TABLE dim_drug;
TRUNCATE TABLE dim_lab_test;
TRUNCATE TABLE dim_patient;
TRUNCATE TABLE fact_visit;
TRUNCATE TABLE fact_diagnosis;
TRUNCATE TABLE fact_lab_result;
TRUNCATE TABLE fact_medication;

-- ----------------------------------------------------------------------------
-- 一、维度表（与 ont_instance 同源对齐）
-- ----------------------------------------------------------------------------

INSERT INTO dim_department (id, dept_code, dept_name)
SELECT i.id, i.code, i.name_cn
FROM ont_instance i JOIN ont_class c ON c.id = i.class_id
WHERE c.code = 'CLS_DEPARTMENT'
ORDER BY i.id;

INSERT INTO dim_disease (id, disease_code, disease_name, icd_chapter)
SELECT i.id, i.code, i.name_cn,
       CASE LEFT(i.code, 1)
           WHEN 'E' THEN 'E00-E90 内分泌、营养和代谢疾病'
           WHEN 'I' THEN 'I00-I99 循环系统疾病'
           WHEN 'J' THEN 'J00-J99 呼吸系统疾病'
           WHEN 'K' THEN 'K00-K93 消化系统疾病'
           WHEN 'N' THEN 'N00-N99 泌尿生殖系统疾病'
           WHEN 'D' THEN 'D50-D89 血液及造血器官疾病'
           WHEN 'C' THEN 'C00-C97 肿瘤'
           WHEN 'F' THEN 'F00-F99 精神和行为障碍'
           WHEN 'M' THEN 'M00-M99 肌肉骨骼系统和结缔组织疾病'
       END
FROM ont_instance i JOIN ont_class c ON c.id = i.class_id
WHERE c.code IN ('CLS_DISEASE', 'CLS_DIABETES')
ORDER BY i.id;

-- 药理分类来自 ont_instance.remark（02 种子中已按药品写入）
INSERT INTO dim_drug (id, drug_code, drug_name, drug_category)
SELECT i.id, i.code, i.name_cn, i.remark
FROM ont_instance i JOIN ont_class c ON c.id = i.class_id
WHERE c.code IN ('CLS_DRUG', 'CLS_ORAL_HYPO', 'CLS_INSULIN')
ORDER BY i.id;

-- 检验项目维度（参考范围等数值仅此处维护，id 与 ont_instance 一致）
INSERT INTO dim_lab_test (id, test_code, test_name, specimen, unit, ref_low, ref_high) VALUES
(141, 'LAB_HBA1C',           '糖化血红蛋白',           '全血', '%',          4.00,   6.00),
(142, 'LAB_FBG',             '空腹血糖',               '血清', 'mmol/L',     3.90,   6.10),
(143, 'LAB_OGTT',            '口服葡萄糖耐量试验',     '血清', 'mmol/L',     3.90,   7.80),
(144, 'LAB_RANDOM_BG',       '随机血糖',               '血清', 'mmol/L',     3.90,  11.10),
(145, 'LAB_INSULIN_FASTING', '空腹胰岛素',             '血清', 'mIU/L',      2.60,  24.90),
(146, 'LAB_CPEPTIDE',        '空腹C肽',                '血清', 'ng/mL',      1.10,   4.40),
(147, 'LAB_TC',              '总胆固醇',               '血清', 'mmol/L',     2.80,   5.70),
(148, 'LAB_TG',              '甘油三酯',               '血清', 'mmol/L',     0.40,   1.70),
(149, 'LAB_HDL',             '高密度脂蛋白胆固醇',     '血清', 'mmol/L',     1.16,   1.42),
(150, 'LAB_LDL',             '低密度脂蛋白胆固醇',     '血清', 'mmol/L',     1.90,   3.10),
(151, 'LAB_ALT',             '丙氨酸氨基转移酶',       '血清', 'U/L',        7.00,  40.00),
(152, 'LAB_AST',             '天门冬氨酸氨基转移酶',   '血清', 'U/L',       13.00,  35.00),
(153, 'LAB_GGT',             'γ-谷氨酰转肽酶',         '血清', 'U/L',       10.00,  60.00),
(154, 'LAB_TBIL',            '总胆红素',               '血清', 'umol/L',     3.40,  17.10),
(155, 'LAB_DBIL',            '直接胆红素',             '血清', 'umol/L',     0.00,   6.80),
(156, 'LAB_ALB',             '白蛋白',                 '血清', 'g/L',       40.00,  55.00),
(157, 'LAB_UREA',            '尿素',                   '血清', 'mmol/L',     2.90,   8.20),
(158, 'LAB_CREAT',           '血肌酐',                 '血清', 'umol/L',    57.00,  97.00),
(159, 'LAB_EGFR',            '估算肾小球滤过率',       '血清', 'ml/min',    90.00, 120.00),
(160, 'LAB_URIC_ACID',       '血尿酸',                 '血清', 'umol/L',   208.00, 428.00),
(161, 'LAB_UACR',            '尿微量白蛋白肌酐比',     '尿液', 'mg/g',       0.00,  30.00),
(162, 'LAB_K',               '血钾',                   '血清', 'mmol/L',     3.50,   5.30),
(163, 'LAB_NA',              '血钠',                   '血清', 'mmol/L',   137.00, 147.00),
(164, 'LAB_CA',              '血钙',                   '血清', 'mmol/L',     2.11,   2.52),
(165, 'LAB_HGB',             '血红蛋白',               '全血', 'g/L',      115.00, 150.00),
(166, 'LAB_WBC',             '白细胞计数',             '全血', '10^9/L',     3.50,   9.50),
(167, 'LAB_PLT',             '血小板计数',             '全血', '10^9/L',   125.00, 350.00),
(168, 'LAB_TSH',             '促甲状腺激素',           '血清', 'mIU/L',      0.55,   4.78),
(169, 'LAB_FT3',             '游离三碘甲状腺原氨酸',   '血清', 'pmol/L',     3.50,   6.50),
(170, 'LAB_FT4',             '游离甲状腺素',           '血清', 'pmol/L',    10.60,  21.20);

-- ----------------------------------------------------------------------------
-- 二、患者维度（20000 行，patient_id = 1..20000）
-- ----------------------------------------------------------------------------

INSERT INTO dim_patient (patient_id, patient_name, gender, birth_date, region, insurance_type)
SELECT n,
       CONCAT('患者', LPAD(n, 6, '0')),
       IF(n % 2 = 0, 'F', 'M'),
       DATE_SUB(DATE_SUB(CURDATE(), INTERVAL (25 + n % 50) YEAR), INTERVAL (n % 360) DAY),
       ELT(1 + (n * 7 % 8), '北京市', '上海市', '广州市', '深圳市', '杭州市', '成都市', '武汉市', '西安市'),
       ELT(1 + (n % 3), '城镇职工', '城乡居民', '自费')
FROM (SELECT a.d * 10000 + b.d * 1000 + c.d * 100 + e.d * 10 + f.d + 1 AS n
      FROM (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS a CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS b CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS c
           CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS e CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS f) t
WHERE n <= 20000;

-- ----------------------------------------------------------------------------
-- 三、就诊事实（40000 行，visit_id = 1..40000；每名患者恰好 2 次）
-- ----------------------------------------------------------------------------

INSERT INTO fact_visit (visit_id, patient_id, dept_code, visit_type, visit_date)
SELECT n,
       (n * 13 % 20000) + 1,
       ELT(1 + (n * 7 % 12), 'DEPT_ENDO', 'DEPT_CARD', 'DEPT_RESPI', 'DEPT_DIGEST', 'DEPT_NEURO',
           'DEPT_NEPHRO', 'DEPT_ORTH', 'DEPT_GEN_SURG', 'DEPT_GYNAE', 'DEPT_OPH', 'DEPT_EMERG', 'DEPT_GP'),
       ELT(1 + (n % 5), '门诊', '门诊', '住院', '急诊', '门诊'),
       DATE_ADD(CURDATE() - INTERVAL (n % 400) DAY, INTERVAL (8 + n % 10) HOUR)
FROM (SELECT a.d * 10000 + b.d * 1000 + c.d * 100 + e.d * 10 + f.d + 1 AS n
      FROM (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS a CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS b CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS c
           CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS e CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS f) t
WHERE n <= 40000;

-- ----------------------------------------------------------------------------
-- 四、糖尿病队列（核心演示目标：同义词漏配成为真实数据效应）
-- 队列定义 k = patient_id / 10，k = 1..2000，patient_id = 10k 恰为 patient_id%10=0。
-- ----------------------------------------------------------------------------

-- 4.1 E11 诊断 2000 行：disease_name 按 k%4 散布，仅 '2型糖尿病' 可被 LIKE '%2型糖尿病%' 命中；
--     诊断日期 CURDATE()-(patient_id%40)，即偏移 0/10/20/30 天。
INSERT INTO fact_diagnosis (patient_id, visit_id, disease_code, disease_name, diagnosis_type, diagnosis_date)
SELECT 10 * k,
       (k * 7 % 40000) + 1,
       'E11',
       ELT(1 + (k % 4), '2型糖尿病', '糖尿病II型', 'II型糖尿病', 'diabetes mellitus type 2'),
       '主要',
       CURDATE() - INTERVAL (10 * k % 40) DAY
FROM (SELECT a.d * 1000 + b.d * 100 + c.d * 10 + e.d + 1 AS k
      FROM (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS a CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS b CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS c CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS e) t
WHERE k <= 2000;

-- 4.2 HbA1c 检验 2000 行：test_name 按 (k*2)%3 散布三种写法；值 6.5+(k%30)/10，DECIMAL 两位。
INSERT INTO fact_lab_result (patient_id, visit_id, test_code, test_name, result_value, unit, is_abnormal, ref_low, ref_high, test_date)
SELECT 10 * k,
       (k * 11 % 40000) + 1,
       'LAB_HBA1C',
       ELT(1 + (k * 2 % 3), '糖化血红蛋白', 'HbA1c', 'HbA1c%'),
       ROUND(6.5 + (k % 30) / 10, 2),
       '%',
       1,
       4.00,
       6.00,
       CURDATE() - INTERVAL (k % 30) DAY
FROM (SELECT a.d * 1000 + b.d * 100 + c.d * 10 + e.d + 1 AS k
      FROM (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS a CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS b CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS c CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS e) t
WHERE k <= 2000;

-- 4.3 二甲双胍用药 666 行（k%3=0，m=k DIV 3）：drug_name 按 m%3 散布，仅 '二甲双胍' 与
--     '盐酸二甲双胍片' 可被 LIKE '%二甲双胍%' 命中，'格华止' 漏配。
INSERT INTO fact_medication (patient_id, visit_id, drug_code, drug_name, dosage, unit, frequency, prescribe_date)
SELECT 10 * k,
       (k * 13 % 40000) + 1,
       'D_METFORMIN',
       ELT(1 + (k DIV 3 % 3), '二甲双胍', '格华止', '盐酸二甲双胍片'),
       500 + 250 * (k DIV 3 % 2),
       'mg',
       ELT(1 + (k DIV 6 % 3), 'qd', 'bid', 'bid'),
       CURDATE() - INTERVAL (k DIV 3 % 90) DAY
FROM (SELECT a.d * 1000 + b.d * 100 + c.d * 10 + e.d + 1 AS k
      FROM (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS a CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS b CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS c CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS e) t
WHERE k <= 2000 AND k % 3 = 0;

-- ----------------------------------------------------------------------------
-- 五、常规业务数据（哈希散布多种疾病/检验/药品，日期 CURDATE()-(n%400) 天）
-- 通用段刻意避开 E11 / LAB_HBA1C / D_METFORMIN（由队列段独占），保证核心问题计数确定。
-- ----------------------------------------------------------------------------

-- 5.1 常规诊断 58000 行（跳过 id=2 的 E11；disease_name 恒为标准名）
INSERT INTO fact_diagnosis (patient_id, visit_id, disease_code, disease_name, diagnosis_type, diagnosis_date)
SELECT ((n * 7 % 20000) + (n DIV 20000) * 3) % 20000 + 1,
       (n * 11 % 40000) + 1,
       dd.disease_code,
       dd.disease_name,
       ELT(1 + (n % 5), '主要', '主要', '主要', '并发', '可疑'),
       CURDATE() - INTERVAL (n % 400) DAY
FROM (SELECT a.d * 10000 + b.d * 1000 + c.d * 100 + e.d * 10 + f.d + 1 AS n
      FROM (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS a CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS b CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS c
           CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS e CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS f) t
JOIN dim_disease dd
  ON dd.id = CASE WHEN 1 + ((n * 31 + (n * 7 % 20000) DIV 61) % 79) < 2
                  THEN 1 + ((n * 31 + (n * 7 % 20000) DIV 61) % 79)
                  ELSE 2 + ((n * 31 + (n * 7 % 20000) DIV 61) % 79) END
WHERE n <= 58000;

-- 5.2 常规检验 118000 行（跳过 id=141 的 LAB_HBA1C；结果值由参考范围派生，约两成异常）
INSERT INTO fact_lab_result (patient_id, visit_id, test_code, test_name, result_value, unit, is_abnormal, ref_low, ref_high, test_date)
SELECT ((n * 7 % 20000) + (n DIV 20000) * 11) % 20000 + 1,
       (n * 11 % 40000) + 1,
       dlt.test_code,
       dlt.test_name,
       ROUND(dlt.ref_low + (n * 13 % 100) / 100 * (dlt.ref_high - dlt.ref_low + 2), 2),
       dlt.unit,
       IF(ROUND(dlt.ref_low + (n * 13 % 100) / 100 * (dlt.ref_high - dlt.ref_low + 2), 2) > dlt.ref_high, 1, 0),
       dlt.ref_low,
       dlt.ref_high,
       CURDATE() - INTERVAL (n % 400) DAY
FROM (SELECT a.d * 100000 + b.d * 10000 + c.d * 1000 + e.d * 100 + f.d * 10 + g.d + 1 AS n
      FROM (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS a CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS b CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS c CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS e
           CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS f CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS g) t
JOIN dim_lab_test dlt
  ON dlt.id = 141 + 1 + ((n * 37 + (n * 7 % 20000) DIV 53) % 29)
WHERE n <= 118000;

-- 5.3 常规用药 89334 行（跳过 id=81 的 D_METFORMIN；胰岛素剂量单位 IU）
INSERT INTO fact_medication (patient_id, visit_id, drug_code, drug_name, dosage, unit, frequency, prescribe_date)
SELECT ((n * 7 % 20000) + (n DIV 20000) * 17) % 20000 + 1,
       (n * 11 % 40000) + 1,
       dd.drug_code,
       dd.drug_name,
       CASE WHEN dd.drug_code LIKE 'D_INSULIN%' THEN 10 + (n % 4) * 6 ELSE 50 * (1 + n % 4) END,
       CASE WHEN dd.drug_code LIKE 'D_INSULIN%' THEN 'IU' ELSE 'mg' END,
       ELT(1 + (n % 3), 'qd', 'bid', 'tid'),
       CURDATE() - INTERVAL (n % 400) DAY
FROM (SELECT a.d * 100000 + b.d * 10000 + c.d * 1000 + e.d * 100 + f.d * 10 + g.d + 1 AS n
      FROM (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS a CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS b CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS c CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS e
           CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS f CROSS JOIN (SELECT 0 AS d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) AS g) t
JOIN dim_drug dd
  ON dd.id = 81 + 1 + ((n * 37 + (n * 7 % 20000) DIV 47) % 59)
WHERE n <= 89334;

-- ----------------------------------------------------------------------------
-- 六、行数自检（应输出 12 / 80 / 60 / 30 / 20000 / 40000 / 60000 / 120000 / 90000）
-- ----------------------------------------------------------------------------

SELECT (SELECT COUNT(*) FROM dim_department) AS dept_cnt,
       (SELECT COUNT(*) FROM dim_disease)    AS disease_cnt,
       (SELECT COUNT(*) FROM dim_drug)       AS drug_cnt,
       (SELECT COUNT(*) FROM dim_lab_test)   AS lab_cnt,
       (SELECT COUNT(*) FROM dim_patient)    AS patient_cnt,
       (SELECT COUNT(*) FROM fact_visit)     AS visit_cnt,
       (SELECT COUNT(*) FROM fact_diagnosis) AS diagnosis_cnt,
       (SELECT COUNT(*) FROM fact_lab_result) AS lab_result_cnt,
       (SELECT COUNT(*) FROM fact_medication) AS medication_cnt;
