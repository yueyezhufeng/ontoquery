// OntoQuery API 封装 · 端点与字段严格对齐 docs/CONTRACT.md · 作者：月夜烛峰
//
// devmock 说明：URL 携带 ?devmock=1 时不访问后端，改为返回按契约形状构造的假数据，
// 仅用于前端布局自测（devmock 假数据中的数字不得出现在正式演示口径中），默认关闭。

const DEVMOCK = typeof location !== 'undefined' && new URLSearchParams(location.search).has('devmock');

/** 契约第 7 节定义的核心演示问题 */
export const CORE_QUESTION = '近一个月诊断为2型糖尿病且HbA1c大于7的患者中，使用二甲双胍治疗的有多少人？';

const SERVICE_DOWN_CODE = 'B0001';
const SERVICE_DOWN_MESSAGE = '无法连接后端服务，请确认服务已启动（mvn spring-boot:run，端口 8080）';

/**
 * 构造统一错误对象 {code, message}；aborted 标记页面切换时的请求取消。
 * @param {string} code 5 位错误码
 * @param {string} message 用户可理解的错误信息
 * @param {boolean} aborted 是否为主动取消
 * @returns {Error} 携带 code/aborted 字段的错误
 */
function makeError(code, message, aborted) {
  const error = new Error(message);
  error.code = code;
  error.aborted = Boolean(aborted);
  return error;
}

function isErrorCode(code) {
  return typeof code === 'string' && /^[A-Z]\d{4}$/.test(code);
}

/**
 * 底层请求：统一 JSON 解析与错误转译。
 * @param {string} path 请求路径
 * @param {Object} options method/body/signal
 * @returns {Promise<Object>} 响应 JSON
 */
async function request(path, options) {
  const config = options || {};
  const init = { method: config.method || 'GET', signal: config.signal };
  if (config.body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' };
    init.body = JSON.stringify(config.body);
  }
  let response;
  try {
    response = await fetch(path, init);
  } catch (err) {
    if (err && err.name === 'AbortError') {
      throw makeError('A0408', '请求已取消', true);
    }
    throw makeError(SERVICE_DOWN_CODE, SERVICE_DOWN_MESSAGE);
  }
  if (config.signal && config.signal.aborted) {
    throw makeError('A0408', '请求已取消', true);
  }
  let data = null;
  const text = await response.text().catch(() => '');
  if (text) {
    try {
      data = JSON.parse(text);
    } catch (err) {
      data = null;
    }
  }
  if (!response.ok) {
    const code = data && isErrorCode(data.code) ? data.code : 'H0' + String(response.status).padStart(3, '0');
    const message = data && data.message ? data.message : '请求失败（HTTP ' + response.status + '）';
    throw makeError(code, message);
  }
  return data;
}

// ==================== 查询端点（契约第 1、2 节） ====================

/**
 * POST /api/query/ontology
 * @param {string} question 自然语言问题
 * @param {AbortSignal} signal 取消信号
 * @returns {Promise<Object>} PipelineResult（engine=ontology）
 */
export function queryOntology(question, signal) {
  if (DEVMOCK) {
    return mockRespond(buildMockCompare(question)).then((data) => data.ontology);
  }
  return request('/api/query/ontology', { method: 'POST', body: { question: question }, signal: signal });
}

/**
 * POST /api/query/traditional
 * @param {string} question 自然语言问题
 * @param {AbortSignal} signal 取消信号
 * @returns {Promise<Object>} PipelineResult（engine=traditional）
 */
export function queryTraditional(question, signal) {
  if (DEVMOCK) {
    return mockRespond(buildMockCompare(question)).then((data) => data.traditional);
  }
  return request('/api/query/traditional', { method: 'POST', body: { question: question }, signal: signal });
}

/**
 * POST /api/query/compare
 * @param {string} question 自然语言问题
 * @param {AbortSignal} signal 取消信号
 * @returns {Promise<Object>} {question, ontology, traditional}
 */
export function queryCompare(question, signal) {
  if (DEVMOCK) {
    return mockRespond(buildMockCompare(question));
  }
  return request('/api/query/compare', { method: 'POST', body: { question: question }, signal: signal });
}

// ==================== 本体图谱端点（契约第 4 节） ====================

/** GET /api/ontology/tree */
export function ontologyTree(signal) {
  if (DEVMOCK) {
    return mockRespond(buildMockTree());
  }
  return request('/api/ontology/tree', { signal: signal });
}

