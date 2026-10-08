# OntoQuery API 契约（后端与前端严格按此实现，字段名不得增删改）

JSON 字段一律 camelCase。时间单位毫秒。错误由 GlobalExceptionHandler 统一返回 `{code, message}`（5 位错误码）。

## 1. 查询端点

### POST /api/query/ontology
请求体 `{"question": "..."}`，响应 PipelineResult（engine 固定 "ontology"）。

### POST /api/query/traditional
请求体同上，响应 PipelineResult（engine 固定 "traditional"）。

### POST /api/query/compare
请求体同上，响应：

```json
{
  "question": "近一个月诊断为2型糖尿病且HbA1c大于7的患者中，使用二甲双胍治疗的有多少人？",
  "ontology":    { "PipelineResult": "见下，engine=ontology" },
  "traditional": { "PipelineResult": "见下，engine=traditional" }
}
```

## 2. PipelineResult（两条管线统一结构）

```json
{
  "engine": "ontology",
  "mode": "deterministic",
  "modeLabel": "确定性本体推理",
  "fallbackReason": null,
  "question": "原始问题",
  "steps": [
    {
      "key": "ner",
      "title": "词典识别",
      "status": "ok",
      "elapsedMs": 3,
      "summary": "一句话结论",
      "items": [ /* TraceItem，见第 3 节 */ ]
    }
  ],
  "sql": "SELECT COUNT(DISTINCT ...) ...",
  "sqlStatus": "ok",
  "result": {
    "columns": ["patient_count"],
    "rows": [["327"]],
    "rowCount": 1,
    "truncated": false,
    "elapsedMs": 45,
    "error": null
  },
  "confidence": {
    "score": 92,
    "formula": "100 - 40*(1-0.92) - 12*0 - 8*0",
    "factors": [
      {"label": "实体平均得分 0.92（同义词命中）", "delta": -3},
      {"label": "无未决歧义", "delta": 0}
    ]
  },
  "risks": [
    {
      "code": "SYNONYM_EVIDENCE",
      "level": "high",
      "title": "同义词漏配",
      "detail": "该疾病在数据中存在 3 种写法，LIKE 原词实测漏配 2 种",
      "evidence": [{"sql": "SELECT disease_name, COUNT(*) ...", "result": "糖化血红蛋白 412 / HbA1c 388 / HbA1c% 401"}]
    }
  ],
  "error": null,
  "totalElapsedMs": 180
}
```

字段说明：
- `mode`：ontology 侧恒 `"deterministic"`；traditional 侧 `"llm"`（真实调用）/ `"llm-cache"`（缓存命中）/ `"mock"`（禁用或降级，此时 `fallbackReason` 说明原因）。
- `sqlStatus`：`ok` | `error`（生成/执行失败，`result.error` 有值）| `none`（无法生成，如超纲问题）。
- `steps[].status`：`ok` | `warn` | `error`。
- `risks`：ontology 侧可为空数组或 info 级提示；traditional 侧由 8 条 linter 规则产出。
- 计数类问题 `result.rows[0][0]` 即答案数字，UI 直接展示。

### steps[].key 约定（前端按此渲染固定五步）

| key | ontology 侧标题 | traditional 侧标题 |
|---|---|---|
| ner | 词典识别 | - |
| relation | 关系链接 | - |
| reasoning | 本体推理 | - |
| path | 路径规划与 SQL 生成 | - |
| verify | 校验与置信度 | - |
| schema | - | 模式读取 |
| link | - | 列名匹配 |
| join | - | 关联推断 |
| generate | - | LLM 生成 |
| risk | - | 风险检测 |

## 3. TraceItem（steps[].items[] 元素）

`type` 决定渲染方式：

| type | 字段 | 渲染 |
|---|---|---|
| kv | label, from, to, rule?, level? | 键值行：label: from -> to（rule 灰字小注） |
| entity-chip | from, to | 实体胶囊：from 映射到 to（本体概念） |
| relation-edge | from, to, label | A --label--> B |
| path-chain | values[] | 胶囊+箭头横向链 |
| sql-pre | text | 等宽 SQL 片段（可滚动） |
| kv-table | columns[], rows[][] | 小型表格 |
| text | text, level | 纯文本行（level: ok/warn/error/info） |

