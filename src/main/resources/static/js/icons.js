// OntoQuery 图标库 · 全站唯一图标来源（禁止 emoji）· 作者：月夜烛峰
// 统一风格：24x24 线性图标，stroke=currentColor，stroke-width=1.8，fill=none
const ICON_PATHS = {
  'compare':
    '<rect x="3.5" y="5" width="7" height="14" rx="1.5"/>'
    + '<rect x="13.5" y="5" width="7" height="14" rx="1.5"/>'
    + '<path d="M10.5 12h3"/>',
  'brain':
    '<path d="M12 5c-1-1.2-2.2-1.8-3.5-1.8-2.4 0-4 1.9-4 4.1 0 1 .3 1.8.9 2.5'
    + '-.6.7-.9 1.5-.9 2.5 0 2.2 1.6 4.1 4 4.1 1.3 0 2.5-.6 3.5-1.8z"/>'
    + '<path d="M12 5c1-1.2 2.2-1.8 3.5-1.8 2.4 0 4 1.9 4 4.1 0 1-.3 1.8-.9 2.5'
    + '.6.7.9 1.5.9 2.5 0 2.2-1.6 4.1-4 4.1-1.3 0-2.5-.6-3.5-1.8z"/>'
    + '<path d="M12 4.6v14.8"/>',
  'robot':
    '<rect x="5" y="8" width="14" height="11" rx="2"/>'
    + '<path d="M12 8V5.2"/><circle cx="12" cy="4" r="1.1"/>'
    + '<path d="M9.4 12.6v1.6M14.6 12.6v1.6M9.5 16.6h5"/>',
  'graph':
    '<circle cx="6" cy="18" r="2.2"/><circle cx="18" cy="18" r="2.2"/><circle cx="12" cy="6" r="2.2"/>'
    + '<path d="M7.6 16.2 10.8 8.2M13.2 8.2l3.2 8M8.2 18h7.6"/>',
  'flask':
    '<path d="M10 3v6.4L4.8 18a2 2 0 0 0 1.7 3h11a2 2 0 0 0 1.7-3L14 9.4V3"/>'
    + '<path d="M8.5 3h7M7.4 14.5h9.2"/>',
  'scale':
    '<path d="M12 3v18M7 21h10M12 5.5 5.5 7.8M12 5.5l6.5 2.3"/>'
    + '<path d="M2.5 13a3.2 3.2 0 0 0 6 0L5.5 7.8z"/><path d="M15.5 13a3.2 3.2 0 0 0 6 0L18.5 7.8z"/>',
  'circle-check': '<circle cx="12" cy="12" r="8.6"/><path d="m8.4 12.3 2.4 2.4 4.8-5.2"/>',
  'triangle-alert': '<path d="M12 4 2.8 19.5h18.4z"/><path d="M12 10v4.2M12 16.9v.01"/>',
  'octagon-x':
    '<path d="M8.7 3h6.6L21 8.7v6.6L15.3 21H8.7L3 15.3V8.7z"/><path d="m9.5 9.5 5 5M14.5 9.5l-5 5"/>',
  'zoom': '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.8-3.8M8.5 11h5M11 8.5v5"/>',
  'search': '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.8-3.8"/>',
  'send': '<path d="M21 3 10.5 13.5"/><path d="M21 3l-6.8 18-3.7-7.5L3 9.8z"/>',
  'clock': '<circle cx="12" cy="12" r="8.6"/><path d="M12 7v5l3.2 2"/>',
  'database':
    '<ellipse cx="12" cy="5.5" rx="8" ry="3"/><path d="M4 5.5V12c0 1.7 3.6 3 8 3s8-1.3 8-3V5.5"/>'
    + '<path d="M4 12v6.5c0 1.7 3.6 3 8 3s8-1.3 8-3V12"/>',
  'cpu':
    '<rect x="6" y="6" width="12" height="12" rx="2"/><rect x="9.5" y="9.5" width="5" height="5" rx="1"/>'
    + '<path d="M9 3v3M15 3v3M9 18v3M15 18v3M3 9h3M3 15h3M18 9h3M18 15h3"/>',
  'branch':
    '<circle cx="6" cy="5" r="2.2"/><circle cx="6" cy="19" r="2.2"/><circle cx="18" cy="9" r="2.2"/>'
    + '<path d="M6 7.2v9.6"/><path d="M6 10c0-2.8 5-2 8.8-2.6"/>',
  'target': '<circle cx="12" cy="12" r="8.6"/><circle cx="12" cy="12" r="4.8"/><circle cx="12" cy="12" r="1.1"/>',
  'list':
    '<path d="M8.5 6h12M8.5 12h12M8.5 18h12"/>'
    + '<circle cx="4" cy="6" r="1"/><circle cx="4" cy="12" r="1"/><circle cx="4" cy="18" r="1"/>',
  'chart': '<path d="M4 4v16h16"/><path d="M8 16v-5M12 16V8M16 16v-3"/>',
  'user': '<circle cx="12" cy="8" r="4"/><path d="M4.5 20c1.2-3.2 4-5 7.5-5s6.3 1.8 7.5 5"/>',
  'pill': '<path d="M10.5 20.5 3.5 13.5a4.95 4.95 0 1 1 7-7l7 7a4.95 4.95 0 1 1-7 7z"/><path d="m8.5 8.5 7 7"/>',
  'activity': '<path d="M3 12h4l2.5-7 4 14 2.5-7h5"/>',
  'refresh': '<path d="M20.5 8.5A8.6 8.6 0 1 0 21 12"/><path d="M21 3v5.5h-5.5"/>',
  'chevron-right': '<path d="m9 5 7 7-7 7"/>',
  'chevron-down': '<path d="m5 9 7 7 7-7"/>',
  'plus': '<path d="M12 5v14M5 12h14"/>',
  'minus': '<path d="M5 12h14"/>',
  'home': '<path d="M4.5 10.5 12 4l7.5 6.5"/><path d="M6.5 9.5V20h11V9.5"/>',
  'close': '<path d="m6 6 12 12M18 6 6 18"/>',
  'copy':
    '<rect x="9" y="9" width="11" height="11" rx="2"/>'
    + '<path d="M5 15c-1.1 0-2-.9-2-2V5c0-1.1.9-2 2-2h8c1.1 0 2 .9 2 2"/>',
  'download': '<path d="M12 4v10M7.5 10.5 12 15l4.5-4.5M4.5 19.5h15"/>',
  'play': '<path d="M8 5.5v13l11-6.5z"/>',
  'info': '<circle cx="12" cy="12" r="8.6"/><path d="M12 11v5M12 8v.01"/>',
  'wrench':
    '<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94'
    + 'l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>'
};

/**
 * 按名称取内联 SVG 图标字符串。
 * @param {string} name 图标名（见 ICON_PATHS）
 * @param {number} size 渲染尺寸（px，默认 24）
 * @returns {string} SVG 字符串；名称不存在时返回空串
 */
export function icon(name, size) {
  const body = ICON_PATHS[name];
  if (!body) {
    return '';
  }
  const s = size || 24;
  return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="' + s + '" height="' + s
    + '" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"'
    + ' stroke-linejoin="round" aria-hidden="true">' + body + '</svg>';
}

export const ICON_NAMES = Object.keys(ICON_PATHS);
