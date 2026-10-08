// OntoQuery 解析步骤轨迹组件 · 作者：月夜烛峰
// 五步轨迹：圆点连线 + 每步 elapsedMs + status 着色 + 点击展开 items；
// TraceItem 按契约七种 type 渲染；另导出置信度卡（score 环形 + formula + factors）。
import { el, sqlHighlight, asArray } from '../util.js';
import { icon } from '../icons.js';

const STATUS_CLASS = { ok: 'ok', warn: 'warn', error: 'error' };

function levelClass(level) {
  return level === 'ok' || level === 'warn' || level === 'error' || level === 'info'
    ? 'lv-' + level
    : 'lv-info';
}

function renderKv(item) {
  const row = el('div', { class: 'ti ti-kv ' + levelClass(item.level) });
  row.appendChild(el('span', { class: 'ti-kv-label', text: item.label }));
  row.appendChild(el('span', { class: 'ti-from mono', text: item.from }));
  row.appendChild(el('span', { class: 'ti-arrow', text: '->' }));
  row.appendChild(el('span', { class: 'ti-to mono', text: item.to }));
  if (item.rule) {
    row.appendChild(el('span', { class: 'ti-rule', title: item.rule }, [
      el('span', { class: 'ti-rule-tag', text: '规则' }),
      el('span', { class: 'ti-rule-text', text: item.rule })
    ]));
  }
  return row;
}

function renderEntityChip(item) {
  return el('div', { class: 'ti ti-chips' }, [
    el('span', { class: 'ti-chip from', text: item.from }),
    el('span', { class: 'ti-arrow', text: '->' }),
    el('span', { class: 'ti-chip to', text: item.to })
  ]);
}

function renderRelationEdge(item) {
  return el('div', { class: 'ti ti-edge' }, [
    el('span', { class: 'ti-node', text: item.from }),
    el('span', { class: 'ti-edge-label', text: '-- ' + item.label + ' -->' }),
    el('span', { class: 'ti-node', text: item.to })
  ]);
}

function renderPathChain(item) {
  const row = el('div', { class: 'ti ti-chain' });
  asArray(item.values).forEach((value, index) => {
    if (index > 0) {
      row.appendChild(el('span', { class: 'ti-arrow', text: '->' }));
    }
    row.appendChild(el('span', { class: 'ti-chip path', text: value }));
  });
  return row;
}

function renderSqlPre(item) {
  const pre = el('pre', { class: 'ti ti-sql' });
  pre.innerHTML = sqlHighlight(item.text);
  return pre;
}

function renderKvTable(item) {
  const table = el('table', { class: 'ti-table' });
  const columns = asArray(item.columns);
  if (columns.length) {
    table.appendChild(el('thead', null, [
      el('tr', null, columns.map((col) => el('th', { text: col })))
    ]));
  }
  const body = el('tbody');
  asArray(item.rows).forEach((row) => {
    body.appendChild(el('tr', null, asArray(row).map((cell) => el('td', { text: cell }))));
  });
  table.appendChild(body);
  return el('div', { class: 'ti ti-table-wrap' }, [table]);
}

function renderTextItem(item) {
  return el('div', { class: 'ti ti-text ' + levelClass(item.level) }, [
    el('span', { class: 'ti-status-ic', html: icon(statusIcon(item.level), 13) }),
    el('span', { class: 'ti-text-body', text: item.text })
  ]);
}

function statusIcon(level) {
  if (level === 'ok') {
    return 'circle-check';
  }
  if (level === 'warn') {
    return 'triangle-alert';
  }
  if (level === 'error') {
    return 'octagon-x';
  }
  return 'info';
}

const ITEM_RENDERERS = {
  'kv': renderKv,
  'entity-chip': renderEntityChip,
  'relation-edge': renderRelationEdge,
  'path-chain': renderPathChain,
  'sql-pre': renderSqlPre,
  'kv-table': renderKvTable,
  'text': renderTextItem
};

/**
 * 渲染单个 TraceItem（按契约 type 分发）。
 * @param {Object} item TraceItem
 * @returns {HTMLElement} 渲染节点
 */
export function renderTraceItem(item) {
  const renderer = item && ITEM_RENDERERS[item.type];
  if (!renderer) {
    return el('div', { class: 'ti ti-text lv-info', text: item && item.text ? item.text : '' });
  }
  return renderer(item);
}

function stepStatusClass(status) {
  return STATUS_CLASS[status] || 'ok';
}

