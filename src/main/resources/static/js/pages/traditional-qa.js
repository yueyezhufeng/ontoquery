// OntoQuery 传统问数页 · 作者：月夜烛峰
// 单管线全屏 chat，真实调用 POST /api/query/traditional；
// 显式展示模式徽标与 linter 风险面板，并提供 Schema 参考（GET /api/meta/tables）。
import { el, asArray, fmtNum } from '../util.js';
import { icon } from '../icons.js';
import { queryTraditional, metaTables, CORE_QUESTION } from '../api.js';
import { createMessages, createInputBar, renderPipelineContent, renderChatError } from '../components/chat.js';

const SUGGESTIONS = [
  CORE_QUESTION,
  '患者共有多少人？',
  '各诊断疾病的记录数是多少？',
  '使用二甲双胍的患者有多少人？',
  '检验结果值大于10的记录有多少条？'
];

const WELCOME =
  '你好，我是传统 NL2SQL 助手。我基于数据库 Schema 与大模型直接生成 SQL，不依赖领域本体。'
  + '生成结果附带模式徽标与风险面板，供与本体管线对照评估。点击下方建议或直接输入问题开始。';

function buildSchemaModalBody(tables) {
  const body = el('div', { class: 'schema-modal' });
  const list = asArray(tables);
  if (!list.length) {
    body.appendChild(el('div', { class: 'state-desc', text: '暂无表元数据' }));
    return body;
  }
  list.forEach((table) => {
    const card = el('div', { class: 'schema-table-card' }, [
      el('div', { class: 'schema-table-head' }, [
        el('span', { class: 'schema-table-name mono', text: table.name }),
        el('span', { class: 'schema-table-comment', text: table.comment || '' }),
        el('span', { class: 'schema-table-rows', text: fmtNum(table.rowCount) + ' 行' })
      ])
    ]);
    const columns = asArray(table.columns);
    if (columns.length) {
      const tableEl = el('table', { class: 'ti-table schema-cols' });
      tableEl.appendChild(el('thead', null, [
        el('tr', null, [el('th', { text: '列名' }), el('th', { text: '类型' }), el('th', { text: '说明' })])
      ]));
      const tbody = el('tbody');
      columns.forEach((column) => {
        tbody.appendChild(el('tr', null, [
          el('td', { class: 'mono', text: column.name }),
          el('td', { class: 'mono', text: column.type || '' }),
          el('td', { text: column.comment || '' })
        ]));
      });
      tableEl.appendChild(tbody);
      card.appendChild(tableEl);
    }
    body.appendChild(card);
  });
  return body;
}

export function mount(container, ctx) {
  let tablesCache = null;

  const schemaBtn = el('button', {
    class: 'btn btn-sm schema-btn',
    title: '查看数据库 Schema（来自 /api/meta/tables）',
    html: icon('database', 13),
    on: {
      click: () => {
        openSchema();
      }
    }
  }, [el('span', { text: 'Schema 参考' })]);

  const header = el('div', { class: 'qa-panel-header' }, [
    el('span', { class: 'qa-panel-badge traditional', html: icon('robot', 13) }, [
      el('span', { text: '传统 NL2SQL' })
    ]),
    el('span', { class: 'qa-panel-title', text: '传统文本转 SQL' }),
    schemaBtn,
    el('span', { class: 'qa-panel-desc', text: 'Schema + 大模型端到端生成' })
  ]);

  const messages = createMessages({ avatar: 'robot', avatarClass: '', welcome: WELCOME });
  const panel = el('section', { class: 'qa-panel qa-panel-single' }, [header, messages.root]);

  const inputBar = createInputBar({
    placeholder: '用自然语言提问，传统 NL2SQL 方式...',
    suggestions: SUGGESTIONS,
    submitLabel: '查询',
    onSubmit: (question) => {
      runQuery(question);
    }
  });

  const page = el('div', { class: 'page-root qa-page qa-page-single' }, [panel, inputBar.root]);
  container.appendChild(page);

  async function openSchema() {
    schemaBtn.disabled = true;
    try {
      if (!tablesCache) {
        tablesCache = await metaTables(ctx.signal);
      }
      const tables = asArray(tablesCache);
      if (ctx && typeof ctx.modal === 'function') {
        ctx.modal({
          title: '数据库 Schema 参考（' + tables.length + ' 张表）',
          body: buildSchemaModalBody(tables),
          actions: [{ label: '关闭' }]
        });
      }
    } catch (err) {
      if (err && err.aborted) {
        return;
      }
      if (ctx && typeof ctx.toast === 'function') {
        ctx.toast(err.message || 'Schema 加载失败', 'error');
      }
    } finally {
      schemaBtn.disabled = false;
    }
  }

  async function runQuery(question) {
    inputBar.setBusy(true);
    messages.addUser(question);
    const holder = messages.addAI('正在基于 Schema 与大模型生成 SQL...');
    try {
      const pipeline = await queryTraditional(question, ctx.signal);
      holder.done(renderPipelineContent(pipeline, { sqlVariant: 'cyan' }));
    } catch (err) {
      if (err && err.aborted) {
        return;
      }
      holder.fail(renderChatError(err));
      if (ctx && typeof ctx.toast === 'function') {
        ctx.toast(err.message || '查询失败', 'error');
      }
    } finally {
      inputBar.setBusy(false);
    }
  }

  return {
    destroy: () => {
    },
    focusInput: () => {
      inputBar.focus();
    }
  };
}
