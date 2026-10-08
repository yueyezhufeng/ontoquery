// OntoQuery 前端工具函数 · 作者：月夜烛峰
// 所有动态文本必须经 escapeHtml（或走 el() 的 text/textContent）后再进入 DOM。

/** 跨页面共享的 localStorage 键：最近一次对比问答结果（parser 页复用） */
export const STORAGE_KEY_LAST_COMPARE = 'ontoquery_last_compare';

/**
 * HTML 转义：任何来自用户输入或后端响应的文本，输出到 HTML 前必须调用。
 * @param {string} value 原始文本
 * @returns {string} 转义后的安全文本
 */
export function escapeHtml(value) {
  if (value === null || value === undefined) {
    return '';
  }
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

const SQL_KEYWORDS = new Set([
  'SELECT', 'FROM', 'WHERE', 'AND', 'OR', 'NOT', 'NULL', 'IS', 'IN', 'LIKE', 'EXISTS', 'BETWEEN',
  'AS', 'ON', 'JOIN', 'LEFT', 'RIGHT', 'INNER', 'OUTER', 'CROSS', 'FULL', 'DISTINCT', 'ALL',
  'GROUP', 'BY', 'ORDER', 'LIMIT', 'OFFSET', 'HAVING', 'UNION', 'INTERVAL', 'DAY', 'MONTH',
  'YEAR', 'WEEK', 'CASE', 'WHEN', 'THEN', 'ELSE', 'END', 'ASC', 'DESC', 'TRUE', 'FALSE',
  'IF', 'VALUES', 'WITH'
]);

const SQL_FUNCTIONS = new Set([
  'COUNT', 'SUM', 'AVG', 'MAX', 'MIN', 'ROUND', 'CONCAT', 'CAST', 'COALESCE', 'IFNULL',
  'DATE_SUB', 'DATE_ADD', 'CURDATE', 'NOW', 'DATE_FORMAT', 'DATEDIFF', 'TIMESTAMPDIFF',
  'YEAR', 'MONTH', 'DAY', 'WEEK'
]);

const SQL_TOKEN_RE = /(--[^\n]*)|('(?:[^']|'')*')|(\b\d+(?:\.\d+)?\b)|([A-Za-z_][A-Za-z0-9_]*)|([\s\S])/g;

function wrapSpan(cls, text) {
  return '<span class="' + cls + '">' + escapeHtml(text) + '</span>';
}

/**
 * SQL 语法高亮：关键字紫、函数琥珀、字符串绿、数字橙、注释灰。
 * 内部先转义再包裹，输出可直接作为 innerHTML。
 * @param {string} sql SQL 文本
 * @returns {string} 高亮后的 HTML
 */
export function sqlHighlight(sql) {
  if (sql === null || sql === undefined) {
    return '';
  }
  const source = String(sql);
  let out = '';
  let match = SQL_TOKEN_RE.exec(source);
  while (match !== null) {
    if (match[1]) {
      out += wrapSpan('sql-comment', match[1]);
    } else if (match[2]) {
      out += wrapSpan('sql-str', match[2]);
    } else if (match[3]) {
      out += wrapSpan('sql-num', match[3]);
    } else if (match[4]) {
      const word = match[4];
      const upper = word.toUpperCase();
      const rest = source.slice(SQL_TOKEN_RE.lastIndex);
      if (SQL_KEYWORDS.has(upper)) {
        out += wrapSpan('sql-kw', word);
      } else if (SQL_FUNCTIONS.has(upper) || /^\s*\(/.test(rest)) {
        out += wrapSpan('sql-fn', word);
      } else {
        out += escapeHtml(word);
      }
    } else {
      out += escapeHtml(match[5]);
    }
    match = SQL_TOKEN_RE.exec(source);
  }
  return out;
}

/**
 * 毫秒耗时展示：小于 1 秒以 ms 计，否则保留两位小数秒。
 * @param {number} ms 毫秒数
 * @returns {string} 展示文本
 */
export function fmtMs(ms) {
  if (ms === null || ms === undefined || !Number.isFinite(Number(ms))) {
    return '--';
  }
  const value = Number(ms);
  return value < 1000 ? value + 'ms' : (value / 1000).toFixed(2) + 's';
}

/**
 * 数字千分位展示；非数值原样返回，空值显示占位符。
 * @param {number|string} value 数值
 * @returns {string} 展示文本
 */
export function fmtNum(value) {
  if (value === null || value === undefined || value === '') {
    return '--';
  }
  const num = Number(value);
  if (!Number.isFinite(num)) {
    return String(value);
  }
  return num.toLocaleString('zh-CN');
}

/**
 * 快捷 DOM 构造。
 * attrs 支持：class / text / html（仅限内部受信内容）/ title / disabled / value /
 * on:{事件名: 处理函数} / 其它setAttribute 属性；children 为节点或字符串数组。
 * @param {string} tag 标签名
 * @param {Object} attrs 属性
 * @param {Array} children 子节点
 * @returns {HTMLElement} 构造好的元素
 */
export function el(tag, attrs, children) {
  const node = document.createElement(tag);
  const options = attrs || {};
  const reserved = ['class', 'text', 'html', 'title', 'on', 'disabled', 'value'];
  if (options.class) {
    node.className = options.class;
  }
  if (options.text !== null && options.text !== undefined) {
    node.textContent = options.text;
  }
  if (options.html !== null && options.html !== undefined) {
    node.innerHTML = options.html;
  }
  if (options.title !== null && options.title !== undefined) {
    node.title = options.title;
  }
  if (options.disabled) {
    node.disabled = true;
  }
  if (options.value !== null && options.value !== undefined) {
    node.value = options.value;
  }
  const events = options.on || {};
  Object.keys(events).forEach((name) => {
    if (typeof events[name] === 'function') {
      node.addEventListener(name, events[name]);
    }
  });
  Object.keys(options).forEach((key) => {
    if (reserved.indexOf(key) >= 0) {
      return;
    }
    node.setAttribute(key, options[key]);
  });
  const list = Array.isArray(children) ? children : (children ? [children] : []);
  list.forEach((child) => {
    if (child === null || child === undefined) {
      return;
    }
    node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
  });
  return node;
}

/** 数组安全包装：null/undefined 返回空数组 */
export function asArray(value) {
  return Array.isArray(value) ? value : [];
}
