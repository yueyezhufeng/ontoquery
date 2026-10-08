// OntoQuery 应用入口：hash 路由 + 页面懒加载 + 全局 toast/modal · 作者：月夜烛峰
import { icon } from './icons.js';
import { el, STORAGE_KEY_LAST_COMPARE } from './util.js';
import { llmStatus, clearCache } from './api.js';

const ROUTES = {
  'compare': { title: '对比问答', load: () => import('./pages/compare.js') },
  'ontology-qa': { title: '本体问数', load: () => import('./pages/ontology-qa.js') },
  'traditional-qa': { title: '传统问数', load: () => import('./pages/traditional-qa.js') },
  'graph': { title: '本体图谱', load: () => import('./pages/graph.js') },
  'parser': { title: '语义解析过程', load: () => import('./pages/parser.js') },
  'ops': { title: '本体运维', load: () => import('./pages/ops.js') },
  'comparison': { title: '对比分析', load: () => import('./pages/comparison.js') }
};

const DEFAULT_ROUTE = 'compare';
const TOAST_DURATION_MS = 3200;

const view = document.getElementById('view');
const topbarTitle = document.getElementById('topbar-title');
const toastWrap = document.getElementById('toast-wrap');
const modalRoot = document.getElementById('modal-root');

let pageHandle = null;
let pageAborter = null;
let routeGeneration = 0;

// ---------- 全局 toast ----------

const TOAST_ICON = { ok: 'circle-check', warn: 'triangle-alert', error: 'octagon-x', info: 'info' };

/**
 * 全局 toast 提示（替代 alert）。
 * @param {string} message 提示文案
 * @param {string} type ok | warn | error | info
 */
function toast(message, type) {
  const kind = TOAST_ICON[type] ? type : 'info';
  const node = el('div', { class: 'toast ' + kind }, [
    el('span', { class: 'toast-ic', html: icon(TOAST_ICON[kind], 15) }),
    el('span', { class: 'toast-text', text: message || '' })
  ]);
  toastWrap.appendChild(node);
  requestAnimationFrame(() => {
    node.classList.add('show');
  });
  const dismiss = () => {
    node.classList.remove('show');
    setTimeout(() => {
      node.remove();
    }, 220);
  };
  node.addEventListener('click', dismiss);
  setTimeout(dismiss, TOAST_DURATION_MS);
}

// ---------- 全局 modal（替代 confirm） ----------

let activeModal = null;

/**
 * 全局模态框。
 * @param {Object} options title/body(节点)/actions:[{label, primary, onClick}]
 * @returns {Function} 关闭函数
 */
function openModal(options) {
  closeModal();
  const opts = options || {};
  const mask = el('div', { class: 'modal-mask' });
  const actions = el('div', { class: 'modal-foot' });
  asList(opts.actions).forEach((action) => {
    actions.appendChild(el('button', {
      class: 'btn' + (action.primary ? ' primary' : ''),
      type: 'button',
      text: action.label || '确定',
      on: {
        click: () => {
          if (typeof action.onClick === 'function') {
            action.onClick();
          }
          closeModal();
        }
      }
    }));
  });
  const card = el('div', { class: 'modal' }, [
    el('div', { class: 'modal-head' }, [
      el('span', { class: 'modal-title', text: opts.title || '' }),
      el('button', {
        class: 'icon-btn modal-close',
        html: icon('close', 14),
        on: { click: () => closeModal() }
      })
    ]),
    el('div', { class: 'modal-body' }, [opts.body || el('span')]),
    actions
  ]);
  mask.appendChild(card);
  mask.addEventListener('click', (event) => {
    if (event.target === mask) {
      closeModal();
    }
  });
  modalRoot.innerHTML = '';
  modalRoot.appendChild(mask);
  requestAnimationFrame(() => {
    mask.classList.add('show');
  });
  activeModal = mask;
  return closeModal;
}

function closeModal() {
  if (activeModal) {
    activeModal.classList.remove('show');
    const node = activeModal;
    setTimeout(() => {
      node.remove();
    }, 180);
    activeModal = null;
  }
}

function onModalKeydown(event) {
  if (event.key === 'Escape') {
    closeModal();
  }
}

function asList(value) {
  return Array.isArray(value) ? value : [];
}

// ---------- 路由 ----------