`level` 取值 `ok | warn | error | info`，仅影响颜色。

## 4. 本体图谱端点

### GET /api/ontology/tree

```json
[
  {"code": "CLS_ENTITY_GROUP", "name": "临床实体", "color": "#06b6d4", "instanceCount": 182,
   "children": [
     {"code": "CLS_DISEASE", "name": "疾病", "color": "#f87171", "instanceCount": 80, "children": []}
   ]}
]
```

顶层为分组类（人员/临床实体/临床事件），叶子为可映射类。`instanceCount` 为该类直接实例数。

### GET /api/ontology/graph

```json
{
  "nodes": [
    {"code": "CLS_PATIENT", "name": "患者", "color": "#8b5cf6", "layer": 1, "instanceCount": 20000}
  ],
  "edges": [
    {"from": "CLS_PATIENT", "to": "CLS_DIAGNOSIS", "label": "被诊断为", "kind": "data"},
    {"from": "CLS_DISEASE", "to": "CLS_DISEASE", "label": "subClassOf", "kind": "subclass"}
  ]
}
```

`kind=data` 实线箭头（对象关系）；`kind=subclass` 虚线（类层级）。`layer` 供分层布局（0 顶部分组，1 核心类，2 事件类，3 维度类）。

### GET /api/ontology/entity/{code}

code 可为类编码或实例编码，响应按 `kind` 判别：

```json
{
  "kind": "class",
  "code": "CLS_DISEASE",
  "name": "疾病",
  "color": "#f87171",
  "remark": "ICD-10 疾病概念",
  "parent": {"code": "CLS_ENTITY_GROUP", "name": "临床实体"},
  "attributes": [
    {"code": "ATTR_RESULT_VALUE", "name": "结果值", "dataType": "decimal", "unit": "%", "valueLow": 4.0, "valueHigh": 6.0,
     "mappings": [{"table": "fact_lab_result", "column": "result_value"}]}
  ],
  "relations": [
    {"code": "REL_DIAGNOSED_WITH", "name": "被诊断为", "direction": "out", "target": {"code": "CLS_DIAGNOSIS", "name": "诊断记录"}}
  ],
  "synonyms": ["病种"],
  "mappings": [{"table": "dim_disease", "column": null, "kind": "class"}],
  "instances": [{"code": "E11", "name": "2型糖尿病"}],
  "instanceTotal": 80
}
```

实例详情（kind=instance）：`{kind, code, name, classInfo: {code, name, color}, synonyms: ["格华止", ...], mappings: [{table: "fact_diagnosis", column: "disease_code", kind: "instance", valueExpr: "{t}.disease_code = 'E11'"}], remark}`。类详情的 `instances` 最多返回 50 条，`instanceTotal` 为总数。

## 5. 元数据端点

### GET /api/meta/tables

```json
[
  {"name": "fact_diagnosis", "comment": "诊断事实", "rowCount": 60000,
   "columns": [{"name": "disease_code", "type": "varchar(32)", "comment": "ICD-10 编码（恒标准）"}]}
]
```

### GET /api/meta/llm-status

```json
{"enabled": true, "model": "deepseek-flash", "baseUrl": "https://api.deepseek.com/v1", "cacheEntries": 2, "lastMode": "llm"}
```

### POST /api/meta/llm-cache/clear

响应 `{"cleared": 2}`。

## 6. 基准端点

### POST /api/benchmark/run?forceMock=true

forceMock=true 时传统侧强制 mock（秒级）；缺省按 llm.enabled 走真实 LLM（串行+缓存）。响应：

