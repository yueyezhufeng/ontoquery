// OntoQuery 查询结果表组件 · 作者：月夜烛峰
// 列宽自适应 + 行数徽标 + truncated 提示 + error/empty 态
import { el, fmtMs, asArray } from '../util.js';
import { icon } from '../icons.js';

function cellText(value) {
  if (value === null || value === undefined) {
    return 'NULL';
  }
  return String(value);
}

function renderError(message) {
  return el('div', { class: 'result-error' }, [
    el('span', { class: 'result-error-ic', html: icon('octagon-x', 15) }),
    el('div', { class: 'result-error-text' }, [
      el('div', { class: 'result-error-title', text: '查询执行失败' }),
      el('div', { class: 'result-error-msg', text: message })
    ])
  ]);
}

/**
 * 渲染查询结果区。
 * @param {Object} result 契约 PipelineResult.result（columns/rows/rowCount/truncated/elapsedMs/error）
 * @param {Object} options sqlStatus: 'ok'|'error'|'none' 用于无法生成时的空态区分
 * @returns {HTMLElement|null} 结果区块；无可渲染内容时返回 null
 */
export function renderResult(result, options) {
  const opts = options || {};
  const data = result || {};
  const wrap = el('div', { class: 'result-wrap' });

  if (data.error) {
    wrap.appendChild(el('div', { class: 'result-head' }, [
      el('span', { class: 'result-title', text: '查询结果' })
    ]));
    wrap.appendChild(renderError(data.error));
    return wrap;
  }

  if (opts.sqlStatus === 'none') {
    wrap.appendChild(el('div', { class: 'result-empty' }, [
      el('span', { class: 'state-icon', html: icon('circle-check', 18) }),
      el('div', null, [
        el('div', { class: 'result-empty-title', text: '未生成 SQL' }),
        el('div', { class: 'result-empty-desc', text: '该问题超出本体覆盖范围，管线判定无法生成查询' })
      ])
    ]));
    return wrap;
  }

  const columns = asArray(data.columns);
  const rows = asArray(data.rows);
  if (!columns.length && !rows.length) {
    return null;
  }

  const rowCount = Number.isFinite(Number(data.rowCount)) ? Number(data.rowCount) : rows.length;
  const metaParts = ['返回 ' + rowCount + ' 行'];
  if (Number.isFinite(Number(data.elapsedMs))) {
    metaParts.push('执行 ' + fmtMs(data.elapsedMs));
  }
  wrap.appendChild(el('div', { class: 'result-head' }, [
    el('span', { class: 'result-title', text: '查询结果' }),
    el('span', { class: 'result-badge', text: rowCount + ' 行' }),
    el('span', { class: 'result-meta', text: metaParts.join(' · ') })
  ]));

  const table = el('table', { class: 'result-table' });
  if (columns.length) {
    const headRow = el('tr', null, columns.map((col) => el('th', { text: col })));
    table.appendChild(el('thead', null, [headRow]));
  }
  const body = el('tbody');
  rows.forEach((row) => {
    const cells = asArray(row).map((value) => {
      const text = cellText(value);
      const td = el('td', { title: text });
      td.textContent = text;
      return td;
    });
    body.appendChild(el('tr', null, cells));
  });
  table.appendChild(body);
  wrap.appendChild(table);

  if (data.truncated) {
    wrap.appendChild(el('div', { class: 'truncated-note' }, [
      el('span', { class: 'truncated-ic', html: icon('triangle-alert', 13) }),
      el('span', { text: '结果已截断，仅展示部分行；行数以上方统计为准' })
    ]));
  }
  return wrap;
}
