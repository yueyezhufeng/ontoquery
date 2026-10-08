// OntoQuery 对比问答页 · 作者：月夜烛峰
// 双栏 chat（左本体右传统）+ 底部共享输入，一次调用 /api/query/compare；
// 每次结果写入 localStorage（ontoquery_last_compare）供语义解析过程页复用。
import { el, STORAGE_KEY_LAST_COMPARE } from '../util.js';
import { icon } from '../icons.js';
import { queryCompare, CORE_QUESTION } from '../api.js';
import { createMessages, createInputBar, renderPipelineContent, renderChatError } from '../components/chat.js';

const SUGGESTIONS = [
  CORE_QUESTION,
  '糖化血红蛋白大于7的2型糖尿病患者有多少人？',
  '被诊断为原发性高血压的患者使用了哪些药品？',
  '各科室的就诊人次是多少？',
  '使用格华止的患者中检验结果值大于7的有多少人？'
];

const ONTOLOGY_WELCOME =
  '你好，我是本体论智能问数助手。我会把你的问题映射到医疗本体（类、关系、实例），'
  + '经词典识别、关系链接、本体推理、路径规划、校验五步确定性流程生成 SQL 并执行。'
  + '点击下方建议或直接输入问题开始。';

const TRADITIONAL_WELCOME =
  '你好，我是传统 NL2SQL 助手。我基于数据库 Schema 与大模型端到端生成 SQL，'
  + '不依赖领域本体；生成后由 linter 规则做风险检测，结果仅供对照。';

function buildPanel(side) {
  const isOntology = side === 'ontology';
  const header = el('div', { class: 'qa-panel-header' }, [
    el('span', {
      class: 'qa-panel-badge ' + (isOntology ? 'ontology' : 'traditional'),
      html: icon(isOntology ? 'brain' : 'robot', 13)
    }, [el('span', { text: isOntology ? '本体增强' : '传统 NL2SQL' })]),
    el('span', { class: 'qa-panel-title', text: isOntology ? '本体论智能问数' : '传统文本转 SQL' }),
    el('span', {
      class: 'qa-panel-desc',
      text: isOntology ? '医疗本体 + 语义推理' : 'Schema + 大模型直接生成'
    })
  ]);
  const messages = createMessages({
    avatar: isOntology ? 'brain' : 'robot',
    avatarClass: isOntology ? 'ontology-ai' : '',
    welcome: isOntology ? ONTOLOGY_WELCOME : TRADITIONAL_WELCOME
  });
  const panel = el('section', { class: 'qa-panel' }, [header, messages.root]);
  return { panel: panel, messages: messages };
}

export function mount(container, ctx) {
  const ontology = buildPanel('ontology');
  const traditional = buildPanel('traditional');
  const layout = el('div', { class: 'qa-layout' }, [ontology.panel, traditional.panel]);

  const inputBar = createInputBar({
    placeholder: '输入问题，两侧管线将同时作答...',
    suggestions: SUGGESTIONS,
    submitLabel: '同时查询',
    onSubmit: (question) => {
      runCompare(question);
    }
  });

  const page = el('div', { class: 'page-root qa-page' }, [layout, inputBar.root]);
  container.appendChild(page);

  async function runCompare(question) {
    inputBar.setBusy(true);
    ontology.messages.addUser(question);
    traditional.messages.addUser(question);
    const ontHolder = ontology.messages.addAI('正在基于医疗本体解析你的问题...');
    const tradHolder = traditional.messages.addAI('正在基于 Schema 与大模型生成 SQL...');
    try {
      const data = await queryCompare(question, ctx.signal);
      ontHolder.done(renderPipelineContent(data.ontology, { sqlVariant: 'purple' }));
      tradHolder.done(renderPipelineContent(data.traditional, { sqlVariant: 'cyan' }));
      saveLastCompare(data);
    } catch (err) {
      if (err && err.aborted) {
        return;
      }
      ontHolder.fail(renderChatError(err));
      tradHolder.fail(renderChatError(err));
      if (ctx && typeof ctx.toast === 'function') {
        ctx.toast(err.message || '查询失败', 'error');
      }
    } finally {
      inputBar.setBusy(false);
    }
  }

  function saveLastCompare(data) {
    try {
      const payload = {
        question: data.question || '',
        ontology: data.ontology || null,
        traditional: data.traditional || null,
        savedAt: Date.now()
      };
      localStorage.setItem(STORAGE_KEY_LAST_COMPARE, JSON.stringify(payload));
    } catch (err) {
      // localStorage 不可用（隐私模式等）时不阻断主流程
    }
  }

  return {
    destroy: () => {
      // 页面级 AbortController 由 app.js 统一 abort，此处无额外全局资源
    },
    focusInput: () => {
      inputBar.focus();
    }
  };
}