```json
{
  "runId": 3,
  "runMode": "mock",
  "totalCount": 36,
  "ontologyCorrect": 34,
  "traditionalCorrect": 21,
  "ontologyAccuracy": 94,
  "traditionalAccuracy": 58,
  "ontologyElapsedMs": 4120,
  "traditionalElapsedMs": 860,
  "categoryStats": [
    {"category": "同义词理解", "ontologyAccuracy": 100, "traditionalAccuracy": 17}
  ],
  "items": [
    {"questionNo": 1, "category": "简单单表", "question": "...",
     "goldenCount": "327", "ontologySql": "...", "ontologyCount": "327", "ontologyOk": true,
     "traditionalSql": "...", "traditionalCount": "1562", "traditionalOk": false}
  ]
}
```

准确率为百分数取整。ontologyCount/traditionalCount 为标量字符串（无法取数时 null）。

### GET /api/benchmark/latest

同上结构；无历史时 `{"run": null}`。

## 7. 核心演示问题与黄金 SQL 模板（各端点实现的对齐基准）

问题：`近一个月诊断为2型糖尿病且HbA1c大于7的患者中，使用二甲双胍治疗的有多少人？`

本体侧必须生成的 SQL（PathToSqlBuilder 单测逐字符断言；别名 t0/t1/t2 按约束出现顺序编号）：

```sql
SELECT COUNT(DISTINCT p.patient_id) AS patient_count
FROM dim_patient p
WHERE EXISTS (SELECT 1 FROM fact_diagnosis t0
              WHERE t0.patient_id = p.patient_id
                AND t0.disease_code = 'E11'
                AND t0.diagnosis_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY))
  AND EXISTS (SELECT 1 FROM fact_lab_result t1
              WHERE t1.patient_id = p.patient_id
                AND t1.test_code = 'LAB_HBA1C'
                AND t1.result_value > 7)
  AND EXISTS (SELECT 1 FROM fact_medication t2
              WHERE t2.patient_id = p.patient_id
                AND t2.drug_code = 'D_METFORMIN')
```

## 8. 本体编码约定（种子与引擎共用，不得偏离）

类（ont_class.code）：`CLS_PERSON` 患者 / `CLS_VISIT` 就诊 / `CLS_DIAGNOSIS` 诊断记录 / `CLS_LAB_RESULT` 检验结果 / `CLS_MEDICATION` 用药记录 / `CLS_DISEASE` 疾病 / `CLS_LAB_TEST` 检验项目 / `CLS_DRUG` 药品 / `CLS_DEPARTMENT` 科室；分组类：`CLS_PERSON_GROUP` 人员 / `CLS_ENTITY_GROUP` 临床实体 / `CLS_EVENT_GROUP` 临床事件。

患者出发的四条 1:N 关系（表 + join 条件，`{t}` 事件表别名、`{a}` 锚表别名）：
- `REL_HAS_VISIT` 就诊于：fact_visit，`{t}.patient_id = {a}.patient_id`
- `REL_DIAGNOSED_WITH` 被诊断为：fact_diagnosis，`{t}.patient_id = {a}.patient_id`
- `REL_UNDERGOES_TEST` 有检验结果：fact_lab_result，`{t}.patient_id = {a}.patient_id`
- `REL_TAKES_DRUG` 使用药物：fact_medication，`{t}.patient_id = {a}.patient_id`

语义边（N:1，供推理展示，不生成 JOIN）：`REL_DIAGNOSIS_OF` 确诊为（诊断记录->疾病）、`REL_TEST_OF` 检验项目为（检验结果->检验项目）、`REL_DRUG_OF` 使用药品为（用药记录->药品）、`REL_VISIT_DEPT` 就诊科室（就诊->科室）。

关键实例（ont_instance，code 即业务码）：
- `E11` 2型糖尿病（CLS_DISEASE），映射 valueExpr `{t}.disease_code = 'E11'`（{t} 由路径解析为 fact_diagnosis 别名）
- `D_METFORMIN` 二甲双胍（CLS_DRUG），`{t}.drug_code = 'D_METFORMIN'`
- `LAB_HBA1C` 糖化血红蛋白（CLS_LAB_TEST），`{t}.test_code = 'LAB_HBA1C'`

