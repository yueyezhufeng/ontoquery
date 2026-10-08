# OntoQuery 本体论智能问数系统

作者：月夜烛峰

同一个自然语言问题，两条管线并行真实执行、并排对比：

- **本体增强管线**：词典识别 -> 关系链接 -> 本体推理 -> 路径规划 -> SQL 生成校验。确定性 Java 实现，不依赖 LLM。
- **传统 NL2SQL 管线**：模式读取 -> 列名匹配 -> 关联推断 -> DeepSeek 生成 -> 风险检测。

演示数据中真实埋设了同义词写法散布、多表基数、时间挂靠歧义，两条管线处理后产生真实差异。页面上看到的每个数字都来自 SafeQueryExecutor 对数据库的真实查询，前后端不硬编码任何结果。

![对比问答：同一个问题两条管线并排执行，结果与过程轨迹直接对比](docs/images/shots/compare-core.png)

## 界面一览

![本体问数：单管线问答](docs/images/shots/ontology-qa.png)

![语义解析过程](docs/images/shots/parser-trace.png)

![本体图谱：实例详情](docs/images/shots/graph-entity.png)

![效果分析：36 题基准对比](docs/images/shots/comparison-benchmark.png)

![本体运维：实例编辑](docs/images/shots/ops-instance.png)

## 架构与流程

![总体架构图](docs/images/architecture.svg)

![双管线流程图](docs/images/pipeline-flow.svg)

## 文档

| 文档 | 说明 |
|---|---|
| [操作手册](docs/manual.md) | 环境搭建、演示路径、实测数字与截图 |
| [方案设计](docs/design.md) | 本体建模与双管线设计 |
| [API 契约](docs/CONTRACT.md) | 控制器与前端共同遵守的字段级契约 |
| [原型与页面说明](docs/prototype.md) | 七页 SPA 的页面结构说明 |
| [演示讲解词](docs/demo-script.md) | 演示流程讲稿 |

## 技术栈

Java 21 + Spring Boot 3.5.16（starter-web / starter-jdbc / mysql-connector-j / starter-test，无 JPA 无 Lombok）+ MySQL 8 + 原生 ES module 前端（零构建）。后端编码遵守《Java开发手册（嵩山版）》强制条目。

## 环境准备

1. 本地 MySQL 8（自建演示账号 demo/demo@127.0.0.1:3306，需有建库建表权限）。
2. JDK 21 与 Maven 3.9+（若本机 Maven 运行在更高版本 JDK 上，pom 已 pin release 21，无需处理）。
3. LLM 密钥（传统侧）：DeepSeek OpenAI 兼容端点。两种方式任选：
   - 项目根目录新建 `local.yml`（已被 .gitignore 忽略）：
     ```yaml
     ontoquery:
       llm:
         api-key: sk-你的密钥
     ```
   - 或环境变量 `ONTOQUERY_LLM_API_KEY`。
   未配置时传统侧自动降级确定性 Mock 生成器（UI 琥珀徽标注明"模拟模式"），系统照常可用。

## 初始化数据库（按顺序执行）

```bash
M=/usr/local/mysql/bin/mysql
$M -udemo -pdemo --default-character-set=utf8mb4 < src/main/resources/db/01_schema.sql
$M -udemo -pdemo --default-character-set=utf8mb4 < src/main/resources/db/02_ontology_seed.sql
$M -udemo -pdemo --default-character-set=utf8mb4 < src/main/resources/db/03_data.sql
$M -udemo -pdemo --default-character-set=utf8mb4 < src/main/resources/db/04_ontology_ops.sql
```

四个脚本均幂等可重跑；03 为纯 SQL 确定性生成（20,000 患者 / 40,000 就诊 / 60,000 诊断 / 120,000 检验 / 90,000 用药），重跑结果逐位一致；04 为本体运维审计表（ont_change_log），重跑 02 会让本体回到出厂状态但审计表不受影响。

## 构建与运行

```bash
mvn -q package          # 构建
mvn spring-boot:run     # 启动，端口 8080
```

浏览器打开 http://localhost:8080 ，从"对比问答"页开始。核心演示问题已放在建议芯片中：

> 近一个月诊断为2型糖尿病且HbA1c大于7的患者中，使用二甲双胍治疗的有多少人？

若 8080 被占用：`server.port` 可在 local.yml 覆盖。

## 常用接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | /api/query/compare | 双管线并行对比（主演示入口） |
| POST | /api/query/ontology | 仅本体管线 |
| POST | /api/query/traditional | 仅传统管线 |
| GET | /api/ontology/tree / graph / entity/{code} | 本体图谱三端点 |
| GET | /api/meta/tables / llm-status | 数据源与 LLM 状态 |
| POST | /api/meta/llm-cache/clear | 清空 LLM 缓存（演示前重置） |
| POST | /api/benchmark/run?forceMock=true | 36 题基准（Mock 秒级；缺省走真实 LLM 较慢） |
| GET | /api/benchmark/latest | 最近一次基准结果 |
| GET / POST | /api/ontology/admin/* | 本体运维（实例/同义词/映射编辑、热加载、变更历史、回退、覆盖扫描，契约第 9 节） |

## 目录结构

```
src/main/java/com/ontoquery/
├── ontology/        本体模型/加载/NER/关系链接/推理/路径/SQL 生成/校验/管线编排
│   ├── ops/         运维写操作与审计/回退/覆盖扫描
│   └── trace/       决策轨迹上下文
├── tradnl/          传统管线：模式读取/列名匹配/关联推断/管线编排
├── llm/             DeepSeek 客户端/缓存/SQL 抽取/Mock 缺陷生成器
├── risk/            SQL 风险检测（8 规则 + 真实证据查询）
├── compare/         双管线并行编排
├── benchmark/       基准运行器（36 题同跑比对）
├── web/             控制器与全局异常（5 位错误码）
├── sql/             SafeQueryExecutor（唯一 SQL 出口）/ ResultTable
├── config/          LLM 配置属性
└── support/         共享 DTO 与轻量分词
src/main/resources/
├── db/              01 建表 / 02 本体种子 / 03 确定性数据 / 04 本体变更审计
├── benchmark/       questions.json 36 题基准题库
└── static/          前端 SPA（七页，原生 ES module）
```

## 规约说明

- 全站禁用 emoji，图标一律内联 SVG。
- 遵守《Java开发手册（嵩山版）》强制条目（命名/包装类型/日志/并发/MySQL 建表/参数绑定等）。
- LLM 密钥不入仓库（local.yml 或环境变量）。
- 所有 SQL 仅经 SafeQueryExecutor 白名单执行（单条 SELECT、10 秒超时、500 行上限）。
- 本体写操作全部参数绑定并落审计（ont_change_log），valueExpr 过白名单校验（B0501）。
