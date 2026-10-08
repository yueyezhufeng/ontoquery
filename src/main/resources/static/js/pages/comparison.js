// OntoQuery 对比分析页 · 作者：月夜烛峰
// 顶部三个指标瓦片（/api/benchmark/latest，未运行显示占位与运行按钮）+
// 12 维度定性对比表 + 6 场景卡（categoryStats 逐类准确率条形对比）。
import { el, asArray, fmtMs, fmtNum } from '../util.js';
import { icon } from '../icons.js';
import { latestBenchmark, runBenchmark } from '../api.js';

const DIMENSIONS = [
  ['准确率', '概念到物理表的映射由本体显式约束，语义还原度高', '依赖模型对 Schema 的理解，复杂问题易出错'],
  ['同义词', '本体同义词表统一归一，如 HbA1c 与糖化血红蛋白', '依赖模型内在知识，未收录写法易漏匹配'],
  ['歧义消解', '依据本体关系与就近挂靠等规则消解，结论可复核', '黑盒猜测，消解结果不可控'],
  ['可解释性', '五步解析全程留痕，每一步可展开核对', '端到端生成，仅能查看最终 SQL'],
  ['多跳查询', '本体路径规划支持多跳关联，EXISTS 展开规避多表 JOIN', '三表以上关联时准确率明显下降'],
  ['耗时', '词典与规则推理为主，毫秒级完成解析', '单次 LLM 调用，秒级且受网络波动影响'],
  ['成本', '无按次调用费用，成本集中在本体建设', '按 token 计费，调用量增长带动成本线性上升'],
  ['维护', '领域知识沉淀在本体表内，可版本化管理', '依赖提示词与 bad case 微调，修复分散'],
  ['冷启动', '需先构建领域本体与映射，初期投入较高', '接入 Schema 即可用，开箱即用'],
  ['可控性', 'SQL 由白名单编码确定性拼接，行为可预测', '生成结果存在随机性，需执行层兜底'],
  ['扩展性', '新增类、关系、实例即插即用，引擎零改动', '领域变化需重新调优提示词或微调'],
  ['审计', '逐步 trace 可复查，出错可定位到具体环节', '仅能对最终 SQL 做事后审查']
];

function metricTile(iconName, label, value, sub, extraClass) {
  return el('div', { class: 'tile ' + (extraClass || '') }, [
    el('div', { class: 'tile-icon', html: icon(iconName, 17) }),
    el('div', { class: 'tile-value', text: value }),
    el('div', { class: 'tile-label', text: label }),
    sub ? el('div', { class: 'tile-sub', text: sub }) : null
  ]);
}

function runButton(label, busy) {
  return el('button', { class: 'btn primary run-btn', type: 'button', disabled: Boolean(busy) }, [
    el('span', { class: busy ? 'spinner' : 'btn-ic', html: busy ? null : icon('play', 13) }),
    el('span', { text: busy ? '运行中（强制 mock，秒级完成）...' : label })
  ]);
}

function barRow(label, value, cls) {
  const pct = Math.max(0, Math.min(100, Number.isFinite(Number(value)) ? Number(value) : 0));
  const bar = el('div', { class: 'bar-row' }, [
    el('span', { class: 'bar-label', text: label }),
    el('div', { class: 'bar-track' }, [
      el('div', { class: 'bar-fill ' + cls, style: 'width:' + pct + '%' })
    ]),
    el('span', { class: 'bar-value', text: Number.isFinite(Number(value)) ? value + '%' : '--' })
  ]);
  return bar;
}

function scenarioCard(stat) {
  const card = el('div', { class: 'scenario-card' }, [
    el('div', { class: 'scenario-title' }, [
      el('span', { class: 'scenario-ic', html: icon('target', 14) }),
      el('span', { text: stat.category || '未分类' })
    ]),
    barRow('本体', stat.ontologyAccuracy, 'ont'),
    barRow('传统', stat.traditionalAccuracy, 'trad')
  ]);
  const diff = Number(stat.ontologyAccuracy) - Number(stat.traditionalAccuracy);
  if (Number.isFinite(diff)) {
    card.appendChild(el('div', {
      class: 'scenario-diff ' + (diff >= 0 ? 'pos' : 'neg'),
      text: (diff >= 0 ? '本体领先 ' : '传统领先 ') + Math.abs(diff) + ' 个百分点'
    }));
  }
  return card;
}

function dimensionTable() {
  const table = el('table', { class: 'dim-table' });
  table.appendChild(el('thead', null, [
    el('tr', null, [
      el('th', { class: 'dim-col', text: '对比维度' }),
      el('th', { class: 'col-ont' }, [
        el('span', { class: 'th-ic', html: icon('brain', 13) }),
        el('span', { text: '本体增强' })
      ]),
      el('th', { class: 'col-trad' }, [
        el('span', { class: 'th-ic', html: icon('robot', 13) }),
        el('span', { text: '传统 NL2SQL' })
      ])
    ])
  ]));
  const body = el('tbody');
  DIMENSIONS.forEach((row) => {
    body.appendChild(el('tr', null, [
      el('td', { class: 'dim-col', text: row[0] }),
      el('td', { class: 'col-ont', text: row[1] }),
      el('td', { class: 'col-trad', text: row[2] })
    ]));
  });
  table.appendChild(body);
  return table;
}

