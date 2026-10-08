// OntoQuery SQL 代码块组件（高亮 + 复制）· 作者：月夜烛峰
import { el, sqlHighlight } from '../util.js';
import { icon } from '../icons.js';

function copyText(text) {
  const done = () => {
    if (window.OntoApp && typeof window.OntoApp.toast === 'function') {
      window.OntoApp.toast('SQL 已复制到剪贴板', 'ok');
    }
  };
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done, () => fallbackCopy(text, done));
  } else {
    fallbackCopy(text, done);
  }
}

function fallbackCopy(text, done) {
  const holder = el('textarea', { class: 'copy-holder', value: text });
  document.body.appendChild(holder);
  holder.select();
  try {
    document.execCommand('copy');
    done();
  } catch (err) {
    if (window.OntoApp && typeof window.OntoApp.toast === 'function') {
      window.OntoApp.toast('复制失败，请手动选择复制', 'warn');
    }
  }
  document.body.removeChild(holder);
}

/**
 * 渲染 SQL 代码块：语法高亮 + 复制按钮。
 * @param {string} sql SQL 文本
 * @param {Object} options variant: 'cyan'|'purple'（默认按引擎配色）
 * @returns {HTMLElement} 代码块元素
 */
export function renderSqlBlock(sql, options) {
  const opts = options || {};
  const variant = opts.variant === 'purple' ? 'purple' : 'cyan';
  const block = el('div', { class: 'sql-block ' + variant });
  const head = el('div', { class: 'sql-block-head' }, [
    el('span', { class: 'sql-block-tag', text: 'SQL' }),
    el('button', {
      class: 'icon-btn sql-copy-btn',
      title: '复制 SQL',
      html: icon('copy', 14),
      on: {
        click: () => {
          copyText(sql || '');
        }
      }
    })
  ]);
  const pre = el('pre', { class: 'sql-pre' });
  pre.innerHTML = sqlHighlight(sql);
  block.appendChild(head);
  block.appendChild(pre);
  return block;
}