function currentRouteName() {
  const hash = location.hash.replace(/^#\/?/, '').split('?')[0];
  return ROUTES[hash] ? hash : DEFAULT_ROUTE;
}

/**
 * 页面间跳转（hash 路由，不刷新）。
 * @param {string} name 路由名
 */
function navigate(name) {
  if (ROUTES[name]) {
    location.hash = '#/' + name;
  }
}

async function showRoute() {
  const name = currentRouteName();
  const route = ROUTES[name];
  topbarTitle.textContent = route.title;
  document.title = route.title + ' · OntoQuery';
  document.querySelectorAll('.sb-nav-item').forEach((item) => {
    item.classList.toggle('active', item.dataset.route === name);
  });

  const generation = ++routeGeneration;
  if (pageAborter) {
    pageAborter.abort();
  }
  if (pageHandle && typeof pageHandle.destroy === 'function') {
    pageHandle.destroy();
  }
  pageHandle = null;
  pageAborter = new AbortController();
  view.innerHTML = '';

  const loading = el('div', { class: 'page-loading' }, [
    el('span', { class: 'spinner' }),
    el('span', { text: '正在加载页面...' })
  ]);
  view.appendChild(loading);
  try {
    const module = await route.load();
    if (generation !== routeGeneration) {
      return;
    }
    view.innerHTML = '';
    pageHandle = module.mount(view, {
      signal: pageAborter.signal,
      navigate: navigate,
      toast: toast,
      modal: openModal,
      routeName: name
    });
  } catch (err) {
    if (generation !== routeGeneration) {
      return;
    }
    view.innerHTML = '';
    view.appendChild(el('div', { class: 'page-root' }, [
      el('div', { class: 'state-block' }, [
        el('div', { class: 'state-icon', html: icon('octagon-x', 22) }),
        el('div', { class: 'state-title', text: '页面加载失败' }),
        el('div', { class: 'state-desc', text: (err && err.message) || '模块加载异常' }),
        el('button', {
          class: 'btn btn-sm',
          type: 'button',
          on: { click: () => showRoute() }
        }, [el('span', { text: '重试' })])
      ])
    ]));
  }
}

window.addEventListener('hashchange', showRoute);
window.addEventListener('keydown', onModalKeydown);

// ---------- 侧边栏与顶栏 ----------

function decorateShell() {
  document.querySelectorAll('.sb-nav-item').forEach((item) => {
    const holder = item.querySelector('.sb-nav-ic');
    if (holder) {
      holder.innerHTML = icon(holder.dataset.icon || 'circle-check', 17);
    }
  });

  const exportBtn = document.getElementById('btn-export');
  exportBtn.appendChild(el('span', { class: 'btn-ic', html: icon('download', 13) }));
  exportBtn.appendChild(el('span', { text: '导出 SQL' }));
  exportBtn.addEventListener('click', exportLastSql);

  const newBtn = document.getElementById('btn-new');
  newBtn.appendChild(el('span', { class: 'btn-ic', html: icon('plus', 13) }));
  newBtn.appendChild(el('span', { text: '新建查询' }));
  newBtn.addEventListener('click', () => {
    navigate(DEFAULT_ROUTE);
    setTimeout(() => {
      const input = view.querySelector('.qa-input');
      if (input) {
        input.focus();
      }
    }, 80);
  });
}

function exportLastSql() {
  let payload = null;
  try {
    payload = JSON.parse(localStorage.getItem(STORAGE_KEY_LAST_COMPARE) || 'null');
  } catch (err) {
    payload = null;
  }
  if (!payload || (!payload.ontology && !payload.traditional)) {
    toast('暂无可导出的查询结果，请先在对比问答中提问', 'warn');
    return;
  }
  const lines = [];
  lines.push('-- OntoQuery SQL 导出');
  lines.push('-- 问题：' + (payload.question || ''));
  if (payload.ontology && payload.ontology.sql) {
    lines.push('');
    lines.push('-- ===== 本体增强管线 =====');
    lines.push(payload.ontology.sql);
  }
  if (payload.traditional && payload.traditional.sql) {
    lines.push('');
    lines.push('-- ===== 传统 NL2SQL 管线 =====');
    lines.push(payload.traditional.sql);
  }
  const blob = new Blob([lines.join('\n') + '\n'], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = el('a', { href: url, download: 'ontoquery-export.sql' });
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => {
    URL.revokeObjectURL(url);
  }, 1000);
  toast('已导出 ontoquery-export.sql', 'ok');
}

function renderLlmChip(status, failed) {
  const chip = document.getElementById('llm-chip');
  chip.innerHTML = '';
  if (failed) {
    chip.classList.add('down');
    chip.title = '后端服务未启动：请运行 mvn spring-boot:run（端口 8080）后刷新';
    chip.appendChild(el('span', { class: 'llm-dot error' }));
    chip.appendChild(el('span', { text: '服务未启动' }));
    return;
  }
  chip.classList.remove('down');
  chip.title = 'LLM：' + (status.model || '--') + ' · lastMode=' + (status.lastMode || '--');
  chip.appendChild(el('span', { class: 'llm-dot ' + (status.enabled ? 'ok' : 'warn') }));
  chip.appendChild(el('span', { class: 'llm-model', text: status.model || '未配置' }));
  if (Number.isFinite(Number(status.cacheEntries)) && Number(status.cacheEntries) > 0) {
    chip.appendChild(el('span', { class: 'llm-cache', text: '缓存 ' + status.cacheEntries }));
    const clearBtn = el('button', {
      class: 'llm-chip-btn',
      title: '清空 LLM 缓存（POST /api/meta/llm-cache/clear）',
      html: icon('close', 11),
      on: {
        click: async () => {
          try {
            const result = await clearCache();
            toast('已清理 ' + (result && result.cleared !== undefined ? result.cleared : 0) + ' 条缓存', 'ok');
            loadLlmStatus();
          } catch (err) {
            toast(err.message || '缓存清理失败', 'error');
          }
        }
      }
    });
    chip.appendChild(clearBtn);
  }
  const refreshBtn = el('button', {
    class: 'llm-chip-btn',
    title: '刷新 LLM 状态',
    html: icon('refresh', 11),
    on: { click: () => loadLlmStatus() }
  });
  chip.appendChild(refreshBtn);
}

function loadLlmStatus() {
  llmStatus().then((status) => {
    renderLlmChip(status || {}, false);
  }).catch((err) => {
    if (err && err.aborted) {
      return;
    }
    renderLlmChip({}, true);
  });
}

// ---------- 启动 ----------

function boot() {
  decorateShell();
  if (!location.hash) {
    history.replaceState(null, '', '#/' + DEFAULT_ROUTE);
  }
  loadLlmStatus();
  showRoute();
}

// 暴露给组件层（sql-block 复制反馈等）使用的全局入口
window.OntoApp = { toast: toast, navigate: navigate, modal: openModal };

boot();
