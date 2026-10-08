# OntoQuery 本体论智能问数系统 — 方案设计

作者：月夜烛峰

版本：1.0.0

## 1. 背景与目标

自然语言问数（NL2SQL）在真实业务库上的核心困难不是"生成一条语法正确的 SQL"，而是：

1. **同义词与写法散布**——同一实体在数据中存在多种写法（2型糖尿病 / 糖尿病II型 / II型糖尿病；格华止 / 盐酸二甲双胍片），按用户原词字面匹配必然漏配；
2. **时间修饰歧义**——"近一个月诊断为 2 型糖尿病且 HbA1c 大于 7"中"近一个月"应挂靠诊断日期而非检验日期，挂错列语义随数据分布漂移；
3. **多表关联基数**——患者对诊断/检验/用药均为一对多，JOIN 后直接 COUNT 统计行数导致结果膨胀；
4. **不可解释**——端到端 LLM 无法说明"为什么这样查"，业务方无法审计。

本系统用一个可运行的原型证明：把领域知识显式建模为**轻量关系型本体**（类/实例/同义词/关系/属性/映射/规则七类元数据），配合确定性推理管线，可以在不牺牲灵活性的前提下系统性解决上述四类问题，并与"纯 LLM + 表结构"的传统路线在同一数据集上并排对比。

**演示核心问题**：近一个月诊断为2型糖尿病且HbA1c大于7的患者中，使用二甲双胍治疗的有多少人？

两侧真实执行 SQL、真实计数；差异不是脚本演的，而是数据中真实存在的陷阱被两种方法真实处理的结果。

## 2. 总体架构

```
浏览器 SPA（static/，原生 ES module，零构建）
   │ fetch JSON（契约见 docs/CONTRACT.md）
   ▼
Spring Boot 8080（Java 21，四个 starter，无 JPA 无 Lombok）
   ├─ QueryController    /api/query/{ontology|traditional|compare}
   ├─ OntologyController /api/ontology/{tree|graph|entity/{code}}
   ├─ MetaController     /api/meta/{tables|llm-status|llm-cache/clear}
   ├─ BenchmarkController /api/benchmark/{run|latest}
   ├─ CompareService（双管线并行，手建命名线程池）
   │
   ├─【本体增强管线 · 确定性 Java · 无 LLM】
   │    DictionaryNer（AC/Trie 最长匹配）
   │      -> RelationLinker（domain/range 匹配）
   │      -> ReasoningService（同义归一/子类闭包/时间就近挂靠/值域校验）
   │      -> PathPlanner（类-关系图 BFS）
   │      -> PathToSqlBuilder（EXISTS 模板）
   │      -> QueryVerifier（EXPLAIN + 置信度公式）
   │
   ├─【传统 NL2SQL 管线】
   │    SchemaLoader（SHOW CREATE TABLE）
   │      -> SchemaLinker（列名/注释字面匹配）
   │      -> JoinInferer（同名列推断）
   │      -> LlmClient（DeepSeek，OpenAI 兼容协议；缓存/重试/降级 Mock）
   │      -> SqlRiskLinter（8 条规则 + 真实证据查询）
   │
   └─ SafeQueryExecutor（唯一 SQL 出口：白名单单条 SELECT、10s 超时、500 行上限）
          │
          ▼
      MySQL 8（ontoquery 库：ont_* 本体元表 + dim_*/fact_* 业务表 + 系统表）
```

两侧共用同一个 SafeQueryExecutor 与同一份 ResultTable 结构，保证对比公平：谁也不许走后门。

## 3. 轻量关系型本体模型

不用图数据库、不用三元组存储，本体就是 7 张 MySQL 元表（OntologyRepository 启动全量加载进内存）：

| 表 | 内容 | 示例 |
|---|---|---|
| ont_class | 类层级（12 类 + 3 分组） | CLS_DISEASE 疾病 |
| ont_instance | 实例=业务标准编码 | E11 2型糖尿病 |
| ont_synonym | 同义词 -> 实例/类 | 格华止 -> D_METFORMIN |
| ont_relation | 对象关系（domain/range/基数） | REL_DIAGNOSED_WITH 患者->诊断记录 1:N |
| ont_attribute | 数据属性 + 值域 | ATTR_RESULT_VALUE decimal % [4,6] |
| ont_mapping | 本体 -> 物理表/列/谓词 | E11 -> "{t}.disease_code = 'E11'" |
| ont_rule | 推理规则（JSON 配置） | TIME_ATTACH_PROXIMITY / VALUE_RANGE / SUBCLASS_CLOSURE |