/** GET /api/ontology/graph */
export function ontologyGraph(signal) {
  if (DEVMOCK) {
    return mockRespond(buildMockGraph());
  }
  return request('/api/ontology/graph', { signal: signal });
}

/**
 * GET /api/ontology/entity/{code}
 * @param {string} code 类编码或实例编码
 */
export function entity(code, signal) {
  if (DEVMOCK) {
    return mockRespond(buildMockEntity(code));
  }
  return request('/api/ontology/entity/' + encodeURIComponent(code), { signal: signal });
}

// ==================== 元数据端点（契约第 5 节） ====================

/** GET /api/meta/tables */
export function metaTables(signal) {
  if (DEVMOCK) {
    return mockRespond(buildMockTables());
  }
  return request('/api/meta/tables', { signal: signal });
}

/** GET /api/meta/llm-status */
export function llmStatus(signal) {
  if (DEVMOCK) {
    return mockRespond(buildMockLlmStatus());
  }
  return request('/api/meta/llm-status', { signal: signal });
}

/** POST /api/meta/llm-cache/clear，响应 {cleared: n} */
export function clearCache(signal) {
  if (DEVMOCK) {
    return mockRespond({ cleared: 2 });
  }
  return request('/api/meta/llm-cache/clear', { method: 'POST', signal: signal });
}

// ==================== 基准端点（契约第 6 节） ====================

/** POST /api/benchmark/run?forceMock=true */
export function runBenchmark(signal) {
  if (DEVMOCK) {
    return mockRespond(buildMockBenchmark(2000));
  }
  return request('/api/benchmark/run?forceMock=true', { method: 'POST', signal: signal });
}

/** GET /api/benchmark/latest；无历史时后端返回 {run: null} */
export function latestBenchmark(signal) {
  if (DEVMOCK) {
    return mockRespond(buildMockBenchmark(0));
  }
  return request('/api/benchmark/latest', { signal: signal });
}

// ====================================================================
// devmock 假数据（仅 ?devmock=1 时生效，用于前端布局自测，禁止用于演示口径）
// ====================================================================

function sleep(ms) {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}

function mockRespond(data) {
  return sleep(360).then(() => JSON.parse(JSON.stringify(data)));
}

