// OntoQuery 本体问数页 · 作者：月夜烛峰
// 单管线全屏 chat，真实调用 POST /api/query/ontology，复用全部应答组件。
import { el } from '../util.js';
import { icon } from '../icons.js';
import { queryOntology, CORE_QUESTION } from '../api.js';
import { createMessages, createInputBar, renderPipelineContent, renderChatError } from '../components/chat.js';

const SUGGESTIONS = [
  CORE_QUESTION,
  '糖化血红蛋白大于7的2型糖尿病患者有多少人？',
  '近30天被诊断为 E11 的患者中使用了二甲双胍的有多少人？',
  '各科室的就诊人次是多少？',
  '患者的平均年龄是多少？'
];

const WELCOME =
  '你好，我是本体论智能问数助手。提问后将展示五步解析轨迹（词典识别、关系链接、'
  + '本体推理、路径规划与 SQL 生成、校验与置信度），全部推理过程可追溯。'
  + '点击下方建议或直接输入问题开始。';

export function mount(container, ctx) {
  const header = el('div', { class: 'qa-panel-header' }, [
    el('span', { class: 'qa-panel-badge ontology', html: icon('brain', 13) }, [
      el('span', { text: '本体增强' })
    ]),
    el('span', { class: 'qa-panel-title', text: '本体论智能问数' }),
    el('span', { class: 'qa-panel-desc', text: '医疗本体 + 五步确定性推理' })
  ]);

  const messages = createMessages({ avatar: 'brain', avatarClass: 'ontology-ai', welcome: WELCOME });
  const panel = el('section', { class: 'qa-panel qa-panel-single' }, [header, messages.root]);

  const inputBar = createInputBar({
    placeholder: '用自然语言提问，本体增强解析...',
    suggestions: SUGGESTIONS,
    submitLabel: '查询',
    onSubmit: (question) => {
      runQuery(question);
    }
  });

  const page = el('div', { class: 'page-root qa-page qa-page-single' }, [panel, inputBar.root]);
  container.appendChild(page);

  async function runQuery(question) {
    inputBar.setBusy(true);
    messages.addUser(question);
    const holder = messages.addAI('正在基于医疗本体解析你的问题...');
    try {
      const pipeline = await queryOntology(question, ctx.signal);
      holder.done(renderPipelineContent(pipeline, { sqlVariant: 'purple' }));
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