映射语义的关键设计：value_expr / join_condition 中的 `{t}` 是**事件表别名占位符**。同一实例（E11）经不同关系到达时，`{t}` 由路径规划解析为对应物理表别名——本体因此与物理存储解耦，换表只改映射不改引擎。

## 4. 本体增强管线（五步，全程结构化轨迹）

1. **词典识别**：实例名+编码+同义词+类名+时间词+比较词+意图词构建 Trie，最长匹配。每个提及打分：精确实例 1.0 / 同义词 0.92 / 类名推断 0.85。
2. **关系链接**：按提及的类做 domain/range 匹配，实体挂到关系边（疾病实例经 REL_DIAGNOSIS_OF 反推诊断记录）。
3. **本体推理**（每个决策写入轨迹）：
   - 同义归一：格华止 -> D_METFORMIN（依据同义词表）；
   - 子类闭包：类级提及展开为实例集（如"胰岛素"展开为全部胰岛素类药品编码；被闭包涵盖的实例提及自动去重）；
   - **时间就近挂靠**：时间词向左找最近事件词，"近一个月[诊断]" -> diagnosis_date，轨迹明确列出"未挂到 test_date / prescribe_date 及理由"；
   - 值域校验：HbA1c 正常 [4,6]、可信 [3,20]，阈值 7 合法、70 则提示单位可疑；
   - 合取/析取判别：同宿主两次提及之间有"或"则合并为单约束 OR 谓词，无"或"则各自成独立 EXISTS（AND 语义）；
   - 否定识别：提及前出现"不含/排除/除外"等提示词生成 NOT 谓词；同宿主存在否定约束时，肯定约束改写为患者队列相关 EXISTS（"E11 患者的其他诊断（不含糖尿病类）"）；
   - 值域分段知识：年龄按青年（小于 45）/中年（45-59）/老年（60 及以上）生成 CASE 桶表达式分组。
4. **路径规划与 SQL 生成**：以患者为锚 BFS 关系图，每个约束展开为独立 EXISTS 子查询：

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

EXISTS 模板天然免疫 JOIN 扇出（对照传统侧缺陷三）；单语句不超过一个主表加若干子查询，符合开发手册"超过三个表禁止 JOIN"的规约精神。意图覆盖计数 / 分组计数 / TopN / 均值四类，超纲问题返回可解释的 error 步骤。

5. **校验与置信度**：EXPLAIN 检查全表扫描；置信度公式

   `score = clamp(100 - 40 x (1 - avg实体分) - 12 x 未决歧义数 - 8 x verify警告数, 55, 100)`

   每个扣分项以 factors 列出，前端逐条展示——可解释性落在数字上。

## 5. 传统 NL2SQL 管线（五步）

1. **模式读取**：9 张业务表 SHOW CREATE TABLE（含中文列注释）。
2. **列名匹配**：问题词与列名/注释做字面子串匹配——"患者"命中 patient_id 注释，而"HbA1c""2型糖尿病"无命中（这正是 LLM 后续靠猜的根源，轨迹如实展示）。
3. **关联推断**：同名列（patient_id/visit_id）推断候选 JOIN，无基数语义。
4. **LLM 生成**：DeepSeek（OpenAI 兼容 /chat/completions，model deepseek-flash，temperature 0，max_tokens 4000）。system 提示只输出一条 MySQL 8 SELECT；user 携带全部 DDL 与问题，zero-shot 保证公平。输出经 SqlExtractor（剥围栏/取首条/必须 SELECT 开头）；抽取失败带错误回执重试一次。响应按 SHA256(model|question|ddlHash) 缓存于 llm_cache 表，命中直接复用。网络失败重试后降级 MockLlmGenerator（确定性缺陷 SQL，与真 LLM 走完全相同下游），mode=mock 如实标注，UI 琥珀徽标。
5. **风险检测**（8 条规则，基于实际生成的 SQL 与真实证据查询）：

