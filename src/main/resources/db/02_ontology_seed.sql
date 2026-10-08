-- ----------------------------------------------------------------------------
-- OntoQuery 本体种子数据（ont_* 七张表）
-- 编码约定依据 docs/CONTRACT.md 第 8 节，不得偏离。
-- 幂等：每表 TRUNCATE 后重插，id 显式指定，parent_id/ref_id 引用确定。
-- 分组类（CLS_*_GROUP）不映射物理表；可映射类 9 个。
-- 作者：月夜烛峰
-- ----------------------------------------------------------------------------

USE ontoquery;

TRUNCATE TABLE ont_class;
TRUNCATE TABLE ont_instance;
TRUNCATE TABLE ont_synonym;
TRUNCATE TABLE ont_relation;
TRUNCATE TABLE ont_attribute;
TRUNCATE TABLE ont_mapping;
TRUNCATE TABLE ont_rule;

-- ----------------------------------------------------------------------------
-- 一、本体类（3 个分组类 + 12 个可映射类 + 4 个类目子类）
-- 层级：人员->患者；临床实体->疾病->糖尿病类；临床实体->药品->降糖药->口服降糖药/胰岛素；
--       临床事件->就诊/诊断记录/检验结果/用药记录
-- ----------------------------------------------------------------------------

INSERT INTO ont_class (id, code, name_cn, parent_id, color, sort_no, remark) VALUES
(1,  'CLS_PERSON_GROUP',  '人员',     NULL, '#6366f1', 1, '顶级分组：人员相关概念'),
(2,  'CLS_ENTITY_GROUP',  '临床实体', NULL, '#06b6d4', 2, '顶级分组：临床实体（不直接映射事实表）'),
(3,  'CLS_EVENT_GROUP',   '临床事件', NULL, '#f59e0b', 3, '顶级分组：临床事件'),
(4,  'CLS_PERSON',       '患者',     1,    '#8b5cf6', 1, '锚类，映射 dim_patient'),
(5,  'CLS_DISEASE',       '疾病',     2,    '#f87171', 2, 'ICD-10 疾病概念，映射 dim_disease'),
(6,  'CLS_LAB_TEST',      '检验项目', 2,    '#fbbf24', 3, '检验项目概念，映射 dim_lab_test'),
(7,  'CLS_DRUG',          '药品',     2,    '#34d399', 4, '药品概念，映射 dim_drug'),
(8,  'CLS_DEPARTMENT',    '科室',     2,    '#60a5fa', 5, '科室概念，映射 dim_department'),
(9,  'CLS_VISIT',         '就诊',     3,    '#38bdf8', 6, '就诊事件，映射 fact_visit'),
(10, 'CLS_DIAGNOSIS',     '诊断记录', 3,    '#fb923c', 7, '诊断事件，映射 fact_diagnosis'),
(11, 'CLS_LAB_RESULT',    '检验结果', 3,    '#a78bfa', 8, '检验事件，映射 fact_lab_result'),
(12, 'CLS_MEDICATION',    '用药记录', 3,    '#4ade80', 9, '用药事件，映射 fact_medication'),
(13, 'CLS_DIABETES',      '糖尿病类', 5,    '#ef4444', 2, 'E10-E14 糖尿病类目（子类闭包根）'),
(14, 'CLS_HYPO_DRUG',     '降糖药',   7,    '#10b981', 4, '降糖药类目（口服降糖药 + 胰岛素）'),
(15, 'CLS_ORAL_HYPO',     '口服降糖药', 14, '#34d399', 4, '口服降糖药子类'),
(16, 'CLS_INSULIN',       '胰岛素',   14,   '#059669', 5, '胰岛素子类');

-- ----------------------------------------------------------------------------
-- 二、数据属性（值域知识）
-- ----------------------------------------------------------------------------

INSERT INTO ont_attribute (id, class_id, code, name_cn, data_type, unit, value_low, value_high, remark) VALUES
(1, 11, 'ATTR_RESULT_VALUE', '结果值', 'decimal', '%',    4.00, 6.00, '检验结果值，正常值域 4-6（以糖化血红蛋白为代表）'),
(2, 12, 'ATTR_DOSAGE',       '剂量',   'decimal', 'mg',   NULL, NULL, '单次用药剂量'),
(3, 4,  'ATTR_AGE',          '年龄',   'decimal', '岁',   NULL, NULL, '虚拟属性，由 birth_date 推导，仅展示用'),
(4, 4,  'ATTR_GENDER',       '性别',   'varchar', NULL,   NULL, NULL, '患者维度分组属性：M / F'),
(5, 4,  'ATTR_REGION',       '常住地区', 'varchar', NULL, NULL, NULL, '患者维度分组属性'),
(6, 4,  'ATTR_INSURANCE_TYPE', '医保类型', 'varchar', NULL, NULL, NULL, '患者维度分组属性：城镇职工 / 城乡居民 / 自费'),
(7, 9,  'ATTR_DEPT_CODE',    '科室',   'varchar', NULL,   NULL, NULL, '就诊事实分组属性，值为科室编码'),
(8, 9,  'ATTR_VISIT_TYPE',   '就诊类型', 'varchar', NULL, NULL, NULL, '就诊事实枚举：门诊 / 住院 / 急诊'),
(9, 12, 'ATTR_DRUG_CODE',    '药品',   'varchar', NULL,   NULL, NULL, '用药事实分组属性，值为药品编码'),
(10, 12, 'ATTR_FREQUENCY',   '用药频次', 'varchar', NULL, NULL, NULL, '用药事实枚举：qd / bid / tid'),
(11, 10, 'ATTR_DISEASE_NAME', '名称写法', 'varchar', NULL, NULL, NULL, '诊断事实分组属性，值为诊断名称原文写法'),
(12, 10, 'ATTR_DISEASE_CODE', '诊断编码', 'varchar', NULL, NULL, NULL, '诊断事实分组属性，值为 ICD-10 编码'),
(13, 11, 'ATTR_IS_ABNORMAL',  '是否异常', 'varchar', NULL, NULL, NULL, '检验事实分组属性：1 异常 / 0 正常');