function buildMockOntologyPipeline(question) {
  return {
    engine: 'ontology',
    mode: 'deterministic',
    modeLabel: '确定性本体推理',
    fallbackReason: null,
    question: question,
    steps: [
      {
        key: 'ner',
        title: '词典识别',
        status: 'ok',
        elapsedMs: 3,
        summary: '识别出 4 个本体实体与 2 个约束条件',
        items: [
          { type: 'entity-chip', from: '患者', to: 'CLS_PATIENT 患者' },
          { type: 'entity-chip', from: '2型糖尿病', to: 'E11（CLS_DISEASE）' },
          { type: 'entity-chip', from: 'HbA1c', to: 'LAB_HBA1C 糖化血红蛋白（CLS_LAB_TEST）' },
          { type: 'entity-chip', from: '二甲双胍', to: 'D_METFORMIN（CLS_DRUG）' },
          { type: 'kv', label: '时间窗口', from: '近一个月', to: '近 30 天', level: 'info' },
          { type: 'kv', label: '数值条件', from: '大于7', to: 'result_value > 7', level: 'info' }
        ]
      },
      {
        key: 'relation',
        title: '关系链接',
        status: 'ok',
        elapsedMs: 5,
        summary: '以患者为锚点链接三条事件关系',
        items: [
          { type: 'relation-edge', from: '患者', to: '诊断记录', label: '被诊断为' },
          { type: 'relation-edge', from: '患者', to: '检验结果', label: '有检验结果' },
          { type: 'relation-edge', from: '患者', to: '用药记录', label: '使用药物' },
          { type: 'relation-edge', from: '检验结果', to: '检验项目', label: '检验项目为' }
        ]
      },
      {
        key: 'reasoning',
        title: '本体推理',
        status: 'ok',
        elapsedMs: 9,
        summary: '同义词归一、时间挂靠与值域校验全部通过',
        items: [
          {
            type: 'kv', label: '同义词归一', from: 'HbA1c / 糖化血红蛋白', to: 'LAB_HBA1C',
            rule: 'SYNONYM_NORMALIZE', level: 'ok'
          },
          {
            type: 'kv', label: '时间挂靠', from: '近一个月', to: 'diagnosis_date',
            rule: 'TIME_ATTACH_PROXIMITY（就近挂靠到诊断事件）', level: 'ok'
          },
          {
            type: 'kv', label: '值域校验', from: 'result_value > 7', to: '正常值域 4-6，条件合理',
            rule: 'VALUE_RANGE', level: 'ok'
          },
          { type: 'text', text: '子类闭包校验通过：二甲双胍 属于 药品 分支，无需向下展开', level: 'ok' }
        ]
      },
      {
        key: 'path',
        title: '路径规划与 SQL 生成',
        status: 'ok',
        elapsedMs: 12,
        summary: '三条事件路径以 EXISTS 子查询展开，规避多表 JOIN',
        items: [
          { type: 'path-chain', values: ['患者', '诊断记录', '疾病 E11'] },
          { type: 'path-chain', values: ['患者', '检验结果', '检验项目 LAB_HBA1C'] },
          { type: 'path-chain', values: ['患者', '用药记录', '药品 D_METFORMIN'] },
          {
            type: 'sql-pre',
            text: "EXISTS (SELECT 1 FROM fact_diagnosis t0 WHERE t0.patient_id = p.patient_id"
              + " AND t0.disease_code = 'E11')"
          }
        ]
      },
      {
        key: 'verify',
        title: '校验与置信度',
        status: 'ok',
        elapsedMs: 4,
        summary: '语义完整性、索引命中与行数预估均通过',
        items: [
          {
            type: 'kv-table',
            columns: ['检查项', '结论'],
            rows: [
              ['语义完整性', '问题全部要素已映射到 SQL'],
              ['索引命中', 'diagnosis_date、disease_code 均有索引'],
              ['行膨胀', 'EXISTS 子查询天然规避笛卡尔积']
            ]
          },
          { type: 'text', text: '置信度 92：实体平均得分 0.92（同义词命中），无未决歧义', level: 'ok' }
        ]
      }
    ],
    sql: [
      'SELECT COUNT(DISTINCT p.patient_id) AS patient_count',
      'FROM dim_patient p',
      "WHERE EXISTS (SELECT 1 FROM fact_diagnosis t0",
      '              WHERE t0.patient_id = p.patient_id',
      "                AND t0.disease_code = 'E11'",
      '                AND t0.diagnosis_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY))',
      '  AND EXISTS (SELECT 1 FROM fact_lab_result t1',
      '              WHERE t1.patient_id = p.patient_id',
      "                AND t1.test_code = 'LAB_HBA1C'",
      '                AND t1.result_value > 7)',
      '  AND EXISTS (SELECT 1 FROM fact_medication t2',
      '              WHERE t2.patient_id = p.patient_id',
      "                AND t2.drug_code = 'D_METFORMIN')"
    ].join('\n'),
    sqlStatus: 'ok',
    result: {
      columns: ['patient_count'],
      rows: [['327']],
      rowCount: 1,
      truncated: false,
      elapsedMs: 45,
      error: null
    },
    confidence: {
      score: 92,
      formula: '100 - 40*(1-0.92) - 12*0 - 8*0',
      factors: [
        { label: '实体平均得分 0.92（同义词命中）', delta: -3 },
        { label: '无未决歧义', delta: 0 }
      ]
    },
    risks: [
      {
        code: 'SYNONYM_EVIDENCE',
        level: 'info',
        title: '同义词归一已启用',
        detail: 'HbA1c 与糖化血红蛋白已通过本体同义词表归一，命中标准编码 LAB_HBA1C',
        evidence: [
          {
            sql: "SELECT synonym FROM ont_synonym WHERE instance_code = 'LAB_HBA1C'",
            result: 'HbA1c / HbA1c% / 糖基化血红蛋白 / 糖化血红蛋白A1c'
          }
        ]
      }
    ],
    error: null,
    totalElapsedMs: 33
  };
}

