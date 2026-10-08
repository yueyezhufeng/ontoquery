// OntoQuery 语义解析过程页 · 作者：月夜烛峰
// 读取 localStorage 中最近一次对比问答的两侧 steps，各渲染五步解析卡；
// 无缓存时自动用核心问题调一次 /api/query/compare。
import { el, asArray, fmtMs, STORAGE_KEY_LAST_COMPARE } from '../util.js';
import { icon } from '../icons.js';
import { queryCompare, CORE_QUESTION } from '../api.js';
import { renderStepper } from '../components/stepper.js';

function panelHeader(badgeClass, badgeIcon, badgeText, title, elapsedMs) {
  return el('div', { class: 'qa-panel-header' }, [
    el('span', { class: 'qa-panel-badge ' + badgeClass, html: icon(badgeIcon, 13) }, [
      el('span', { text: badgeText })
    ]),
    el('span', { class: 'qa-panel-title', text: title }),
    el('span', { class: 'panel-elapsed mono', text: '总耗时 ' + fmtMs(elapsedMs) })
  ]);
}

function questionBanner(question) {
  return el('div', { class: 'question-banner' }, [
    el('span', { class: 'question-banner-label', text: '问题' }),
    el('span', { class: 'question-banner-text', text: question })
  ]);
}

function buildPanel(pipeline) {
  const isOntology = pipeline.engine === 'ontology';
  const body = el('div', { class: 'parser-body' });
  body.appendChild(questionBanner(pipeline.question || CORE_QUESTION));
  body.appendChild(renderStepper(pipeline.steps, { variant: 'cards', defaultOpen: true }));
  const panel = el('section', { class: 'panel parser-panel' }, [
    panelHeader(
      isOntology ? 'ontology' : 'traditional',
      isOntology ? 'brain' : 'robot',
      isOntology ? '本体增强解析' : '传统 NL2SQL 解析',
      isOntology ? '五步语义解析流程' : '端到端生成流程',
      pipeline.totalElapsedMs
    ),
    body
  ]);
  return panel;
}

function loadingPanel(text) {
  const body = el('div', { class: 'parser-body' }, [
    el('div', { class: 'mini-loading' }, [el('span', { class: 'spinner' }), el('span', { text: text })])
  ]);
  return el('section', { class: 'panel parser-panel' }, [
    el('div', { class: 'qa-panel-header' }, [
      el('span', { class: 'spinner' }),
      el('span', { class: 'qa-panel-title', text: text })
    ]),
    body
  ]);
}

function errorPanel(message, ctx) {
  const body = el('div', { class: 'parser-body' });
  body.appendChild(el('div', { class: 'state-block' }, [
    el('div', { class: 'state-icon', html: icon('octagon-x', 22) }),
    el('div', { class: 'state-title', text: '解析数据获取失败' }),
    el('div', { class: 'state-desc', text: message }),
    el('button', {
      class: 'btn btn-sm',
      type: 'button',
      on: {
        click: () => {
          if (ctx && typeof ctx.navigate === 'function') {
            ctx.navigate('compare');
          }
        }
      }
    }, [el('span', { text: '去对比问答页提问' })])
  ]));
  return el('section', { class: 'panel parser-panel' }, [body]);
}

function readCache() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY_LAST_COMPARE);
    if (!raw) {
      return null;
    }
    const data = JSON.parse(raw);
    if (data && data.ontology && asArray(data.ontology.steps).length
      && data.traditional && asArray(data.traditional.steps).length) {
      return data;
    }
  } catch (err) {
    // 缓存损坏时视为无缓存
  }
  return null;
}

export function mount(container, ctx) {
  const layout = el('div', { class: 'parser-layout' });
  const page = el('div', { class: 'page-root parser-page' }, [layout]);
  container.appendChild(page);

  function render(data) {
    layout.innerHTML = '';
    layout.appendChild(buildPanel(data.ontology));
    layout.appendChild(buildPanel(data.traditional));
  }

  function renderLoading() {
    layout.innerHTML = '';
    layout.appendChild(loadingPanel('正在获取本体侧解析轨迹...'));
    layout.appendChild(loadingPanel('正在获取传统侧解析轨迹...'));
  }

  const cached = readCache();
  if (cached) {
    render(cached);
    if (ctx && typeof ctx.toast === 'function') {
      ctx.toast('已载入最近一次对比问答的解析轨迹', 'info');
    }
    return { destroy: () => {} };
  }

  renderLoading();
  queryCompare(CORE_QUESTION, ctx.signal).then((data) => {
    try {
      localStorage.setItem(STORAGE_KEY_LAST_COMPARE, JSON.stringify({
        question: data.question || CORE_QUESTION,
        ontology: data.ontology || null,
        traditional: data.traditional || null,
        savedAt: Date.now()
      }));
    } catch (err) {
      // 存储不可用不阻断渲染
    }
    render(data);
  }).catch((err) => {
    if (err && err.aborted) {
      return;
    }
    layout.innerHTML = '';
    layout.appendChild(errorPanel(err.message || '请求失败', ctx));
    layout.appendChild(errorPanel(err.message || '请求失败', ctx));
  });

  return { destroy: () => {} };
}