-- ----------------------------------------------------------------------------
-- 三、推理规则（config 为 JSON 文本）
-- ----------------------------------------------------------------------------

INSERT INTO ont_rule (id, rule_code, rule_type, name_cn, config) VALUES
(1, 'TIME_ATTACH_PROXIMITY', 'time_attach',      '时间就近挂靠',
 '{"anchorDateColumn":"diagnosis_date","eventTables":{"fact_diagnosis":"diagnosis_date","fact_lab_result":"test_date","fact_medication":"prescribe_date","fact_visit":"visit_date"},"defaultWindowDays":30,"strategy":"nearest_event"}'),
(2, 'VALUE_RANGE',           'value_range',      '值域校验',
 '{"attributeCode":"ATTR_RESULT_VALUE","refLow":4.0,"refHigh":6.0,"action":"flag","outsidePolicy":"keep_and_risk","flagLevel":"warn"}'),
(3, 'SUBCLASS_CLOSURE',      'subclass_closure', '子类闭包',
 '{"expandDownward":true,"maxDepth":3,"groupClasses":["CLS_PERSON_GROUP","CLS_ENTITY_GROUP","CLS_EVENT_GROUP"],"closureRoots":["CLS_PERSON","CLS_VISIT","CLS_DIAGNOSIS","CLS_LAB_RESULT","CLS_MEDICATION"]}');

-- ----------------------------------------------------------------------------
-- 四、对象关系
-- 患者出发四条 1:N 关系（生成 EXISTS 子查询）；语义边 N:1 仅供推理展示，不生成 JOIN。
-- ----------------------------------------------------------------------------

INSERT INTO ont_relation (id, code, name_cn, domain_class_id, range_class_id, cardinality, remark) VALUES
(1, 'REL_HAS_VISIT',       '就诊于',     4,  9,  '1:N', '患者到就诊，事件表 fact_visit'),
(2, 'REL_DIAGNOSED_WITH',  '被诊断为',   4,  10, '1:N', '患者到诊断记录，事件表 fact_diagnosis'),
(3, 'REL_UNDERGOES_TEST',  '有检验结果', 4,  11, '1:N', '患者到检验结果，事件表 fact_lab_result'),
(4, 'REL_TAKES_DRUG',      '使用药物',   4,  12, '1:N', '患者到用药记录，事件表 fact_medication'),
(5, 'REL_DIAGNOSIS_OF',    '确诊为',     10, 5,  'N:1', '诊断记录指向疾病（语义边，不生成 JOIN）'),
(6, 'REL_TEST_OF',         '检验项目为', 11, 6,  'N:1', '检验结果指向检验项目（语义边，不生成 JOIN）'),
(7, 'REL_DRUG_OF',         '使用药品为', 12, 7,  'N:1', '用药记录指向药品（语义边，不生成 JOIN）'),
(8, 'REL_VISIT_DEPT',      '就诊科室',   9,  8,  'N:1', '就诊指向科室（语义边，不生成 JOIN）');

-- ----------------------------------------------------------------------------
-- 五、映射：类 / 关系 / 属性（实例映射见第六节之后的批量段）
-- ----------------------------------------------------------------------------