export function mount(container, ctx) {
  const tiles = el('div', { class: 'tiles' });
  const tilesState = el('div', { class: 'tiles-state' });
  const scenarioGrid = el('div', { class: 'scenario-grid' });
  const runArea = el('div', { class: 'run-area' });

  const page = el('div', { class: 'page-root comparison-page' }, [
    tiles,
    tilesState,
    runArea,
    el('section', { class: 'panel' }, [
      el('div', { class: 'panel-header' }, [
        el('span', { class: 'panel-ic', html: icon('scale', 14) }),
        el('span', { class: 'panel-title', text: '12 维度定性对比' }),
        el('span', { class: 'panel-subtitle', text: '本体增强 vs 传统 NL2SQL' })
      ]),
      el('div', { class: 'panel-body' }, [dimensionTable()])
    ]),
    el('div', { class: 'section-title' }, [
      el('span', { class: 'section-ic', html: icon('chart', 15) }),
      el('span', { text: '场景准确率对比（基准测试逐类统计）' })
    ]),
    scenarioGrid
  ]);
  container.appendChild(page);

  let busy = false;

  function renderRun(run) {
    tiles.innerHTML = '';
    tilesState.innerHTML = '';
    runArea.innerHTML = '';
    if (!run) {
      tiles.appendChild(metricTile('circle-check', '本体准确率', '--', '尚未运行基准测试', 'ontology'));
      tiles.appendChild(metricTile('robot', '传统准确率', '--', '尚未运行基准测试', 'traditional'));
      tiles.appendChild(metricTile('clock', '平均耗时', '--', '尚未运行基准测试', 'time'));
      const button = runButton('运行基准测试（forceMock）', false);
      button.addEventListener('click', () => {
        triggerRun();
      });
      runArea.appendChild(el('div', { class: 'run-hint' }, [
        el('span', { text: '尚无基准结果：点击运行后将调用 POST /api/benchmark/run?forceMock=true 并刷新本页数据。' })
      ]));
      runArea.appendChild(button);
      scenarioGrid.innerHTML = '';
      scenarioGrid.appendChild(el('div', { class: 'scenario-empty' }, [
        el('span', { class: 'state-icon', html: icon('chart', 20) }),
        el('span', { text: '暂无分类统计，请先运行基准测试' })
      ]));
      return;
    }
    const avgMs = (Number(run.ontologyElapsedMs) + Number(run.traditionalElapsedMs)) / 2;
    const subText = '共 ' + fmtNum(run.totalCount) + ' 题 · runId ' + fmtNum(run.runId)
      + (run.runMode ? ' · ' + run.runMode : '');
    tiles.appendChild(metricTile('circle-check', '本体准确率', run.ontologyAccuracy + '%',
      subText + ' · 正确 ' + fmtNum(run.ontologyCorrect) + ' 题', 'ontology'));
    tiles.appendChild(metricTile('robot', '传统准确率', run.traditionalAccuracy + '%',
      subText + ' · 正确 ' + fmtNum(run.traditionalCorrect) + ' 题', 'traditional'));
    tiles.appendChild(metricTile('clock', '两管线平均耗时', fmtMs(avgMs),
      '本体 ' + fmtMs(run.ontologyElapsedMs) + ' / 传统 ' + fmtMs(run.traditionalElapsedMs), 'time'));
    const rerun = runButton('重新运行基准测试', false);
    rerun.addEventListener('click', () => {
      triggerRun();
    });
    runArea.appendChild(rerun);

    const stats = asArray(run.categoryStats);
    scenarioGrid.innerHTML = '';
    if (!stats.length) {
      scenarioGrid.appendChild(el('div', { class: 'scenario-empty' }, [
        el('span', { class: 'state-icon', html: icon('chart', 20) }),
        el('span', { text: '本次运行未返回分类统计' })
      ]));
      return;
    }
    stats.forEach((stat) => {
      scenarioGrid.appendChild(scenarioCard(stat));
    });
  }

  function renderLoading() {
    tiles.innerHTML = '';
    tiles.appendChild(el('div', { class: 'tile tile-loading' }, [
      el('span', { class: 'spinner' }),
      el('span', { text: '正在获取最近一次基准结果...' })
    ]));
  }

  async function triggerRun() {
    if (busy) {
      return;
    }
    busy = true;
    runArea.innerHTML = '';
    runArea.appendChild(runButton('运行中...', true));
    try {
      const run = await runBenchmark(ctx.signal);
      renderRun(run);
      if (ctx && typeof ctx.toast === 'function') {
        ctx.toast('基准测试完成，结果已刷新', 'ok');
      }
    } catch (err) {
      if (err && err.aborted) {
        return;
      }
      renderRun(null);
      if (ctx && typeof ctx.toast === 'function') {
        ctx.toast(err.message || '基准测试运行失败', 'error');
      }
    } finally {
      busy = false;
    }
  }

  renderLoading();
  latestBenchmark(ctx.signal).then((data) => {
    const run = data && data.run === null ? null : data;
    renderRun(run && run.runId !== undefined ? run : null);
  }).catch((err) => {
    if (err && err.aborted) {
      return;
    }
    tiles.innerHTML = '';
    tiles.appendChild(el('div', { class: 'tile tile-error' }, [
      el('span', { class: 'state-icon', html: icon('octagon-x', 20) }),
      el('span', { text: '基准结果获取失败：' + (err.message || '请求失败') })
    ]));
    const retry = runButton('重试获取', false);
    retry.addEventListener('click', () => {
      renderLoading();
      latestBenchmark(ctx.signal).then((data) => {
        const run = data && data.run === null ? null : data;
        renderRun(run && run.runId !== undefined ? run : null);
      }).catch(() => {
      });
    });
    runArea.innerHTML = '';
    runArea.appendChild(retry);
  });

  return { destroy: () => {} };
}
