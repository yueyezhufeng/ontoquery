# OntoQuery 原型与页面说明

作者：月夜烛峰

版本：1.0.0

本文说明原型的页面结构、交互与数据来源。所有页面数字均来自后端真实查询（SafeQueryExecutor 执行结果），前端不硬编码任何结果数字；下文引用的数字均为 2026-10-08 实测（基准 run 8）。

## 1. 技术形态

- 零构建前端：`src/main/resources/static/` 下原生 ES module 单页应用，hash 路由，无 npm、无打包器、无第三方库。
- 单一入口 `index.html`：左侧导航栏（220px）+ 顶栏（56px）+ 视图容器；`js/app.js` 负责 hash 路由与页面懒加载（`import()` 动态导入）。
- 深色主题设计令牌：底色四级（#0a0c10 起）、强调紫 #8b5cf6（本体侧）与青 #06b6d4（传统侧）、卡片圆角 10px、正文字号 13px；全部收敛在 `css/app.css` 顶部变量区。
- **全站禁用 emoji**：图标一律内联 SVG（`js/icons.js`，24x24 stroke 风格，共 33 个：compare / brain / robot / graph / flask / scale / circle-check / triangle-alert / octagon-x / zoom 等）。任何页面、文档、注释不得出现 emoji。

## 2. 六个页面

| 路由 | 页面 | 一句话定位 |
|---|---|---|
| #/compare | 对比问答 | 主演示页：同一问题双管线并行执行，过程与结果并排 |
| #/ontology-qa | 本体问数 | 本体管线单管线全屏问答 |
| #/traditional-qa | 传统问数 | 传统管线单管线全屏问答 |
| #/graph | 本体图谱 | 类树 + 关系图画布 + 详情面板三栏联动 |
| #/parser | 语义解析过程 | 最近一次对比的双侧五步轨迹逐项展开 |
| #/comparison | 对比分析 | 基准实测指标 + 12 维度定性对比 + 6 场景卡 |

### 2.1 对比问答（#/compare）

双栏 chat 布局，底部共享输入框与建议芯片（含核心演示问题）。提交后调用 `POST /api/query/compare`，响应渲染：

- **五步 stepper**：圆点连线纵向时间线，每步显示名称与 elapsedMs；点击展开轨迹项（实体芯片 / 关系边 / 路径链 / kv 决策 / SQL 预览）。左侧本体五步（词典识别 -> 关系链接 -> 本体推理 -> 路径规划与 SQL 生成 -> 校验与置信度），右侧传统五步（模式读取 -> 列名匹配 -> 关联推断 -> LLM 生成 -> 风险检测）。
- **SQL 块**：关键字高亮（SELECT/WHERE/EXISTS/AND 等），可折叠。
- **结果表**：真实执行首行结果与行数；被截断（超过 500 行上限）时标注。
- **置信度卡**：分数 + 公式 + 逐项扣分因子（本体侧如"实体平均得分 0.9425（同义词命中）-2"；传统侧如"值域知识未参与 -3"）。
- **模式徽标**：传统侧右上角常显——紫"LLM: deepseek-flash" / 青"LLM 缓存命中（deepseek-flash）" / 琥珀"模拟模式（降级）"；本体侧固定为"确定性本体推理"。
- **风险检测面板**：传统侧可折叠面板，标题为"风险检测 N 条（M 条高危）"，逐条列出 linter 命中规则与真实证据查询数字（如"E11 存在 4 种写法共 2000 行，LIKE '%2型糖尿病%' 仅命中 500 行，漏配 1500 行（75%）"）。
- 历史结果存 localStorage，供语义解析过程页复用；刷新不丢。

### 2.2 本体问数 / 传统问数（#/ontology-qa、#/traditional-qa）

单管线全屏 chat：只跑一侧，界面更聚焦，适合分别讲解两种方法的内部过程。数据分别来自 `POST /api/query/ontology` 与 `/api/query/traditional`，渲染组件与对比页一致。

### 2.3 本体图谱（#/graph）

三栏联动：

- 左：类树（`GET /api/ontology/tree`），节点带真实实例数徽标；
- 中：SVG 关系图画布（`GET /api/ontology/graph`），数据关系实线箭头、subClassOf 虚线、分层布局，支持缩放平移，节点点击联动右侧；
- 右：详情面板（`GET /api/ontology/entity/{code}`）——基本信息、数据属性、对象关系芯片、映射到数据源芯片（表/列/value_expr）。

示例：点开实例 E11，可见所属类"糖尿病类"、同义词（II型糖尿病 / 糖尿病II型 / 成人糖尿病 / 非胰岛素依赖型糖尿病）、映射 `fact_diagnosis.disease_code = 'E11'`。

### 2.4 语义解析过程（#/parser）

读取最近一次对比问答（localStorage）的双侧五步轨迹，以步骤卡片逐项展开；无缓存时自动执行一次核心问题。讲解"可解释性"时用这页：每一步的输入输出、规则依据（rule 字段）、未选中分支的原因（如时间挂靠"未挂到 fact_lab_result.test_date：距离远于诊断词"）全部可见。

### 2.5 对比分析（#/comparison）

- 顶部指标卡：本体准确率 / 传统准确率 / 两管线平均耗时，来自最近一次基准实测（`GET /api/benchmark/latest`）；未跑过基准时显示占位并标注"尚未运行基准结果"。
- 12 维度定性对比表：准确率、同义词、歧义消解、可解释性、多跳查询、耗时、成本、维护、冷启动、可控性、扩展性、审计。
- 6 个典型场景卡：基准六类目（简单单表 / 两表关联 / 多表复杂 / 同义词理解 / 时间修饰歧义 / 多层推理）两侧实测准确率。
- 顶栏 LLM 状态芯片：显示 enabled/model/缓存条目/最近模式，缓存非空时右侧提供清空按钮（调用 `POST /api/meta/llm-cache/clear`），演示前重置缓存用。

## 3. 组件清单（js/components/）

| 组件 | 职责 |
|---|---|
| stepper.js | 五步轨迹时间线与轨迹项展开 |
| sql-block.js | SQL 关键字高亮块 |
| result-table.js | 结果表渲染（截断标注） |
| risk-panel.js | 已知问题面板（规则 + 证据数字） |
| mode-badge.js | LLM 模式徽标（llm / llm-cache / mock） |
| chat.js | 对话气泡容器与输入区 |

## 4. 前端规约

- 输出到 HTML 的任何用户数据先经 `escapeHtml`（util.js）转义。
- 所有接口调用集中在 `js/api.js`，字段名与 docs/CONTRACT.md 严格一致。
- 图标只从 `js/icons.js` 取内联 SVG，禁止引入图标字体或图片资源。