INSERT INTO ont_mapping (id, kind, ref_id, table_name, column_name, join_condition, value_expr) VALUES
(1,  'class',     4,  'dim_patient',     NULL,       NULL, NULL),
(2,  'class',     5,  'dim_disease',     NULL,       NULL, NULL),
(3,  'class',     6,  'dim_lab_test',    NULL,       NULL, NULL),
(4,  'class',     7,  'dim_drug',        NULL,       NULL, NULL),
(5,  'class',     8,  'dim_department',  NULL,       NULL, NULL),
(6,  'class',     9,  'fact_visit',      NULL,       NULL, NULL),
(7,  'class',     10, 'fact_diagnosis',  NULL,       NULL, NULL),
(8,  'class',     11, 'fact_lab_result', NULL,       NULL, NULL),
(9,  'class',     12, 'fact_medication', NULL,       NULL, NULL),
(10, 'relation',  1,  'fact_visit',      NULL,       '{t}.patient_id = {a}.patient_id', NULL),
(11, 'relation',  2,  'fact_diagnosis',  NULL,       '{t}.patient_id = {a}.patient_id', NULL),
(12, 'relation',  3,  'fact_lab_result', NULL,       '{t}.patient_id = {a}.patient_id', NULL),
(13, 'relation',  4,  'fact_medication', NULL,       '{t}.patient_id = {a}.patient_id', NULL),
(14, 'attribute', 1,  'fact_lab_result', 'result_value', NULL, '{t}.result_value'),
(15, 'attribute', 2,  'fact_medication', 'dosage',       NULL, '{t}.dosage'),
(16, 'attribute', 3,  'dim_patient',     'birth_date',   NULL, 'TIMESTAMPDIFF(YEAR, {t}.birth_date, CURDATE())'),
(17, 'attribute', 4,  'dim_patient',     'gender',        NULL, '{t}.gender'),
(18, 'attribute', 5,  'dim_patient',     'region',        NULL, '{t}.region'),
(19, 'attribute', 6,  'dim_patient',     'insurance_type', NULL, '{t}.insurance_type'),
(20, 'attribute', 7,  'fact_visit',      'dept_code',     NULL, '{t}.dept_code'),
(21, 'attribute', 8,  'fact_visit',      'visit_type',    NULL, '{t}.visit_type'),
(22, 'attribute', 9,  'fact_medication', 'drug_code',     NULL, '{t}.drug_code'),
(23, 'attribute', 10, 'fact_medication', 'frequency',     NULL, '{t}.frequency'),
(24, 'attribute', 11, 'fact_diagnosis',  'disease_name',  NULL, '{t}.disease_name'),
(25, 'attribute', 12, 'fact_diagnosis',  'disease_code',  NULL, '{t}.disease_code'),
(26, 'attribute', 13, 'fact_lab_result', 'is_abnormal',   NULL, '{t}.is_abnormal');

-- ----------------------------------------------------------------------------
-- 六、本体实例
-- 实例 id 与 03_data.sql 维表 id 保持一致（疾病 1-80 / 药品 81-140 / 检验 141-170 / 科室 171-182）
-- 药品实例的 remark 存放药理分类，供 03_data.sql 生成 dim_drug.drug_category。
-- ----------------------------------------------------------------------------

