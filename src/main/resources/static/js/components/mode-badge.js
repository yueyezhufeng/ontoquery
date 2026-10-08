// OntoQuery 模式徽标组件 · 作者：月夜烛峰
// deterministic/llm 紫、llm-cache 青、mock 琥珀；title 展示 fallbackReason
import { el } from '../util.js';

const MODE_CLASS = {
  deterministic: 'deterministic',
  llm: 'llm',
  'llm-cache': 'cache',
  mock: 'mock'
};

const MODE_DEFAULT_LABEL = {
  deterministic: '确定性推理',
  llm: 'LLM 生成',
  'llm-cache': 'LLM 缓存命中',
  mock: 'Mock 降级'
};

/**
 * 渲染管线模式徽标。
 * @param {string} mode 模式标识
 * @param {string} modeLabel 展示名（缺省用默认映射）
 * @param {string} fallbackReason 降级原因（悬浮展示）
 * @returns {HTMLElement} 徽标元素
 */
export function renderModeBadge(mode, modeLabel, fallbackReason) {
  const cls = MODE_CLASS[mode] || 'unknown';
  const label = modeLabel || MODE_DEFAULT_LABEL[mode] || (mode || '未知模式');
  const titleParts = ['模式：' + label];
  if (fallbackReason) {
    titleParts.push('降级原因：' + fallbackReason);
  }
  return el('span', { class: 'mode-badge ' + cls, title: titleParts.join('\n'), text: label });
}