function buildMockTraditionalPipeline(question) {
  return {
    engine: 'traditional',
    mode: 'llm',
    modeLabel: 'LLM 生成',
    fallbackReason: null,
    question: question,
    steps: [
      {
        key: 'schema',
        title: '模式读取',
        status: 'ok',
        elapsedMs: 15,
        summary: '读取全部表结构，筛出 4 张候选表',
        items: [
          {
            type: 'kv-table',
            columns: ['候选表', '判定'],
            rows: [
              ['dim_patient', '命中（患者）'],
              ['fact_diagnosis', '命中（诊断）'],
              ['fact_lab_result', '疑似（HbA1c 归属不确定）'],
              ['fact_medication', '疑似（二甲双胍 归属不确定）']
            ]
          },
          { type: 'text', text: '表选择依赖表名与问题词的字面相似度，无语义约束', level: 'warn' }
        ]
      },
      {
        key: 'link',
        title: '列名匹配',
        status: 'warn',
        elapsedMs: 12,
        summary: '3 处列名为猜测匹配，无法自证正确性',
        items: [
          { type: 'kv', label: 'HbA1c', from: '问题术语', to: 'test_name 列（猜测）', level: 'warn' },
          { type: 'kv', label: '大于7', from: '数值条件', to: 'result_value 列（猜测）', level: 'warn' },
          { type: 'kv', label: '二甲双胍', from: '问题术语', to: 'drug_name 列（猜测）', level: 'warn' }
        ]
      },
      {
        key: 'join',
        title: '关联推断',
        status: 'warn',
        elapsedMs: 18,
        summary: '统一使用 LEFT JOIN，未感知多对多膨胀风险',
        items: [
          { type: 'relation-edge', from: 'dim_patient', to: 'fact_diagnosis', label: 'LEFT JOIN' },
          { type: 'relation-edge', from: 'dim_patient', to: 'fact_lab_result', label: 'LEFT JOIN' },
          { type: 'relation-edge', from: 'dim_patient', to: 'fact_medication', label: 'LEFT JOIN' },
          { type: 'text', text: '三张事件表均为 1:N 关系，直接 JOIN 存在行数放大', level: 'error' }
        ]
      },
      {
        key: 'generate',
        title: 'LLM 生成',
        status: 'ok',
        elapsedMs: 820,
        summary: '模型端到端生成 SQL，模糊匹配与时间挂靠为模型自行决策',
        items: [
          { type: 'sql-pre', text: "WHERE d.disease_name LIKE '%2型糖尿病%' AND l.result_value > 7" },
          { type: 'text', text: '时间条件仅挂靠到诊断时间，检验与用药时间未限定', level: 'warn' },
          { type: 'text', text: '无独立校验环节，正确性完全依赖模型能力', level: 'error' }
        ]
      },
      {
        key: 'risk',
        title: '风险检测',
        status: 'warn',
        elapsedMs: 22,
        summary: 'linter 检出 3 条风险：1 高 2 中',
        items: [
          { type: 'text', text: 'SYNONYM_EVIDENCE（high）：LIKE 原词漏配同义词写法', level: 'error' },
          { type: 'text', text: 'JOIN_FANOUT（medium）：多表 LEFT JOIN 行膨胀风险', level: 'warn' },
          { type: 'text', text: 'TIME_SCOPE_GUESS（medium）：时间修饰挂靠未经语义论证', level: 'warn' }
        ]
      }
    ],
    sql: [
      'SELECT COUNT(DISTINCT p.patient_id) AS cnt',
      'FROM dim_patient p',
      'LEFT JOIN fact_diagnosis d ON p.patient_id = d.patient_id',
      'LEFT JOIN fact_lab_result l ON p.patient_id = l.patient_id',
      'LEFT JOIN fact_medication m ON p.patient_id = m.patient_id',
      "WHERE d.disease_name LIKE '%2型糖尿病%'",
      "  AND l.test_name = 'HbA1c'",
      '  AND l.result_value > 7',
      "  AND m.drug_name LIKE '%二甲双胍%'",
      '  AND d.diagnosis_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)'
    ].join('\n'),
    sqlStatus: 'ok',
    result: {
      columns: ['cnt'],
      rows: [['1562']],
      rowCount: 1,
      truncated: false,
      elapsedMs: 62,
      error: null
    },
    confidence: {
      score: 65,
      formula: '100 - 18（列名猜测） - 12（时间歧义） - 5（行膨胀风险）',
      factors: [
        { label: '3 处列名为字符串相似度猜测', delta: -18 },
        { label: '时间修饰歧义未消解', delta: -12 },
        { label: '多表 LEFT JOIN 存在行膨胀风险', delta: -5 }
      ]
    },
    risks: [
      {
        code: 'SYNONYM_EVIDENCE',
        level: 'high',
        title: '同义词漏配',
        detail: '该疾病在数据中存在 3 种写法，LIKE 原词实测漏配 2 种',
        evidence: [
          {
            sql: 'SELECT disease_name, COUNT(*) AS cnt FROM fact_diagnosis GROUP BY disease_name ORDER BY cnt DESC',
            result: '2型糖尿病 412 / II型糖尿病 121 / 糖尿病II型 87'
          }
        ]
      },
      {
        code: 'JOIN_FANOUT',
        level: 'medium',
        title: '多表 JOIN 行膨胀',
        detail: '三张 1:N 事件表直接 LEFT JOIN，中间结果行数被放大后再 DISTINCT',
        evidence: [
          {
            sql: 'SELECT COUNT(*) FROM fact_diagnosis d JOIN fact_lab_result l ON l.patient_id = d.patient_id',
            result: '60842'
          }
        ]
      },
      {
        code: 'TIME_SCOPE_GUESS',
        level: 'medium',
        title: '时间修饰歧义',
        detail: '“近一个月”仅挂靠到诊断时间，检验与用药时间未限定，语义无法自证',
        evidence: []
      }
    ],
    error: null,
    totalElapsedMs: 947
  };
}