/**
 * 渲染五步解析轨迹。
 * @param {Array} steps PipelineResult.steps
 * @param {Object} options variant: 'inline'（聊天气泡内）|'cards'（解析过程页大卡）；
 *                        defaultOpen: 是否默认展开 items
 * @returns {HTMLElement} 轨迹容器
 */
export function renderStepper(steps, options) {
  const opts = options || {};
  const container = el('div', { class: 'stepper ' + (opts.variant === 'cards' ? 'cards' : 'inline') });
  asArray(steps).forEach((step, index) => {
    const open = Boolean(opts.defaultOpen) && asArray(step.items).length > 0;
    const items = el('div', { class: 'step-items' + (open ? ' open' : '') });
    asArray(step.items).forEach((item) => {
      items.appendChild(renderTraceItem(item));
    });
    const chevron = el('span', {
      class: 'step-chevron' + (open ? ' open' : ''),
      html: icon('chevron-down', 14)
    });
    const head = el('div', { class: 'step-head' }, [
      el('span', { class: 'step-num ' + stepStatusClass(step.status), text: String(index + 1) }),
      el('span', { class: 'step-title', text: step.title || step.key || ('步骤 ' + (index + 1)) }),
      el('span', { class: 'step-elapsed mono', text: fmtStepMs(step.elapsedMs) }),
      chevron
    ]);
    const body = el('div', { class: 'step-body' }, [
      el('div', { class: 'step-summary', text: step.summary || '' }),
      items
    ]);
    const node = el('div', { class: 'step ' + stepStatusClass(step.status) + (open ? ' open' : '') }, [
      el('span', { class: 'step-rail' }),
      head,
      body
    ]);
    head.addEventListener('click', () => {
      const willOpen = !node.classList.contains('open');
      node.classList.toggle('open', willOpen);
      items.classList.toggle('open', willOpen);
      chevron.classList.toggle('open', willOpen);
    });
    container.appendChild(node);
  });
  return container;
}

function fmtStepMs(ms) {
  if (ms === null || ms === undefined || !Number.isFinite(Number(ms))) {
    return '--';
  }
  return Number(ms) < 1000 ? Number(ms) + 'ms' : (Number(ms) / 1000).toFixed(2) + 's';
}

/**
 * 渲染置信度卡：score 环形进度 + 计算公式 + 因子明细。
 * @param {Object} confidence PipelineResult.confidence
 * @returns {HTMLElement|null} 卡片；无数据返回 null
 */
export function renderConfidenceCard(confidence) {
  if (!confidence || !Number.isFinite(Number(confidence.score))) {
    return null;
  }
  const score = Math.max(0, Math.min(100, Math.round(Number(confidence.score))));
  const radius = 26;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference * (1 - score / 100);
  const ring = el('div', { class: 'conf-card' });
  const svg = [
    '<svg viewBox="0 0 64 64" width="64" height="64" class="conf-ring">',
    '<circle cx="32" cy="32" r="' + radius + '" class="conf-ring-bg"/>',
    '<circle cx="32" cy="32" r="' + radius + '" class="conf-ring-fg" stroke-dasharray="'
      + circumference.toFixed(1) + '" stroke-dashoffset="' + offset.toFixed(1) + '"/>',
    '<text x="32" y="37" text-anchor="middle" class="conf-ring-text">' + score + '</text>',
    '</svg>'
  ].join('');
  const right = el('div', { class: 'conf-right' }, [
    el('div', { class: 'conf-score-label', text: '置信度 ' + score + ' / 100' })
  ]);
  if (confidence.formula) {
    right.appendChild(el('div', { class: 'conf-formula mono', text: confidence.formula }));
  }
  const factors = asArray(confidence.factors);
  if (factors.length) {
    const list = el('div', { class: 'conf-factors' });
    factors.forEach((factor) => {
      const delta = Number(factor.delta);
      const deltaClass = delta < 0 ? 'neg' : delta > 0 ? 'pos' : 'zero';
      const deltaText = delta > 0 ? '+' + delta : String(delta);
      list.appendChild(el('div', { class: 'conf-factor' }, [
        el('span', { class: 'conf-factor-label', text: factor.label }),
        el('span', { class: 'conf-delta ' + deltaClass, text: deltaText })
      ]));
    });
    right.appendChild(list);
  }
  ring.appendChild(el('div', { class: 'conf-left', html: svg }));
  ring.appendChild(right);
  return ring;
}