INSERT INTO ont_instance (id, class_id, code, name_cn, remark) VALUES
-- 疾病（ICD-10，E00-E90 内分泌、营养和代谢疾病）
(1,  5, 'E10',   '1型糖尿病',               'ICD-10 E10'),
(2,  5, 'E11',   '2型糖尿病',               'ICD-10 E11'),
(3,  5, 'E12',   '营养不良相关性糖尿病',     'ICD-10 E12'),
(4,  5, 'E13',   '其他特指的糖尿病',         'ICD-10 E13'),
(5,  5, 'E14',   '未特指的糖尿病',           'ICD-10 E14'),
(6,  5, 'E66.9', '肥胖症',                  'ICD-10 E66.9'),
(7,  5, 'E03.9', '甲状腺功能减退症',         'ICD-10 E03.9'),
(8,  5, 'E05.0', '甲状腺功能亢进症',         'ICD-10 E05.0'),
(9,  5, 'E04.1', '结节性甲状腺肿',           'ICD-10 E04.1'),
(10, 5, 'E78.5', '高脂血症',                'ICD-10 E78.5'),
(11, 5, 'E78.0', '纯高胆固醇血症',           'ICD-10 E78.0'),
(12, 5, 'E78.2', '混合型高脂蛋白血症',       'ICD-10 E78.2'),
(13, 5, 'E88.8', '代谢综合征',              'ICD-10 E88.8'),
(14, 5, 'E28.2', '多囊卵巢综合征',           'ICD-10 E28.2'),
(15, 5, 'E27.1', '原发性肾上腺皮质功能不全', 'ICD-10 E27.1'),
(16, 5, 'E55.9', '维生素D缺乏',             'ICD-10 E55.9'),
-- 疾病（I00-I99 循环系统疾病）
(17, 5, 'I10',   '原发性高血压',            'ICD-10 I10'),
(18, 5, 'I15',   '继发性高血压',            'ICD-10 I15'),
(19, 5, 'I20.9', '心绞痛',                  'ICD-10 I20.9'),
(20, 5, 'I21.9', '急性心肌梗死',            'ICD-10 I21.9'),
(21, 5, 'I25.1', '慢性缺血性心脏病',        'ICD-10 I25.1'),
(22, 5, 'I50.9', '心力衰竭',                'ICD-10 I50.9'),
(23, 5, 'I50.0', '充血性心力衰竭',          'ICD-10 I50.0'),
(24, 5, 'I48',   '心房颤动',                'ICD-10 I48'),
(25, 5, 'I49.9', '心律失常',                'ICD-10 I49.9'),
(26, 5, 'I63.9', '脑梗死',                  'ICD-10 I63.9'),
(27, 5, 'I61',   '脑出血',                  'ICD-10 I61'),
(28, 5, 'I67.9', '脑血管病',                'ICD-10 I67.9'),
(29, 5, 'I80.2', '下肢深静脉血栓形成',      'ICD-10 I80.2'),
(30, 5, 'I26.9', '肺栓塞',                  'ICD-10 I26.9'),
(31, 5, 'I70',   '动脉粥样硬化症',          'ICD-10 I70'),
(32, 5, 'I42.0', '扩张型心肌病',            'ICD-10 I42.0'),
-- 疾病（J00-J99 呼吸系统疾病）
(33, 5, 'J06.9', '急性上呼吸道感染',        'ICD-10 J06.9'),
(34, 5, 'J18.9', '肺炎',                    'ICD-10 J18.9'),
(35, 5, 'J20.9', '急性支气管炎',            'ICD-10 J20.9'),
(36, 5, 'J44',   '慢性阻塞性肺疾病',        'ICD-10 J44'),
(37, 5, 'J45',   '支气管哮喘',              'ICD-10 J45'),
(38, 5, 'J30.4', '过敏性鼻炎',              'ICD-10 J30.4'),
(39, 5, 'J90',   '胸腔积液',                'ICD-10 J90'),
(40, 5, 'J84.1', '特发性肺纤维化',          'ICD-10 J84.1');
INSERT INTO ont_instance (id, class_id, code, name_cn, remark) VALUES
-- 疾病（续）
(41, 5, 'J47',   '支气管扩张症',            'ICD-10 J47'),
(42, 5, 'J03',   '急性扁桃体炎',            'ICD-10 J03'),
-- 疾病（K00-K93 消化系统疾病）
(43, 5, 'K21',   '胃食管反流病',            'ICD-10 K21'),
(44, 5, 'K29',   '胃炎',                    'ICD-10 K29'),
(45, 5, 'K25',   '胃溃疡',                  'ICD-10 K25'),
(46, 5, 'K35',   '急性阑尾炎',              'ICD-10 K35'),
(47, 5, 'K40',   '腹股沟疝',                'ICD-10 K40'),
(48, 5, 'K52',   '非感染性胃肠炎',          'ICD-10 K52'),
(49, 5, 'K58',   '肠易激综合征',            'ICD-10 K58'),
(50, 5, 'K76.0', '脂肪肝',                  'ICD-10 K76.0'),
(51, 5, 'K74.6', '肝硬化',                  'ICD-10 K74.6'),
(52, 5, 'K80',   '胆石症',                  'ICD-10 K80'),
(53, 5, 'K85',   '急性胰腺炎',              'ICD-10 K85'),
(54, 5, 'K59.0', '便秘',                    'ICD-10 K59.0'),
-- 疾病（N00-N99 泌尿生殖系统疾病）
(55, 5, 'N18',   '慢性肾脏病',              'ICD-10 N18'),
(56, 5, 'N17.9', '急性肾损伤',              'ICD-10 N17.9'),
(57, 5, 'N03',   '慢性肾小球肾炎',          'ICD-10 N03'),
(58, 5, 'N20',   '肾结石',                  'ICD-10 N20'),
(59, 5, 'N39.0', '尿路感染',                'ICD-10 N39.0'),
(60, 5, 'N23',   '肾绞痛',                  'ICD-10 N23'),
(61, 5, 'N80',   '子宫内膜异位症',          'ICD-10 N80'),
(62, 5, 'N93.9', '异常子宫出血',            'ICD-10 N93.9'),
(63, 5, 'N76.0', '阴道炎',                  'ICD-10 N76.0'),
(64, 5, 'N28.1', '肾囊肿',                  'ICD-10 N28.1'),
-- 疾病（D50-D89 血液系统疾病）
(65, 5, 'D50',   '缺铁性贫血',              'ICD-10 D50'),
(66, 5, 'D51',   '维生素B12缺乏性贫血',    'ICD-10 D51'),
(67, 5, 'D69.3', '免疫性血小板减少症',      'ICD-10 D69.3'),
(68, 5, 'D69.0', '过敏性紫癜',              'ICD-10 D69.0'),
(69, 5, 'D64.9', '贫血',                    'ICD-10 D64.9'),
(70, 5, 'D69.6', '血小板减少症',            'ICD-10 D69.6'),
-- 疾病（C00-C97 肿瘤）
(71, 5, 'C50',   '乳房恶性肿瘤',            'ICD-10 C50'),
(72, 5, 'C34',   '支气管和肺恶性肿瘤',      'ICD-10 C34'),
(73, 5, 'C18',   '结肠恶性肿瘤',            'ICD-10 C18'),
(74, 5, 'C22',   '肝和肝内胆管恶性肿瘤',    'ICD-10 C22'),
(75, 5, 'C61',   '前列腺恶性肿瘤',          'ICD-10 C61'),
-- 疾病（F00-F99 精神与行为障碍 / M00-M99 肌肉骨骼）
(76, 5, 'F32.9', '抑郁发作',                'ICD-10 F32.9'),
(77, 5, 'F41.9', '焦虑障碍',                'ICD-10 F41.9'),
(78, 5, 'F51.0', '非器质性失眠症',          'ICD-10 F51.0'),
(79, 5, 'M10',   '痛风',                    'ICD-10 M10'),
(80, 5, 'M81',   '骨质疏松症',              'ICD-10 M81');
INSERT INTO ont_instance (id, class_id, code, name_cn, remark) VALUES
-- 药品（81-140；remark 为药理分类，03_data.sql 据此生成 dim_drug.drug_category）
(81,  7, 'D_METFORMIN',          '二甲双胍',          '双胍类口服降糖药'),
(82,  7, 'D_GLIMEPIRIDE',        '格列美脲',          '磺脲类口服降糖药'),
(83,  7, 'D_GLIBENCLAMIDE',      '格列本脲',          '磺脲类口服降糖药'),
(84,  7, 'D_GLICLAZIDE',         '格列齐特',          '磺脲类口服降糖药'),
(85,  7, 'D_GLIPIZIDE',          '格列吡嗪',          '磺脲类口服降糖药'),
(86,  7, 'D_REPAGLINIDE',        '瑞格列奈',          '格列奈类口服降糖药'),
(87,  7, 'D_ACARBOSE',           '阿卡波糖',          'α-糖苷酶抑制剂类口服降糖药'),
(88,  7, 'D_VOGLIBOSE',          '伏格列波糖',        'α-糖苷酶抑制剂类口服降糖药'),
(89,  7, 'D_PIOGLITAZONE',       '吡格列酮',          '噻唑烷二酮类口服降糖药'),
(90,  7, 'D_SITAGLIPTIN',        '西格列汀',          'DPP-4抑制剂类口服降糖药'),
(91,  7, 'D_EMPAGLIFLOZIN',      '恩格列净',          'SGLT2抑制剂类口服降糖药'),
(92,  7, 'D_DAPAGLIFLOZIN',      '达格列净',          'SGLT2抑制剂类口服降糖药'),
(93,  7, 'D_INSULIN_GLARGINE',   '甘精胰岛素',        '长效胰岛素类似物'),
(94,  7, 'D_INSULIN_DETEMIR',    '地特胰岛素',        '长效胰岛素类似物'),
(95,  7, 'D_INSULIN_ASPART',     '门冬胰岛素',        '速效胰岛素类似物'),
(96,  7, 'D_INSULIN_LISPRO',     '赖脯胰岛素',        '速效胰岛素类似物'),
(97,  7, 'D_INSULIN_REGULAR',    '人胰岛素',          '短效胰岛素'),
(98,  7, 'D_INSULIN_MIX30',      '预混胰岛素30R',     '预混胰岛素'),
(99,  7, 'D_AMLODIPINE',         '苯磺酸氨氯地平',    '二氢吡啶类钙拮抗剂'),
(100, 7, 'D_NIFEDIPINE',         '硝苯地平',          '二氢吡啶类钙拮抗剂'),
(101, 7, 'D_LOSARTAN',           '氯沙坦',            '血管紧张素受体拮抗剂'),
(102, 7, 'D_VALSARTAN',          '缬沙坦',            '血管紧张素受体拮抗剂'),
(103, 7, 'D_OLMESARTAN',         '奥美沙坦',          '血管紧张素受体拮抗剂'),
(104, 7, 'D_ENALAPRIL',          '依那普利',          '血管紧张素转换酶抑制剂'),
(105, 7, 'D_PERINDOPRIL',        '培哚普利',          '血管紧张素转换酶抑制剂'),
(106, 7, 'D_METOPROLOL',         '美托洛尔',          'β受体阻滞剂'),
(107, 7, 'D_BISOPROLOL',         '比索洛尔',          'β受体阻滞剂'),
(108, 7, 'D_HYDROCHLOROTHIAZIDE','氢氯噻嗪',          '噻嗪类利尿剂'),
(109, 7, 'D_ATORVASTATIN',       '阿托伐他汀',        '他汀类调脂药'),
(110, 7, 'D_SIMVASTATIN',        '辛伐他汀',          '他汀类调脂药'),
(111, 7, 'D_ROSUVASTATIN',       '瑞舒伐他汀',        '他汀类调脂药'),
(112, 7, 'D_FENOFIBRATE',        '非诺贝特',          '贝特类调脂药'),
(113, 7, 'D_ASPIRIN',            '阿司匹林',          '抗血小板药'),
(114, 7, 'D_CLOPIDOGREL',        '氯吡格雷',          '抗血小板药'),
(115, 7, 'D_NITROGLYCERIN',      '硝酸甘油',          '硝酸酯类扩血管药'),
(116, 7, 'D_ISOSORBIDE_MONO',    '单硝酸异山梨酯',    '硝酸酯类扩血管药'),
(117, 7, 'D_DIGOXIN',            '地高辛',            '强心苷类'),
(118, 7, 'D_FUROSEMIDE',         '呋塞米',            '袢利尿剂'),
(119, 7, 'D_SPIRONOLACTONE',     '螺内酯',            '醛固酮受体拮抗剂'),
(120, 7, 'D_SALBUTAMOL',         '沙丁胺醇',          '短效β2受体激动剂');
INSERT INTO ont_instance (id, class_id, code, name_cn, remark) VALUES
-- 药品（续）
(121, 7, 'D_BUDESONIDE',         '布地奈德',          '吸入性糖皮质激素'),
(122, 7, 'D_FORMOTEROL',         '福莫特罗',          '长效β2受体激动剂'),
(123, 7, 'D_MONTELUKAST',        '孟鲁司特',          '白三烯受体拮抗剂'),
(124, 7, 'D_AMBROXOL',           '氨溴索',            '黏液溶解性祛痰药'),
(125, 7, 'D_OMEPRAZOLE',         '奥美拉唑',          '质子泵抑制剂'),
(126, 7, 'D_PANTOPRAZOLE',       '泮托拉唑',          '质子泵抑制剂'),
(127, 7, 'D_ESOMEPRAZOLE',       '埃索美拉唑',        '质子泵抑制剂'),
(128, 7, 'D_DOMPERIDONE',        '多潘立酮',          '促胃肠动力药'),
(129, 7, 'D_LACTULOSE',          '乳果糖',            '渗透性缓泻药'),
(130, 7, 'D_URSODIOL',           '熊去氧胆酸',        '利胆药'),
(131, 7, 'D_CEFTRIAXONE',        '头孢曲松',          '第三代头孢菌素'),
(132, 7, 'D_CEFUROXIME',         '头孢呋辛',          '第二代头孢菌素'),
(133, 7, 'D_AZITHROMYCIN',       '阿奇霉素',          '大环内酯类抗生素'),
(134, 7, 'D_AMOXICILLIN',        '阿莫西林',          '青霉素类抗生素'),
(135, 7, 'D_AMOX_CLAV',          '阿莫西林克拉维酸钾','β内酰胺类复合抗生素'),
(136, 7, 'D_LEVOFLOXACIN',       '左氧氟沙星',        '喹诺酮类抗生素'),
(137, 7, 'D_MOXIFLOXACIN',       '莫西沙星',          '喹诺酮类抗生素'),
(138, 7, 'D_METRONIDAZOLE',      '甲硝唑',            '硝基咪唑类抗菌药'),
(139, 7, 'D_LEVOTHYROXINE',      '左甲状腺素钠',      '甲状腺激素类药物'),
(140, 7, 'D_ALLOPURINOL',        '别嘌醇',            '黄嘌呤氧化酶抑制剂'),
-- 检验项目（141-170）
(141, 6, 'LAB_HBA1C',           '糖化血红蛋白', CONCAT('全血', '，', '参考 4-6')),
(142, 6, 'LAB_FBG',             '空腹血糖', CONCAT('血清', '，', '参考 3.9-6.1')),
(143, 6, 'LAB_OGTT',            '口服葡萄糖耐量试验', CONCAT('血清', '，', '参考 3.9-7.8')),
(144, 6, 'LAB_RANDOM_BG',       '随机血糖', CONCAT('血清', '，', '参考 3.9-11.1')),
(145, 6, 'LAB_INSULIN_FASTING', '空腹胰岛素', CONCAT('血清', '，', '参考 2.6-24.9')),
(146, 6, 'LAB_CPEPTIDE',        '空腹C肽', CONCAT('血清', '，', '参考 1.1-4.4')),
(147, 6, 'LAB_TC',              '总胆固醇', CONCAT('血清', '，', '参考 2.8-5.7')),
(148, 6, 'LAB_TG',              '甘油三酯', CONCAT('血清', '，', '参考 0.4-1.7')),
(149, 6, 'LAB_HDL',             '高密度脂蛋白胆固醇', CONCAT('血清', '，', '参考 1.16-1.42')),
(150, 6, 'LAB_LDL',             '低密度脂蛋白胆固醇', CONCAT('血清', '，', '参考 1.9-3.1')),
(151, 6, 'LAB_ALT',             '丙氨酸氨基转移酶', CONCAT('血清', '，', '参考 7-40')),
(152, 6, 'LAB_AST',             '天门冬氨酸氨基转移酶', CONCAT('血清', '，', '参考 13-35')),
(153, 6, 'LAB_GGT',             'γ-谷氨酰转肽酶', CONCAT('血清', '，', '参考 10-60')),
(154, 6, 'LAB_TBIL',            '总胆红素', CONCAT('血清', '，', '参考 3.4-17.1')),
(155, 6, 'LAB_DBIL',            '直接胆红素', CONCAT('血清', '，', '参考 0-6.8')),
(156, 6, 'LAB_ALB',             '白蛋白', CONCAT('血清', '，', '参考 40-55')),
(157, 6, 'LAB_UREA',            '尿素', CONCAT('血清', '，', '参考 2.9-8.2')),
(158, 6, 'LAB_CREAT',           '血肌酐', CONCAT('血清', '，', '参考 57-97')),
(159, 6, 'LAB_EGFR',            '估算肾小球滤过率', CONCAT('血清', '，', '参考 90-120')),
(160, 6, 'LAB_URIC_ACID',       '血尿酸', CONCAT('血清', '，', '参考 208-428'));
INSERT INTO ont_instance (id, class_id, code, name_cn, remark) VALUES
-- 检验项目（续）
(161, 6, 'LAB_UACR',   '尿微量白蛋白肌酐比', CONCAT('尿液', '，', '参考 0-30')),
(162, 6, 'LAB_K',      '血钾', CONCAT('血清', '，', '参考 3.5-5.3')),
(163, 6, 'LAB_NA',     '血钠', CONCAT('血清', '，', '参考 137-147')),
(164, 6, 'LAB_CA',     '血钙', CONCAT('血清', '，', '参考 2.11-2.52')),
(165, 6, 'LAB_HGB',    '血红蛋白', CONCAT('全血', '，', '参考 115-150')),
(166, 6, 'LAB_WBC',    '白细胞计数', CONCAT('全血', '，', '参考 3.5-9.5')),
(167, 6, 'LAB_PLT',    '血小板计数', CONCAT('全血', '，', '参考 125-350')),
(168, 6, 'LAB_TSH',    '促甲状腺激素', CONCAT('血清', '，', '参考 0.55-4.78')),
(169, 6, 'LAB_FT3',    '游离三碘甲状腺原氨酸', CONCAT('血清', '，', '参考 3.5-6.5')),
(170, 6, 'LAB_FT4',    '游离甲状腺素', CONCAT('血清', '，', '参考 10.6-21.2')),
-- 科室（171-182）
(171, 8, 'DEPT_ENDO',  '内分泌科',    '住院与门诊均含'),
(172, 8, 'DEPT_CARD',  '心血管内科',  '住院与门诊均含'),
(173, 8, 'DEPT_RESPI', '呼吸内科',    '住院与门诊均含'),
(174, 8, 'DEPT_DIGEST','消化内科',    '住院与门诊均含'),
(175, 8, 'DEPT_NEURO', '神经内科',    '住院与门诊均含'),
(176, 8, 'DEPT_NEPHRO','肾内科',      '住院与门诊均含'),
(177, 8, 'DEPT_ORTH',  '骨科',        '住院与门诊均含'),
(178, 8, 'DEPT_GEN_SURG','普外科',    '住院与门诊均含'),
(179, 8, 'DEPT_GYNAE', '妇科',        '住院与门诊均含'),
(180, 8, 'DEPT_OPH',   '眼科',        '住院与门诊均含'),
(181, 8, 'DEPT_EMERG', '急诊科',      '含急诊留观'),
(182, 8, 'DEPT_GP',    '全科医学科',  '含慢病随访');