function buildMockCompare(question) {
  return {
    question: question,
    ontology: buildMockOntologyPipeline(question),
    traditional: buildMockTraditionalPipeline(question)
  };
}

// ---- 本体图谱 mock（编码严格取自契约第 8 节） ----

const MOCK_CLASSES = [
  { code: 'CLS_PERSON_GROUP', name: '人员', color: '#a855f7', layer: 0, instanceCount: 0, parent: null },
  { code: 'CLS_ENTITY_GROUP', name: '临床实体', color: '#06b6d4', layer: 0, instanceCount: 0, parent: null },
  { code: 'CLS_EVENT_GROUP', name: '临床事件', color: '#f59e0b', layer: 0, instanceCount: 0, parent: null },
  { code: 'CLS_PATIENT', name: '患者', color: '#8b5cf6', layer: 1, instanceCount: 20000, parent: 'CLS_PERSON_GROUP' },
  { code: 'CLS_VISIT', name: '就诊', color: '#f97316', layer: 2, instanceCount: 60000, parent: 'CLS_EVENT_GROUP' },
  { code: 'CLS_DIAGNOSIS', name: '诊断记录', color: '#ec4899', layer: 2, instanceCount: 60000, parent: 'CLS_EVENT_GROUP' },
  { code: 'CLS_LAB_RESULT', name: '检验结果', color: '#22d3ee',
    layer: 2, instanceCount: 80000, parent: 'CLS_EVENT_GROUP' },
  { code: 'CLS_MEDICATION', name: '用药记录', color: '#10b981',
    layer: 2, instanceCount: 90000, parent: 'CLS_EVENT_GROUP' },
  { code: 'CLS_DISEASE', name: '疾病', color: '#f87171', layer: 3, instanceCount: 80, parent: 'CLS_ENTITY_GROUP' },
  { code: 'CLS_LAB_TEST', name: '检验项目', color: '#38bdf8', layer: 3, instanceCount: 40, parent: 'CLS_ENTITY_GROUP' },
  { code: 'CLS_DRUG', name: '药品', color: '#34d399', layer: 3, instanceCount: 120, parent: 'CLS_ENTITY_GROUP' },
  { code: 'CLS_DEPARTMENT', name: '科室', color: '#fbbf24', layer: 3, instanceCount: 15, parent: 'CLS_ENTITY_GROUP' }
];

function classByCode(code) {
  return MOCK_CLASSES.find((item) => item.code === code) || null;
}

function buildMockTree() {
  const childrenOf = (parentCode) => MOCK_CLASSES.filter((item) => item.parent === parentCode);
  const toNode = (item) => ({
    code: item.code,
    name: item.name,
    color: item.color,
    instanceCount: item.instanceCount,
    children: childrenOf(item.code).map(toNode)
  });
  return MOCK_CLASSES.filter((item) => item.parent === null).map(toNode);
}