| 规则 | 级别 | 说明 |
|---|---|---|
| FUZZY_LIKE | high | 前导通配 LIKE 作用于 *_name 列 |
| SYNONYM_EVIDENCE | high | 对 LIKE 词跑真实证据查询，量化"命中 X 行 / 标准码全量 Y 行 / 漏配 Z 行（P%）" |
| JOIN_FANOUT | high | JOIN + COUNT(*) 未去重 |
| TIME_MISATTACH | medium | 时间词应挂列 vs 实际过滤列不符/缺失 |
| NO_ON_CARTESIAN | high | JOIN 缺 ON |
| MISSING_AGG | medium | 问数量无聚合 |
| NON_MYSQL | medium | ILIKE / :: / TOP n |
| VALUE_RANGE_INFO | low | 值域知识可参与而未参与（提示） |

传统侧置信度：`clamp(100 - 10 x high - 6 x medium - 3 x low, 30, 100)`。

## 6. 数据策略：确定性合成 + 同义词陷阱

**确定性**：全部数据由纯 SQL 生成（digit 派生表 CROSS JOIN 展开 + 算术散列 `(n*7919)%N`），无 RAND、无存储过程、无视图（账号无 CREATE ROUTINE/VIEW 权限，规约亦禁止）。同一脚本重跑结果逐位一致，demo 数字可复现。

**规模**：dim_patient 20,000；fact_visit 40,000；fact_diagnosis 60,000；fact_lab_result 120,000；fact_medication 90,000。

**陷阱（差异的真实来源）**：code 列恒标准、name 列按行散布多写法——

| 表 | 标准码列（恒定） | 名称列（按行散布） |
|---|---|---|
| fact_diagnosis | disease_code='E11' | disease_name 按 n%4：2型糖尿病/糖尿病II型/II型糖尿病/diabetes mellitus type 2 |
| fact_medication | drug_code='D_METFORMIN' | drug_name 按 n%3：二甲双胍/格华止/盐酸二甲双胍片 |
| fact_lab_result | test_code='LAB_HBA1C' | test_name 按 n%3：糖化血红蛋白/HbA1c/HbA1c% |

三条事实表各带独立日期列（diagnosis_date/test_date/prescribe_date），时间词挂错列即产生真实偏差。E11 患者队列的诊断日期锚定 CURDATE()-(patient_id%40)，保证"近一个月"窗口恒有足量数据（时间相对题永不落空）。

## 7. 基准 harness

36 题 = 6 类 x 6（简单单表/两表关联/多表复杂/同义词理解/时间修饰歧义/多层推理）。每题 goldenSql 与被测 SQL **同时执行**、标量精确比对（数值按 BigDecimal 值比较）——时间相对题不存固定答案，免疫时间漂移。结果落库 benchmark_run/benchmark_item；对比分析页消费 categoryStats（逐类两侧准确率）。传统侧默认 Mock 跑基准（秒级），forceMock=false 走真实 LLM（串行+缓存）。

## 8. 工程规约

- **阿里《Java开发手册（嵩山版）》强制条目全量遵守**：命名/包装类型/无魔法值/SLF4J/异常区分/线程池手动创建并命名/MySQL 建表规约（is_xxx、pk_/uk_/idx_、DECIMAL、必备三字段、禁外键存储过程）/安全规约（证据查询一律参数绑定）。
- 全站禁 emoji：图标一律内联 SVG（js/icons.js）。
- 只读安全：SafeQueryExecutor 白名单是两侧唯一 SQL 出口，DML/DDL 关键字直接拒绝。
- 作者署名统一为"月夜烛峰"。

## 9. 局限与边界

本体侧意图覆盖四类（计数/分组/TopN/均值），超纲问题诚实返回"无法解析"而非强行生成；本体质量决定上限（映射错误会系统性出错，但可审计可修正，这正是本体路线的运维逻辑）；传统侧 LLM 输出具有随机性，演示靠 temperature 0 + 响应缓存 + Mock 降级保证可复现，mode 徽标如实标注。