-- ----------------------------------------------------------------------------
-- 七、实例类目重挂与实例映射（批量生成，id = 100 + 实例 id，即 101-282）
-- 类目子类：E10-E14 -> 糖尿病类；口服降糖药 81-92 / 胰岛素 93-98 -> 降糖药两个子类。
-- 疾病及糖尿病类 -> fact_diagnosis.disease_code；药品及降糖药子类 -> fact_medication.drug_code；
-- 检验 -> fact_lab_result.test_code；科室 -> fact_visit.dept_code。
-- ----------------------------------------------------------------------------

UPDATE ont_instance SET class_id = 13 WHERE id BETWEEN 1 AND 5;
UPDATE ont_instance SET class_id = 15 WHERE id BETWEEN 81 AND 92;
UPDATE ont_instance SET class_id = 16 WHERE id BETWEEN 93 AND 98;

INSERT INTO ont_mapping (id, kind, ref_id, table_name, column_name, value_expr)
SELECT 100 + i.id,
       'instance',
       i.id,
       CASE c.code
           WHEN 'CLS_DISEASE'     THEN 'fact_diagnosis'
           WHEN 'CLS_DIABETES'    THEN 'fact_diagnosis'
           WHEN 'CLS_DRUG'        THEN 'fact_medication'
           WHEN 'CLS_ORAL_HYPO'   THEN 'fact_medication'
           WHEN 'CLS_INSULIN'     THEN 'fact_medication'
           WHEN 'CLS_LAB_TEST'    THEN 'fact_lab_result'
           WHEN 'CLS_DEPARTMENT'  THEN 'fact_visit'
       END,
       CASE c.code
           WHEN 'CLS_DISEASE'     THEN 'disease_code'
           WHEN 'CLS_DIABETES'    THEN 'disease_code'
           WHEN 'CLS_DRUG'        THEN 'drug_code'
           WHEN 'CLS_ORAL_HYPO'   THEN 'drug_code'
           WHEN 'CLS_INSULIN'     THEN 'drug_code'
           WHEN 'CLS_LAB_TEST'    THEN 'test_code'
           WHEN 'CLS_DEPARTMENT'  THEN 'dept_code'
       END,
       CONCAT(CASE c.code
           WHEN 'CLS_DISEASE'     THEN '{t}.disease_code'
           WHEN 'CLS_DIABETES'    THEN '{t}.disease_code'
           WHEN 'CLS_DRUG'        THEN '{t}.drug_code'
           WHEN 'CLS_ORAL_HYPO'   THEN '{t}.drug_code'
           WHEN 'CLS_INSULIN'     THEN '{t}.drug_code'
           WHEN 'CLS_LAB_TEST'    THEN '{t}.test_code'
           WHEN 'CLS_DEPARTMENT'  THEN '{t}.dept_code'
       END, ' = ''', i.code, '''')
FROM ont_instance i
JOIN ont_class c ON c.id = i.class_id
WHERE c.code IN ('CLS_DISEASE', 'CLS_DIABETES', 'CLS_DRUG', 'CLS_ORAL_HYPO', 'CLS_INSULIN',
                 'CLS_LAB_TEST', 'CLS_DEPARTMENT')
ORDER BY i.id;

-- ----------------------------------------------------------------------------
-- 八、同义词（term 全库唯一；instance_id 与 class_id 二选一）
-- E11 / D_METFORMIN / LAB_HBA1C 为契约全量同义词，其余为真实别名。
-- ----------------------------------------------------------------------------

INSERT INTO ont_synonym (id, term, instance_id, class_id) VALUES
-- 类级同义词
(1,  '病种',     NULL, 5),
(2,  '药品',     NULL, 7),
(3,  '化验',     NULL, 6),
(4,  '病人',     NULL, 4),
(5,  '检验指标', NULL, 6),
-- 契约全量同义词：E11（实例 id=2）
(6,  'II型糖尿病',            2,    NULL),
(7,  '糖尿病II型',            2,    NULL),
(8,  '成人糖尿病',            2,    NULL),
(9,  '非胰岛素依赖型糖尿病',  2,    NULL),
-- 契约全量同义词：D_METFORMIN（实例 id=81）
(10, '格华止',          81,  NULL),
(11, '盐酸二甲双胍片',  81,  NULL),
(12, '降糖片',          81,  NULL),
-- 契约全量同义词：LAB_HBA1C（实例 id=141）
(13, 'HbA1c',           141, NULL),
(14, 'HbA1c%',          141, NULL),
(15, '糖基化血红蛋白',  141, NULL),
(16, '糖化血红蛋白A1c', 141, NULL),
-- 其他疾病同义词
(17, '胰岛素依赖型糖尿病',    1,  NULL),
(18, '少年糖尿病',            1,  NULL),
(19, '心衰',                  22, NULL),
(20, '心功能不全',            22, NULL),
(21, '高血压',                17, NULL),
(22, '高血压病',              17, NULL),
(23, '血脂异常',              10, NULL),
(24, '高血脂',                10, NULL),
(25, '心梗',                  20, NULL),
(26, '急性心梗',              20, NULL),
(27, '冠心病',                21, NULL),
(28, '冠状动脉粥样硬化性心脏病', 21, NULL),
(29, '脑梗',                  26, NULL),
(30, '缺血性脑卒中',          26, NULL),
(31, '慢阻肺',                36, NULL),
(32, 'COPD',                  36, NULL),
(33, '哮喘',                  37, NULL),
(34, '脂肪性肝病',            50, NULL),
(35, 'NAFLD',                 50, NULL),
(36, '慢性肾功能不全',        55, NULL),
(37, 'CKD',                   55, NULL),
(38, '肥胖',                  6,  NULL),
(39, '肥胖病',                6,  NULL),
(40, '泌尿系感染',            59, NULL),
(41, 'UTI',                   59, NULL),
(42, '房颤',                  24, NULL),
(43, '心房纤颤',              24, NULL),
(44, '甲减',                  7,  NULL),
(45, '甲状腺机能减退症',      7,  NULL),
(46, '甲亢',                  8,  NULL),
(47, '甲状腺机能亢进症',      8,  NULL),
(48, '铁缺乏性贫血',          65, NULL),
(49, '痛风性关节炎',          79, NULL),
(50, '乳腺癌',                71, NULL),
(51, '肝硬变',                51, NULL);
INSERT INTO ont_synonym (id, term, instance_id, class_id) VALUES
-- 药品同义词（含商品名与通用名别名）
(52, '亚莫利',            82,  NULL),
(53, '拜唐苹',            87,  NULL),
(54, '卡博平',            87,  NULL),
(55, '来得时',            93,  NULL),
(56, '长效胰岛素',        93,  NULL),
(57, '络活喜',            99,  NULL),
(58, '氨氯地平',          99,  NULL),
(59, '科素亚',            101, NULL),
(60, '氯沙坦钾',          101, NULL),
(61, '倍他乐克',          106, NULL),
(62, '琥珀酸美托洛尔',    106, NULL),
(63, '立普妥',            109, NULL),
(64, '阿托伐他汀钙',      109, NULL),
(65, '拜阿司匹灵',        113, NULL),
(66, '阿司匹林肠溶片',    113, NULL),
(67, '速尿',              118, NULL),
(68, '万托林',            120, NULL),
(69, '洛赛克',            125, NULL),
(70, '奥美拉唑肠溶胶囊',  125, NULL),
(71, '优甲乐',            139, NULL),
-- 检验同义词
(72, '血糖',              142, NULL),
(73, 'FPG',               142, NULL),
(74, '空腹血浆葡萄糖',    142, NULL),
(75, 'OGTT',              143, NULL),
(76, '糖耐量试验',        143, NULL),
(77, '葡萄糖耐量试验',    143, NULL),
(78, 'TC',                147, NULL),
(79, 'CHOL',              147, NULL),
(80, 'TG',                148, NULL),
(81, '三酰甘油',          148, NULL),
(82, 'HDL-C',             149, NULL),
(83, '高密度脂蛋白',      149, NULL),
(84, 'LDL-C',             150, NULL),
(85, '低密度脂蛋白',      150, NULL),
(86, '肌酐',              158, NULL),
(87, 'SCr',               158, NULL),
(88, '血色素',            165, NULL),
(89, '白细胞',            166, NULL),
(90, '促甲状腺素',        168, NULL),
-- 类目类级同义词（子类闭包入口：糖尿病类 E10-E14 / 降糖药 口服+胰岛素）
(91, '糖尿病',     NULL, 13),
(92, '降糖药',     NULL, 14),
(93, '口服降糖药', NULL, 15),
(94, '胰岛素',     NULL, 16);

-- ----------------------------------------------------------------------------
-- 九、自检（执行后应各返回一行；行数供人工核对）
-- 类 16 / 实例 182 / 同义词 94 / 关系 8 / 属性 13 / 映射 208 / 规则 3
-- ----------------------------------------------------------------------------

SELECT (SELECT COUNT(*) FROM ont_class)     AS cls_cnt,
       (SELECT COUNT(*) FROM ont_instance)  AS inst_cnt,
       (SELECT COUNT(*) FROM ont_synonym)   AS syn_cnt,
       (SELECT COUNT(*) FROM ont_relation)  AS rel_cnt,
       (SELECT COUNT(*) FROM ont_attribute) AS attr_cnt,
       (SELECT COUNT(*) FROM ont_mapping)   AS map_cnt,
       (SELECT COUNT(*) FROM ont_rule)      AS rule_cnt;