function buildMockGraph() {
  const nodes = MOCK_CLASSES.map((item) => ({
    code: item.code,
    name: item.name,
    color: item.color,
    layer: item.layer,
    instanceCount: item.instanceCount
  }));
  const edges = [
    { from: 'CLS_PATIENT', to: 'CLS_VISIT', label: '就诊于', kind: 'data' },
    { from: 'CLS_PATIENT', to: 'CLS_DIAGNOSIS', label: '被诊断为', kind: 'data' },
    { from: 'CLS_PATIENT', to: 'CLS_LAB_RESULT', label: '有检验结果', kind: 'data' },
    { from: 'CLS_PATIENT', to: 'CLS_MEDICATION', label: '使用药物', kind: 'data' },
    { from: 'CLS_DIAGNOSIS', to: 'CLS_DISEASE', label: '确诊为', kind: 'data' },
    { from: 'CLS_LAB_RESULT', to: 'CLS_LAB_TEST', label: '检验项目为', kind: 'data' },
    { from: 'CLS_MEDICATION', to: 'CLS_DRUG', label: '使用药品为', kind: 'data' },
    { from: 'CLS_VISIT', to: 'CLS_DEPARTMENT', label: '就诊科室', kind: 'data' }
  ];
  MOCK_CLASSES.forEach((item) => {
    if (item.parent) {
      edges.push({ from: item.code, to: item.parent, label: 'subClassOf', kind: 'subclass' });
    }
  });
  return { nodes: nodes, edges: edges };
}

function buildMockEntity(code) {
  if (code === 'E11') {
    return {
      kind: 'instance',
      code: 'E11',
      name: '2型糖尿病',
      classInfo: { code: 'CLS_DISEASE', name: '疾病', color: '#f87171' },
      synonyms: ['II型糖尿病', '糖尿病II型', '成人糖尿病', '非胰岛素依赖型糖尿病'],
      mappings: [
        { table: 'fact_diagnosis', column: 'disease_code', kind: 'instance', valueExpr: "{t}.disease_code = 'E11'" }
      ],
      remark: 'ICD-10 E11，糖代谢异常类疾病'
    };
  }
  if (code === 'CLS_DISEASE') {
    return {
      kind: 'class',
      code: 'CLS_DISEASE',
      name: '疾病',
      color: '#f87171',
      remark: 'ICD-10 疾病概念，实例编码即 ICD 码',
      parent: { code: 'CLS_ENTITY_GROUP', name: '临床实体' },
      attributes: [
        {
          code: 'ATTR_DISEASE_CODE', name: '疾病编码', dataType: 'varchar(32)', unit: null,
          valueLow: null, valueHigh: null,
          mappings: [{ table: 'dim_disease', column: 'disease_code' }]
        },
        {
          code: 'ATTR_DIAGNOSIS_DATE', name: '诊断日期', dataType: 'datetime', unit: null,
          valueLow: null, valueHigh: null,
          mappings: [{ table: 'fact_diagnosis', column: 'diagnosis_date' }]
        }
      ],
      relations: [
        {
          code: 'REL_DIAGNOSED_WITH', name: '被诊断为', direction: 'out',
          target: { code: 'CLS_DIAGNOSIS', name: '诊断记录' }
        },
        { code: 'REL_SUBCLASS', name: '子类实例', direction: 'out', target: { code: 'CLS_ENTITY_GROUP', name: '临床实体' } }
      ],
      synonyms: ['病种'],
      mappings: [{ table: 'dim_disease', column: null, kind: 'class' }],
      instances: [
        { code: 'E11', name: '2型糖尿病' },
        { code: 'I10', name: '原发性高血压' },
        { code: 'E78', name: '高脂血症' },
        { code: 'J45', name: '支气管哮喘' }
      ],
      instanceTotal: 80
    };
  }
  const cls = classByCode(code);
  if (cls) {
    const eventRelations = {
      CLS_PATIENT: [
        { code: 'REL_HAS_VISIT', name: '就诊于', target: 'CLS_VISIT' },
        { code: 'REL_DIAGNOSED_WITH', name: '被诊断为', target: 'CLS_DIAGNOSIS' },
        { code: 'REL_UNDERGOES_TEST', name: '有检验结果', target: 'CLS_LAB_RESULT' },
        { code: 'REL_TAKES_DRUG', name: '使用药物', target: 'CLS_MEDICATION' }
      ]
    };
    const relations = (eventRelations[cls.code] || []).map((rel) => {
      const target = classByCode(rel.target);
      return {
        code: rel.code, name: rel.name, direction: 'out',
        target: { code: rel.target, name: target ? target.name : rel.target }
      };
    });
    if (cls.parent) {
      relations.push({
        code: 'REL_SUBCLASS', name: 'subClassOf', direction: 'out',
        target: { code: cls.parent, name: classByCode(cls.parent).name }
      });
    }
    const attributes = cls.code === 'CLS_PATIENT'
      ? [{
          code: 'ATTR_AGE', name: '年龄', dataType: 'int', unit: '岁',
          valueLow: 0, valueHigh: 150,
          mappings: [{ table: 'dim_patient', column: 'birth_date' }]
        }]
      : cls.code === 'CLS_LAB_RESULT'
        ? [{
            code: 'ATTR_RESULT_VALUE', name: '结果值', dataType: 'decimal', unit: '%',
            valueLow: 4, valueHigh: 6,
            mappings: [{ table: 'fact_lab_result', column: 'result_value' }]
          }]
        : [];
    const mappingTable = {
      CLS_PATIENT: 'dim_patient', CLS_VISIT: 'fact_visit', CLS_DIAGNOSIS: 'fact_diagnosis',
      CLS_LAB_RESULT: 'fact_lab_result', CLS_MEDICATION: 'fact_medication',
      CLS_DISEASE: 'dim_disease', CLS_LAB_TEST: 'dim_lab_test', CLS_DRUG: 'dim_drug',
      CLS_DEPARTMENT: 'dim_department'
    };
    return {
      kind: 'class',
      code: cls.code,
      name: cls.name,
      color: cls.color,
      remark: '医疗本体类（devmock 自测数据）',
      parent: cls.parent ? { code: cls.parent, name: classByCode(cls.parent).name } : null,
      attributes: attributes,
      relations: relations,
      synonyms: [cls.name + '类'],
      mappings: [{ table: mappingTable[cls.code] || 'dim_' + cls.code, column: null, kind: 'class' }],
      instances: cls.code === 'CLS_DRUG'
        ? [{ code: 'D_METFORMIN', name: '二甲双胍' }, { code: 'D_GLICLAZIDE', name: '格列齐特' }]
        : cls.code === 'CLS_LAB_TEST'
          ? [{ code: 'LAB_HBA1C', name: '糖化血红蛋白' }, { code: 'LAB_FBG', name: '空腹血糖' }]
          : cls.code === 'CLS_DEPARTMENT'
            ? [{ code: 'DEPT_ENDO', name: '内分泌科' }, { code: 'DEPT_CARDIO', name: '心内科' }]
            : [],
      instanceTotal: cls.instanceCount
    };
  }
  return {
    kind: 'class', code: code, name: code, color: '#676e80', remark: null, parent: null,
    attributes: [], relations: [], synonyms: [], mappings: [], instances: [], instanceTotal: 0
  };
}