数据属性：`ATTR_RESULT_VALUE` 结果值（CLS_LAB_RESULT，decimal，单位 %，正常值 4-6，映射 `{t}.result_value`）、`ATTR_DOSAGE` 剂量（CLS_MEDICATION，映射 `{t}.dosage`）、`ATTR_AGE` 年龄（CLS_PERSON，虚拟属性由 birth_date 推导，展示用）。

同义词种子（同义词陷阱的正面对照）：E11 <- {II型糖尿病, 糖尿病II型, 成人糖尿病, 非胰岛素依赖型糖尿病}；D_METFORMIN <- {格华止, 盐酸二甲双胍片, 降糖片}; LAB_HBA1C <- {HbA1c, HbA1c%, 糖基化血红蛋白, 糖化血红蛋白A1c}。

推理规则（ont_rule.rule_code）：`TIME_ATTACH_PROXIMITY`（时间就近挂靠）、`VALUE_RANGE`（值域校验）、`SUBCLASS_CLOSURE`（子类闭包）。

## 9. 本体运维端点（/api/ontology/admin）

统一前缀 `/api/ontology/admin`。变更类端点（instance / instance/update / instance/delete /
synonym/add / synonym/delete / mapping/update / revert）保存后自动热加载并重跑本体侧 36 题
校验（内存比对，不落 benchmark_run），响应结构：

```json
{
  "changesetId": 7,
  "applied": true,
  "validation": {
    "beforeAccuracy": 97, "afterAccuracy": 100,
    "beforeFailed": [3], "afterFailed": [],
    "regressed": [], "improved": [3]
  }
}
```

| 方法 | 路径 | 请求体 | 说明 |
|---|---|---|---|
| GET | /api/ontology/admin/instances?classCode= | - | 实例列表：[{code, nameCn, remark, classCode, className, protected, synonyms[], mapping{table,column,valueExpr} 或 null}] |
| POST | /api/ontology/admin/instance | {classCode, code, nameCn, remark?, synonyms?[], mapping?{table,column,valueExpr}} | 创建实例（可携带同义词与映射） |
| POST | /api/ontology/admin/instance/update | {code, nameCn?, remark?} | 改名/备注；code 不可改；null 字段不改，空串清空 remark |
| POST | /api/ontology/admin/instance/delete | {code} | 级联删除同义词与映射 |
| POST | /api/ontology/admin/synonym/add | {term, targetCode} | 加同义词（首尾空白入库前去除；保护实例放行） |
| POST | /api/ontology/admin/synonym/delete | {term} | 删同义词（类级同义词拒绝） |
| POST | /api/ontology/admin/mapping/update | {instanceCode, table, column, valueExpr} | 改实例映射（valueExpr 白名单校验） |
| POST | /api/ontology/admin/reload | - | 手动热加载，返回 {reloaded: true} |
| GET | /api/ontology/admin/changes | - | 最近 50 个变更集：[{changesetId, isRevert, operator, createTime, ops:[{opType, targetKind, targetCode}]}] |
| POST | /api/ontology/admin/revert | - | 回退最近一次变更（最新集须非回退集，连续回退第二次 A0410；产生 is_revert=1 的新变更集） |
| GET | /api/ontology/admin/coverage | - | 覆盖缺口扫描：{columns:[{table, column, classCode, classCodes[], unmapped:[{term, rowCount}]}]}；classCode 为主归属类，classCodes 为联合比对的类集合（如 fact_diagnosis.disease_name 联合 CLS_DISEASE 与 CLS_DIABETES） |

错误码：A0401 参数非法、A0403 核心保护集（E11 / D_METFORMIN / LAB_HBA1C）拒绝、
A0404 目标不存在、A0409 code 或 term 重复、A0410 无可回退变更集、
B0501 valueExpr 校验失败（附具体原因）、B0502 数据已提交但热加载/验证失败。

valueExpr 白名单：形如 `{t}.列名 操作符 '值'` 或数值（操作符 = <> != >= <= > <）；
表限 9 张业务表（dim_patient / dim_disease / dim_lab_test / dim_drug / dim_department /
fact_visit / fact_diagnosis / fact_lab_result / fact_medication）；列须真实存在且与
valueExpr 中列名一致；禁止子查询与引号逃逸。
