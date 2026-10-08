// OntoQuery 聊天组件：消息气泡 + 输入框 + 建议 chips + 加载态 · 作者：月夜烛峰
// 另提供 renderPipelineContent：按 统一顺序渲染管线应答体。
import { el, asArray, fmtMs } from '../util.js';
import { icon } from '../icons.js';
import { renderModeBadge } from './mode-badge.js';
import { renderStepper, renderConfidenceCard } from './stepper.js';
import { renderSqlBlock } from './sql-block.js';
import { renderResult } from './result-table.js';
import { renderRiskPanel } from './risk-panel.js';

/**
 * 创建消息区（不含输入；输入区由 createInputBar 单独创建，便于双栏共享输入）。
 * @param {Object} options avatar: 头像图标名；avatarClass: 头像配色类；
 *                         welcome: 初始欢迎文案（无数字文案）
 * @returns {Object} {root, addUser(text), addAI(loadingText), scroll()}
 */
export function createMessages(options) {
  const opts = options || {};
  const root = el('div', { class: 'qa-messages' });

  function scroll() {
    requestAnimationFrame(() => {
      root.scrollTop = root.scrollHeight;
    });
  }

  function addUser(text) {
    const bubble = el('div', { class: 'msg-bubble' });
    bubble.textContent = text;
    const node = el('div', { class: 'msg user' }, [
      el('div', { class: 'msg-avatar', html: icon('user', 14) }),
      bubble
    ]);
    root.appendChild(node);
    scroll();
  }

  function addAI(loadingText) {
    const bubble = el('div', { class: 'msg-bubble' });
    const loading = el('div', { class: 'chat-loading' }, [
      el('span', { class: 'spinner' }),
      el('span', { class: 'chat-loading-text', text: loadingText || '正在解析...' })
    ]);
    bubble.appendChild(loading);
    const node = el('div', { class: 'msg ai' }, [
      el('div', { class: 'msg-avatar ' + (opts.avatarClass || ''), html: icon(opts.avatar || 'brain', 15) }),
      el('div', { class: 'msg-content' }, [bubble])
    ]);
    root.appendChild(node);
    scroll();
    return {
      bubble: bubble,
      done: (contentNode) => {
        bubble.innerHTML = '';
        if (contentNode) {
          bubble.appendChild(contentNode);
        }
        scroll();
      },
      fail: (errorNode) => {
        bubble.innerHTML = '';
        if (errorNode) {
          bubble.appendChild(errorNode);
        }
        scroll();
      }
    };
  }

  if (opts.welcome) {
    const holder = addAI();
    holder.done(el('div', { class: 'msg-plain', text: opts.welcome }));
  }

  return { root: root, addUser: addUser, addAI: addAI, scroll: scroll };
}

/**
 * 构造错误气泡内容（octagon-x + 错误码 + 提示）。
 * @param {Error} err 携带 code/message 的错误对象
 * @returns {HTMLElement} 错误内容节点
 */
export function renderChatError(err) {
  const code = err && err.code ? err.code : 'B0000';
  const message = err && err.message ? err.message : '请求失败';
  const node = el('div', { class: 'chat-error' }, [
    el('div', { class: 'chat-error-main' }, [
      el('span', { class: 'chat-error-ic', html: icon('octagon-x', 16) }),
      el('div', null, [
        el('div', { class: 'chat-error-title' }, [
          el('span', { text: '查询失败' }),
          el('span', { class: 'chat-error-code mono', text: code })
        ]),
        el('div', { class: 'chat-error-msg', text: message })
      ])
    ])
  ]);
  if (code === 'B0001') {
    node.appendChild(el('div', {
      class: 'chat-error-hint',
      text: '请先启动后端服务：mvn spring-boot:run（端口 8080），刷新本页后重试'
    }));
  }
  return node;
}

/**
 * 创建输入区：建议 chips + 输入框 + 提交按钮。
 * @param {Object} options placeholder/suggestions/onSubmit(text)/submitLabel
 * @returns {Object} {root, setBusy(busy), focus()}
 */