// ---- 元数据与基准 mock ----

function buildMockTables() {
  return [
    {
      name: 'dim_patient', comment: '患者维度', rowCount: 20000,
      columns: [
        { name: 'patient_id', type: 'bigint', comment: '患者主键' },
        { name: 'birth_date', type: 'date', comment: '出生日期' }
      ]
    },
    {
      name: 'fact_diagnosis', comment: '诊断事实', rowCount: 60000,
      columns: [
        { name: 'disease_code', type: 'varchar(32)', comment: 'ICD-10 编码（恒标准）' },
        { name: 'diagnosis_date', type: 'datetime', comment: '诊断时间' }
      ]
    },
    {
      name: 'fact_lab_result', comment: '检验结果事实', rowCount: 80000,
      columns: [
        { name: 'test_code', type: 'varchar(32)', comment: '检验项目标准编码' },
        { name: 'result_value', type: 'decimal(10,4)', comment: '结果值' }
      ]
    },
    {
      name: 'fact_medication', comment: '用药事实', rowCount: 90000,
      columns: [
        { name: 'drug_code', type: 'varchar(32)', comment: '药品标准编码' },
        { name: 'dosage', type: 'decimal(10,2)', comment: '剂量' }
      ]
    }
  ];
}

function buildMockLlmStatus() {
  return {
    enabled: true,
    model: 'deepseek-flash',
    baseUrl: 'https://api.deepseek.com/v1',
    cacheEntries: 2,
    lastMode: 'llm'
  };
}

