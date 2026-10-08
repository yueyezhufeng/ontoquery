// OntoQuery 风险面板组件（折叠）· 作者：月夜烛峰
// risks 按 level 着色；evidence 内联 SQL + 实测结果
import { el, asArray, sqlHighlight } from '../util.js';
import { icon } from '../icons.js';

const LEVEL_CLASS = { high: 'high', medium: 'medium', low: 'low', info: 'info' };

function levelClass(level) {
  return LEVEL_CLASS[level] || 'info';
}

function levelLabel(level) {
  const labels = { high: '高', medium: '中', low: '低', info: '提示' };
  return labels[level] || '提示';
}

function renderEvidence(item) {
  const wrap = el('div', { class: 'risk-evidence' });
  if (item.sql) {
    const pre = el('pre', { class: 'risk-evidence-sql' });
    pre.innerHTML = sqlHighlight(item.sql);
    wrap.appendChild(pre);
  }
  if (item.result) {
    wrap.appendChild(el('div', { class: 'risk-evidence-result mono', text: item.result }));
  }
  return wrap;
}

function renderRisk(risk) {
  const node = el('div', { class: 'risk-item ' + levelClass(risk.level) }, [
    el('div', { class: 'risk-title-row' }, [
      el('span', { class: 'risk-level-tag', text: levelLabel(risk.level) }),
      el('span', { class: 'risk-title', text: risk.title || risk.code || '未命名风险' }),
      risk.code ? el('span', { class: 'risk-code mono', text: risk.code }) : null
    ]),
    risk.detail ? el('div', { class: 'risk-detail', text: risk.detail }) : null
  ]);
  const evidences = asArray(risk.evidence);
  if (evidences.length) {
    const box = el('div', { class: 'risk-evidences' });
    evidences.forEach((item) => {
      box.appendChild(renderEvidence(item));
    });
    node.appendChild(box);
  }
  return node;
}

/**
 * 渲染风险折叠面板；无风险时返回 null。
 * @param {Array} risks PipelineResult.risks
 * @param {Object} options defaultOpen: 默认是否展开
 * @returns {HTMLElement|null} 面板元素
 */
export function renderRiskPanel(risks, options) {
  const list = asArray(risks);
  if (!list.length) {
    return null;
  }
  const opts = options || {};
  const open = Boolean(opts.defaultOpen);
  const highCount = list.filter((risk) => risk.level === 'high').length;
  const headText = highCount > 0
    ? '风险检测 ' + list.length + ' 条（' + highCount + ' 条高危）'
    : '风险检测 ' + list.length + ' 条';
  const body = el('div', { class: 'risk-body' + (open ? ' open' : '') });
  list.forEach((risk) => {
    body.appendChild(renderRisk(risk));
  });
  const chevron = el('span', { class: 'risk-chevron' + (open ? ' open' : ''), html: icon('chevron-down', 14) });
  const head = el('div', { class: 'risk-head' + (open ? ' open' : '') }, [
    el('span', { class: 'risk-head-ic', html: icon('triangle-alert', 15) }),
    el('span', { class: 'risk-head-text', text: headText }),
    el('span', { class: 'risk-count', text: String(list.length) }),
    chevron
  ]);
  const panel = el('div', { class: 'risk-panel' + (open ? ' open' : '') }, [head, body]);
  head.addEventListener('click', () => {
    const willOpen = !panel.classList.contains('open');
    panel.classList.toggle('open', willOpen);
    body.classList.toggle('open', willOpen);
    head.classList.toggle('open', willOpen);
    chevron.classList.toggle('open', willOpen);
  });
  return panel;
}