export function createInputBar(options) {
  const opts = options || {};
  let busy = false;

  const chips = el('div', { class: 'qa-suggestions' });
  const input = el('input', {
    class: 'qa-input',
    placeholder: opts.placeholder || '用自然语言提问...',
    on: {
      keydown: (event) => {
        if (event.key === 'Enter' && !event.isComposing) {
          submit();
        }
      }
    }
  });
  const button = el('button', { class: 'btn primary', type: 'button' }, [
    el('span', { class: 'btn-ic', html: icon('send', 14) }),
    el('span', { class: 'btn-label', text: opts.submitLabel || '查询' })
  ]);
  button.addEventListener('click', submit);

  function submit() {
    if (busy) {
      return;
    }
    const text = input.value.trim();
    if (!text) {
      return;
    }
    input.value = '';
    if (typeof opts.onSubmit === 'function') {
      opts.onSubmit(text);
    }
  }

  function renderChips(list) {
    chips.innerHTML = '';
    asList(list).forEach((text) => {
      chips.appendChild(el('button', {
        class: 'qa-suggestion',
        type: 'button',
        title: text,
        text: text,
        on: {
          click: () => {
            if (busy) {
              return;
            }
            if (typeof opts.onSubmit === 'function') {
              opts.onSubmit(text);
            }
          }
        }
      }));
    });
  }

  function setBusy(value) {
    busy = Boolean(value);
    input.disabled = busy;
    button.disabled = busy;
    button.classList.toggle('loading', busy);
    button.querySelector('.btn-label').textContent = busy ? '查询中...' : (opts.submitLabel || '查询');
  }

  renderChips(opts.suggestions);

  const root = el('div', { class: 'qa-input-area' }, [
    chips,
    el('div', { class: 'qa-input-row' }, [input, button])
  ]);

  return {
    root: root,
    setBusy: setBusy,
    focus: () => {
      input.focus();
    }
  };
}

function asList(value) {
  return Array.isArray(value) ? value : [];
}

/**
 * 渲染管线应答体：模式徽标 -> 步骤轨迹 -> SQL -> 结果 -> 置信度 -> 风险面板。
 * 供对比问答 / 本体问数 / 传统问数三页复用，字段全部来自契约 PipelineResult。
 * @param {Object} pipeline PipelineResult
 * @param {Object} options sqlVariant: 'purple'|'cyan'；showRisks: 是否渲染风险面板
 * @returns {HTMLElement} 应答体节点
 */
export function renderPipelineContent(pipeline, options) {
  const opts = options || {};
  const data = pipeline || {};
  const wrap = el('div', { class: 'pipeline-answer' });

  const metaRow = el('div', { class: 'answer-meta' });
  if (data.mode) {
    metaRow.appendChild(renderModeBadge(data.mode, data.modeLabel, data.fallbackReason));
  }
  const metaItems = [];
  if (Number.isFinite(Number(data.totalElapsedMs))) {
    metaItems.push('总耗时 ' + fmtMs(data.totalElapsedMs));
  }
  if (data.result && Number.isFinite(Number(data.result.rowCount))) {
    metaItems.push('返回 ' + data.result.rowCount + ' 行');
  }
  if (data.confidence && Number.isFinite(Number(data.confidence.score))) {
    metaItems.push('置信度 ' + data.confidence.score);
  }
  if (metaItems.length) {
    metaRow.appendChild(el('span', { class: 'answer-meta-text', text: metaItems.join(' · ') }));
  }
  if (metaRow.childNodes.length) {
    wrap.appendChild(metaRow);
  }

  if (data.error) {
    wrap.appendChild(renderChatError(data.error && data.error.code ? data.error : {
      code: 'B0500',
      message: typeof data.error === 'string' ? data.error : '管线执行失败'
    }));
  }

  if (asArray(data.steps).length) {
    wrap.appendChild(renderStepper(data.steps));
  }

  if (data.sql && data.sqlStatus !== 'none') {
    wrap.appendChild(renderSqlBlock(data.sql, { variant: opts.sqlVariant }));
  }

  const resultNode = renderResult(data.result, { sqlStatus: data.sqlStatus });
  if (resultNode) {
    wrap.appendChild(resultNode);
  }

  const confidenceCard = renderConfidenceCard(data.confidence);
  if (confidenceCard) {
    wrap.appendChild(confidenceCard);
  }

  if (opts.showRisks !== false) {
    const riskPanel = renderRiskPanel(data.risks);
    if (riskPanel) {
      wrap.appendChild(riskPanel);
    }
  }
  return wrap;
}