function buildMockBenchmark(runId) {
  return {
    runId: runId,
    runMode: 'mock',
    totalCount: 36,
    ontologyCorrect: 34,
    traditionalCorrect: 21,
    ontologyAccuracy: 94,
    traditionalAccuracy: 58,
    ontologyElapsedMs: 4120,
    traditionalElapsedMs: 860,
    categoryStats: [
      { category: '简单单表', ontologyAccuracy: 100, traditionalAccuracy: 92 },
      { category: '两表关联', ontologyAccuracy: 96, traditionalAccuracy: 72 },
      { category: '多表复杂', ontologyAccuracy: 92, traditionalAccuracy: 48 },
      { category: '同义词理解', ontologyAccuracy: 100, traditionalAccuracy: 17 },
      { category: '时间修饰歧义', ontologyAccuracy: 89, traditionalAccuracy: 30 },
      { category: '多层推理', ontologyAccuracy: 85, traditionalAccuracy: 20 }
    ],
    items: [
      {
        questionNo: 1, category: '同义词理解', question: CORE_QUESTION,
        goldenCount: '327',
        ontologySql: 'SELECT COUNT(DISTINCT p.patient_id) ...',
        ontologyCount: '327', ontologyOk: true,
        traditionalSql: 'SELECT COUNT(DISTINCT p.patient_id) ... LIKE ...',
        traditionalCount: '1562', traditionalOk: false
      },
      {
        questionNo: 2, category: '简单单表', question: '患者共有多少人？',
        goldenCount: '20000',
        ontologySql: 'SELECT COUNT(*) FROM dim_patient',
        ontologyCount: '20000', ontologyOk: true,
        traditionalSql: 'SELECT COUNT(*) FROM dim_patient',
        traditionalCount: '20000', traditionalOk: true
      }
    ]
  };
}

// ==================== 本体运维端点（契约第 9 节，不设 devmock） ====================

/**
 * GET /api/ontology/admin/instances
 * @param {string} classCode 可选，按类过滤
 * @param {AbortSignal} signal 取消信号
 * @returns {Promise<Array>} 实例列表（含 protected/synonyms/mapping）
 */
export function adminInstances(classCode, signal) {
  const suffix = classCode ? '?classCode=' + encodeURIComponent(classCode) : '';
  return request('/api/ontology/admin/instances' + suffix, { signal: signal });
}

/** POST /api/ontology/admin/instance（body: {classCode,code,nameCn,remark?,synonyms?[],mapping?}） */
export function adminCreateInstance(body, signal) {
  return request('/api/ontology/admin/instance', { method: 'POST', body: body, signal: signal });
}

/** POST /api/ontology/admin/instance/update（body: {code,nameCn?,remark?}） */
export function adminUpdateInstance(body, signal) {
  return request('/api/ontology/admin/instance/update', { method: 'POST', body: body, signal: signal });
}

/** POST /api/ontology/admin/instance/delete（body: {code}） */
export function adminDeleteInstance(code, signal) {
  return request('/api/ontology/admin/instance/delete', {
    method: 'POST', body: { code: code }, signal: signal
  });
}

/** POST /api/ontology/admin/synonym/add（body: {term,targetCode}） */
export function adminAddSynonym(term, targetCode, signal) {
  return request('/api/ontology/admin/synonym/add', {
    method: 'POST', body: { term: term, targetCode: targetCode }, signal: signal
  });
}

/** POST /api/ontology/admin/synonym/delete（body: {term}） */
export function adminDeleteSynonym(term, signal) {
  return request('/api/ontology/admin/synonym/delete', {
    method: 'POST', body: { term: term }, signal: signal
  });
}

/** POST /api/ontology/admin/mapping/update（body: {instanceCode,table,column,valueExpr}） */
export function adminUpdateMapping(body, signal) {
  return request('/api/ontology/admin/mapping/update', { method: 'POST', body: body, signal: signal });
}

/** POST /api/ontology/admin/reload，响应 {reloaded: true} */
export function adminReload(signal) {
  return request('/api/ontology/admin/reload', { method: 'POST', signal: signal });
}

/** GET /api/ontology/admin/changes，响应最近 50 个变更集 */
export function adminChanges(signal) {
  return request('/api/ontology/admin/changes', { signal: signal });
}

/** POST /api/ontology/admin/revert，回退最近一次变更（最新集须非回退集） */
export function adminRevert(signal) {
  return request('/api/ontology/admin/revert', { method: 'POST', signal: signal });
}

/** GET /api/ontology/admin/coverage，响应 {columns:[...]} */
export function adminCoverage(signal) {
  return request('/api/ontology/admin/coverage', { signal: signal });
}
