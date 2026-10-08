// OntoQuery 本体图谱页 · 作者：月夜烛峰
// 三栏：左类树（/api/ontology/tree）+ 中 SVG 画布（/api/ontology/graph，分层布局、
// data 实线箭头、subclass 虚线、滚轮缩放、拖拽平移）+ 右实体详情（/api/ontology/entity/{code}）。
import { el, asArray, fmtNum } from '../util.js';
import { icon } from '../icons.js';
import { ontologyTree, ontologyGraph, entity } from '../api.js';

const SVG_NS = 'http://www.w3.org/2000/svg';
const NODE_HEIGHT = 46;
const NODE_GAP_X = 36;
const LAYER_GAP_Y = 112;
const CANVAS_PADDING = 40;
const INSTANCE_LIST_MAX = 50;

function svgEl(tag, attrs) {
  const node = document.createElementNS(SVG_NS, tag);
  Object.keys(attrs || {}).forEach((key) => {
    node.setAttribute(key, attrs[key]);
  });
  return node;
}

/** 估算节点宽度：CJK 字符按 13px、其它按 7.5px */
function measureWidth(text) {
  let width = 0;
  const value = String(text || '');
  for (let i = 0; i < value.length; i++) {
    width += value.charCodeAt(i) > 0x2e7f ? 13 : 7.5;
  }
  return width;
}

function stateBlock(iconName, title, desc, action) {
  const block = el('div', { class: 'state-block' }, [
    el('div', { class: 'state-icon', html: icon(iconName, 22) }),
    el('div', { class: 'state-title', text: title }),
    el('div', { class: 'state-desc', text: desc || '' })
  ]);
  if (action) {
    block.appendChild(action);
  }
  return block;
}

export function mount(container, ctx) {
  const state = {
    tree: [],
    graph: null,
    layout: null,
    nodeEls: new Map(),
    edgeEls: [],
    selected: null,
    expanded: new Set(),
    tab: 'detail',
    instanceFilter: '',
    entityCache: new Map(),
    transform: { x: 0, y: 0, k: 1 }
  };

  // ---------- 左栏：类树 ----------
  const treeBody = el('div', { class: 'tree-body' });
  const treePanel = el('aside', { class: 'panel graph-side' }, [
    el('div', { class: 'panel-header' }, [
      el('span', { class: 'panel-ic', html: icon('branch', 14) }),
      el('span', { class: 'panel-title', text: '本体类树 · 医疗领域' })
    ]),
    treeBody
  ]);

  // ---------- 中栏：SVG 画布 ----------
  const svg = svgEl('svg', { class: 'graph-svg' });
  const defs = svgEl('defs');
  defs.innerHTML =
    '<marker id="g-arrow-data" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">'
    + '<polygon points="0 0, 10 3.5, 0 7" fill="#8b93a7"/></marker>'
    + '<marker id="g-arrow-sub" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">'
    + '<polygon points="0 0, 10 3.5, 0 7" fill="#a855f7"/></marker>';
  svg.appendChild(defs);
  const edgeGroup = svgEl('g', { class: 'g-edges' });
  const nodeGroup = svgEl('g', { class: 'g-nodes' });
  const viewport = svgEl('g');
  viewport.appendChild(edgeGroup);
  viewport.appendChild(nodeGroup);
  svg.appendChild(viewport);

  const canvasOverlay = el('div', { class: 'graph-overlay' });
  const legend = el('div', { class: 'graph-legend' }, [
    el('span', { class: 'legend-item' }, [
      el('span', { class: 'legend-swatch rect solid' }),
      el('span', { text: '本体类' })
    ]),
    el('span', { class: 'legend-item' }, [
      el('span', { class: 'legend-swatch rect dashed' }),
      el('span', { text: '子类关系' })
    ]),
    el('span', { class: 'legend-item' }, [
      el('span', { class: 'legend-swatch line' }),
      el('span', { text: '数据关系' })
    ])
  ]);

  const toolbar = el('div', { class: 'graph-toolbar' }, [
    el('div', { class: 'graph-toolbar-group' }, [
      toolButton('home', '适应画布', () => fitView()),
      toolButton('plus', '放大', () => zoomByCenter(1.2)),
      toolButton('minus', '缩小', () => zoomByCenter(1 / 1.2))
    ]),
    el('div', { class: 'graph-toolbar-group' }, [
      toolButton('refresh', '重新加载', () => loadGraph())
    ])
  ]);

  const canvas = el('div', { class: 'graph-canvas' }, [svg, toolbar, legend, canvasOverlay]);

  function toolButton(iconName, title, onClick) {
    return el('button', {
      class: 'graph-tool-btn',
      title: title,
      html: icon(iconName, 15),
      on: { click: onClick }
    });
  }

  // ---------- 右栏：实体详情 ----------
  const tabsHead = el('div', { class: 'tabs' });
  const detailBody = el('div', { class: 'entity-body' });
  const entityPanel = el('aside', { class: 'panel graph-side entity-panel' }, [
    el('div', { class: 'panel-header' }, [
      el('span', { class: 'panel-ic', html: icon('zoom', 14) }),
      el('span', { class: 'panel-title', text: '实体详情' })
    ]),
    tabsHead,
    detailBody
  ]);

  const layout = el('div', { class: 'graph-layout' }, [treePanel, canvas, entityPanel]);
  const page = el('div', { class: 'page-root graph-page' }, [layout]);
  container.appendChild(page);

  // ==================== 数据加载 ====================

  loadTree();
  loadGraph();

  function loadTree() {
    treeBody.innerHTML = '';
    treeBody.appendChild(el('div', { class: 'mini-loading' }, [
      el('span', { class: 'spinner' }),
      el('span', { text: '正在加载类树...' })
    ]));
    ontologyTree(ctx.signal).then((data) => {
      state.tree = asArray(data);
      if (state.tree.length && !state.expanded.size) {
        state.tree.forEach((group) => {
          if (group.code) {
            state.expanded.add(group.code);
          }
        });
      }
      renderTree();
      const firstLeaf = findFirstLeaf(state.tree);
      if (firstLeaf) {
        select(firstLeaf.code, { fromTree: true });
      }
    }).catch((err) => {
      if (err && err.aborted) {
        return;
      }
      treeBody.innerHTML = '';
      treeBody.appendChild(stateBlock('triangle-alert', '类树加载失败', err.message, retryButton('重试', loadTree)));
    });
  }

  function loadGraph() {
    canvasOverlay.innerHTML = '';
    canvasOverlay.appendChild(el('div', { class: 'mini-loading center' }, [
      el('span', { class: 'spinner' }),
      el('span', { text: '正在加载本体图谱...' })
    ]));
    ontologyGraph(ctx.signal).then((data) => {
      state.graph = data || {};
      canvasOverlay.innerHTML = '';
      buildGraph();
      fitView();
      if (state.selected) {
        applyHighlight();
      }
    }).catch((err) => {
      if (err && err.aborted) {
        return;
      }
      canvasOverlay.innerHTML = '';
      canvasOverlay.appendChild(stateBlock('triangle-alert', '图谱加载失败', err.message, retryButton('重试', loadGraph)));
    });
  }

  function retryButton(label, onClick) {
    const button = el('button', { class: 'btn btn-sm', type: 'button', on: { click: onClick } }, [
      el('span', { class: 'btn-ic', html: icon('refresh', 13) }),
      el('span', { text: label })
    ]);
    return button;
  }

  // ==================== 类树渲染 ====================

  function findFirstLeaf(nodes) {
    for (const node of asArray(nodes)) {
      const children = asArray(node.children);
      if (children.length) {
        const leaf = findFirstLeaf(children);
        if (leaf) {
          return leaf;
        }
      } else if (node.code) {
        return node;
      }
    }
    return null;
  }

  function renderTree() {
    treeBody.innerHTML = '';
    state.tree.forEach((group) => {
      treeBody.appendChild(renderTreeNode(group, 0));
    });
  }

  function renderTreeNode(node, depth) {
    const children = asArray(node.children);
    const hasChildren = children.length > 0;
    const expanded = state.expanded.has(node.code);
    const row = el('div', {
      class: 'tree-item' + (hasChildren ? ' group' : ' leaf')
        + (state.selected === node.code ? ' active' : ''),
      on: {
        click: () => {
          if (hasChildren) {
            if (state.expanded.has(node.code)) {
              state.expanded.delete(node.code);
            } else {
              state.expanded.add(node.code);
            }
          }
          select(node.code, { fromTree: true });
        }
      }
    }, [
      el('span', {
        class: 'tree-toggle',
        html: hasChildren ? icon(expanded ? 'chevron-down' : 'chevron-right', 13) : '<span class="tree-dot"></span>'
      }),
      el('span', {
        class: 'tree-color',
        style: 'background:' + (node.color || '#676e80')
      }),
      el('span', { class: 'tree-name', text: node.name || node.code }),
      el('span', { class: 'tree-count', text: fmtNum(node.instanceCount) })
    ]);
    const wrap = el('div', { class: 'tree-node', style: '--depth:' + depth }, [row]);
    if (hasChildren && expanded) {
      const box = el('div', { class: 'tree-children' });
      children.forEach((child) => {
        box.appendChild(renderTreeNode(child, depth + 1));
      });
      wrap.appendChild(box);
    }
    return wrap;
  }

  // ==================== 画布构建与交互 ====================

  function buildGraph() {
    const nodes = asArray(state.graph && state.graph.nodes);
    const edges = asArray(state.graph && state.graph.edges);
    if (!nodes.length) {
      canvasOverlay.appendChild(stateBlock('info', '暂无图谱数据', '后端未返回节点'));
      return;
    }
    const layers = new Map();
    nodes.forEach((node) => {
      const layer = Number.isFinite(Number(node.layer)) ? Number(node.layer) : 99;
      if (!layers.has(layer)) {
        layers.set(layer, []);
      }
      layers.get(layer).push(node);
    });
    const sortedLayers = Array.from(layers.keys()).sort((a, b) => a - b);
    const positions = new Map();
    let maxWidth = 0;
    sortedLayers.forEach((layerKey, rowIndex) => {
      const row = layers.get(layerKey);
      const widths = row.map((node) => Math.max(96, measureWidth(node.name) + measureWidth(node.code) / 2 + 34));
      const rowWidth = widths.reduce((sum, width) => sum + width, 0) + NODE_GAP_X * Math.max(0, row.length - 1);
      let x = CANVAS_PADDING;
      const y = CANVAS_PADDING + rowIndex * (NODE_HEIGHT + LAYER_GAP_Y);
      row.forEach((node, index) => {
        positions.set(node.code, { x: x, y: y, w: widths[index], h: NODE_HEIGHT });
        x += widths[index] + NODE_GAP_X;
      });
      maxWidth = Math.max(maxWidth, rowWidth + CANVAS_PADDING * 2);
    });
    const totalHeight = CANVAS_PADDING * 2 + sortedLayers.length * (NODE_HEIGHT + LAYER_GAP_Y) - LAYER_GAP_Y;
    state.layout = { positions: positions, width: Math.max(maxWidth, 400), height: Math.max(totalHeight, 300) };

    edgeGroup.innerHTML = '';
    nodeGroup.innerHTML = '';
    state.nodeEls.clear();
    state.edgeEls = [];

    edges.forEach((edge) => {
      drawEdge(edge, positions);
    });
    nodes.forEach((node) => {
      drawNode(node, positions);
    });
  }

  function drawNode(node, positions) {
    const pos = positions.get(node.code);
    if (!pos) {
      return;
    }
    const group = svgEl('g', {
      class: 'gnode',
      transform: 'translate(' + pos.x + ',' + pos.y + ')',
      'data-code': node.code
    });
    const rect = svgEl('rect', {
      class: 'gnode-rect',
      width: pos.w,
      height: pos.h,
      rx: 8,
      stroke: node.color || '#676e80'
    });
    const name = svgEl('text', {
      class: 'gnode-name',
      x: pos.w / 2,
      y: 20,
      'text-anchor': 'middle'
    });
    name.textContent = node.name || node.code;
    const sub = svgEl('text', {
      class: 'gnode-sub',
      x: pos.w / 2,
      y: 36,
      'text-anchor': 'middle'
    });
    sub.textContent = node.code || '';
    group.appendChild(rect);
    group.appendChild(name);
    group.appendChild(sub);
    group.addEventListener('click', (event) => {
      event.stopPropagation();
      select(node.code, {});
    });
    nodeGroup.appendChild(group);
    state.nodeEls.set(node.code, group);
  }

  function drawEdge(edge, positions) {
    const from = positions.get(edge.from);
    const to = positions.get(edge.to);
    if (!from || !to) {
      return;
    }
    const isSubclass = edge.kind === 'subclass';
    const fromCenter = { x: from.x + from.w / 2, y: from.y + from.h / 2 };
    const toCenter = { x: to.x + to.w / 2, y: to.y + to.h / 2 };
    let x1;
    let y1;
    let x2;
    let y2;
    let c1;
    let c2;
    if (toCenter.y > fromCenter.y + 8) {
      x1 = fromCenter.x;
      y1 = from.y + from.h;
      x2 = toCenter.x;
      y2 = to.y;
      const dy = Math.max(26, (y2 - y1) / 2);
      c1 = { x: x1, y: y1 + dy };
      c2 = { x: x2, y: y2 - dy };
    } else if (toCenter.y < fromCenter.y - 8) {
      x1 = fromCenter.x;
      y1 = from.y;
      x2 = toCenter.x;
      y2 = to.y + to.h;
      const dy = Math.max(26, (y1 - y2) / 2);
      c1 = { x: x1, y: y1 - dy };
      c2 = { x: x2, y: y2 + dy };
    } else if (toCenter.x >= fromCenter.x) {
      x1 = from.x + from.w;
      y1 = fromCenter.y;
      x2 = to.x;
      y2 = toCenter.y;
      const dx = Math.max(26, (x2 - x1) / 2);
      c1 = { x: x1 + dx, y: y1 };
      c2 = { x: x2 - dx, y: y2 };
    } else {
      x1 = from.x;
      y1 = fromCenter.y;
      x2 = to.x + to.w;
      y2 = toCenter.y;
      const dx = Math.max(26, (x1 - x2) / 2);
      c1 = { x: x1 - dx, y: y1 };
      c2 = { x: x2 + dx, y: y2 };
    }
    const path = svgEl('path', {
      class: 'gedge ' + (isSubclass ? 'subclass' : 'data'),
      d: 'M' + x1 + ' ' + y1 + ' C ' + c1.x + ' ' + c1.y + ', ' + c2.x + ' ' + c2.y + ', ' + x2 + ' ' + y2,
      'marker-end': isSubclass ? 'url(#g-arrow-sub)' : 'url(#g-arrow-data)',
      'data-from': edge.from,
      'data-to': edge.to
    });
    edgeGroup.appendChild(path);
    const midX = (x1 + 3 * c1.x + 3 * c2.x + x2) / 8;
    const midY = (y1 + 3 * c1.y + 3 * c2.y + y2) / 8;
    const label = svgEl('text', {
      class: 'glabel' + (isSubclass ? ' sub' : ''),
      x: midX,
      y: midY - 5,
      'text-anchor': 'middle',
      'data-from': edge.from,
      'data-to': edge.to
    });
    label.textContent = edge.label || '';
    edgeGroup.appendChild(label);
    state.edgeEls.push(path);
    state.edgeEls.push(label);
  }

  function applyTransform() {
    const t = state.transform;
    viewport.setAttribute('transform', 'translate(' + t.x + ',' + t.y + ') scale(' + t.k + ')');
  }

  function fitView() {
    if (!state.layout) {
      return;
    }
    const rect = canvas.getBoundingClientRect();
    const cw = rect.width || 800;
    const ch = rect.height || 500;
    const k = Math.min((cw - 40) / state.layout.width, (ch - 40) / state.layout.height, 1.15);
    state.transform.k = Math.max(0.25, k);
    state.transform.x = (cw - state.layout.width * state.transform.k) / 2;
    state.transform.y = (ch - state.layout.height * state.transform.k) / 2;
    applyTransform();
  }

  function zoomByCenter(factor) {
    const rect = canvas.getBoundingClientRect();
    zoomAt(rect.width / 2, rect.height / 2, factor);
  }

  function zoomAt(px, py, factor) {
    const t = state.transform;
    const k = Math.max(0.25, Math.min(3, t.k * factor));
    const wx = (px - t.x) / t.k;
    const wy = (py - t.y) / t.k;
    t.x = px - wx * k;
    t.y = py - wy * k;
    t.k = k;
    applyTransform();
  }

  function applyHighlight() {
    const code = state.selected;
    state.nodeEls.forEach((nodeEl, nodeCode) => {
      nodeEl.classList.toggle('selected', nodeCode === code);
      nodeEl.classList.toggle('dim', Boolean(code) && nodeCode !== code);
    });
    state.edgeEls.forEach((edgeEl) => {
      const touched = code
        && (edgeEl.getAttribute('data-from') === code || edgeEl.getAttribute('data-to') === code);
      edgeEl.classList.toggle('hl', Boolean(touched));
      edgeEl.classList.toggle('dim', Boolean(code) && !touched);
    });
  }

  // 滚轮缩放（以指针为锚点）
  svg.addEventListener('wheel', (event) => {
    event.preventDefault();
    const rect = canvas.getBoundingClientRect();
    zoomAt(event.clientX - rect.left, event.clientY - rect.top, event.deltaY < 0 ? 1.12 : 1 / 1.12);
  }, { passive: false });

  // 拖拽平移
  let drag = null;
  svg.addEventListener('pointerdown', (event) => {
    if (event.target.closest && event.target.closest('.gnode')) {
      return;
    }
    drag = { startX: event.clientX, startY: event.clientY, originX: state.transform.x, originY: state.transform.y };
    svg.classList.add('dragging');
    svg.setPointerCapture(event.pointerId);
  });
  svg.addEventListener('pointermove', (event) => {
    if (!drag) {
      return;
    }
    state.transform.x = drag.originX + (event.clientX - drag.startX);
    state.transform.y = drag.originY + (event.clientY - drag.startY);
    applyTransform();
  });
  const endDrag = () => {
    drag = null;
    svg.classList.remove('dragging');
  };
  svg.addEventListener('pointerup', endDrag);
  svg.addEventListener('pointercancel', endDrag);

  // ==================== 选择与实体详情 ====================

  function select(code, options) {
    if (!code) {
      return;
    }
    const opts = options || {};
    state.selected = code;
    if (!opts.keepTab) {
      state.tab = 'detail';
      state.instanceFilter = '';
    }
    renderTree();
    applyHighlight();
    loadEntity(code);
  }

  function loadEntity(code) {
    if (state.entityCache.has(code)) {
      renderEntity(state.entityCache.get(code));
      return;
    }
    detailBody.innerHTML = '';
    detailBody.appendChild(el('div', { class: 'mini-loading' }, [
      el('span', { class: 'spinner' }),
      el('span', { text: '正在加载实体详情...' })
    ]));
    tabsHead.innerHTML = '';
    entity(code, ctx.signal).then((data) => {
      state.entityCache.set(code, data);
      // 同一实体可能因并发选中而重复请求，仅渲染当前选中
      if (state.selected !== code) {
        return;
      }
      renderEntity(data);
    }).catch((err) => {
      if (err && err.aborted) {
        return;
      }
      if (state.selected !== code) {
        return;
      }
      detailBody.innerHTML = '';
      detailBody.appendChild(stateBlock('triangle-alert', '实体详情加载失败', err.message));
    });
  }

  function renderEntity(data) {
    detailBody.innerHTML = '';
    renderTabs(data);
    if (state.tab === 'instances' && data.kind === 'class') {
      renderInstancesTab(data);
    } else {
      renderDetailTab(data);
    }
  }

  function renderTabs(data) {
    tabsHead.innerHTML = '';
    tabsHead.appendChild(el('button', {
      class: 'tab' + (state.tab === 'detail' ? ' active' : ''),
      type: 'button',
      on: { click: () => { state.tab = 'detail'; renderEntity(currentEntity()); } },
      text: '详情'
    }));
    if (data.kind === 'class') {
      const total = Number.isFinite(Number(data.instanceTotal))
        ? Number(data.instanceTotal)
        : asArray(data.instances).length;
      tabsHead.appendChild(el('button', {
        class: 'tab' + (state.tab === 'instances' ? ' active' : ''),
        type: 'button',
        on: { click: () => { state.tab = 'instances'; renderEntity(currentEntity()); } },
        text: '实例 ' + fmtNum(total)
      }));
    }
  }

  function currentEntity() {
    return state.entityCache.get(state.selected) || { kind: 'class' };
  }

  function sectionTitle(iconName, text) {
    return el('div', { class: 'entity-section-title' }, [
      el('span', { class: 'entity-section-ic', html: icon(iconName, 12) }),
      el('span', { text: text })
    ]);
  }

  function attrRow(name, value) {
    return el('div', { class: 'attr-item' }, [
      el('span', { class: 'attr-name', text: name }),
      el('span', { class: 'attr-type mono', text: value })
    ]);
  }

  function renderDetailTab(data) {
    const head = el('div', { class: 'entity-head' }, [
      el('span', {
        class: 'entity-color-dot',
        style: 'background:' + (data.color || (data.classInfo && data.classInfo.color) || '#676e80')
      }),
      el('span', { class: 'entity-name', text: data.name || data.code || '' })
    ]);
    const kindText = data.kind === 'instance'
      ? '实例 · 属于 ' + (data.classInfo ? data.classInfo.name : '')
      : '本体类' + (data.parent ? ' · 隶属 ' + data.parent.name : '');
    detailBody.appendChild(head);
    detailBody.appendChild(el('div', { class: 'entity-type mono', text: (data.code || '') + ' · ' + kindText }));
    if (data.remark) {
      detailBody.appendChild(el('div', { class: 'entity-remark', text: data.remark }));
    }

    const baseSection = el('div', { class: 'entity-section' }, [sectionTitle('info', '基本信息')]);
    baseSection.appendChild(attrRow('编码', data.code || '--'));
    if (data.kind === 'class') {
      baseSection.appendChild(attrRow('父类', data.parent ? data.parent.name : '--'));
      baseSection.appendChild(attrRow('实例数', fmtNum(data.instanceTotal) + ' 条记录'));
    } else if (data.classInfo) {
      baseSection.appendChild(attrRow('所属类', data.classInfo.name + '（' + data.classInfo.code + '）'));
    }
    detailBody.appendChild(baseSection);

    const attributes = asArray(data.attributes);
    if (attributes.length) {
      const section = el('div', { class: 'entity-section' }, [sectionTitle('list', '数据属性')]);
      attributes.forEach((attribute) => {
        const range = attribute.valueLow !== null && attribute.valueLow !== undefined
          && attribute.valueHigh !== null && attribute.valueHigh !== undefined
          ? attribute.valueLow + ' ~ ' + attribute.valueHigh
          : '--';
        const mapping = asArray(attribute.mappings)
          .map((item) => (item.column ? item.table + '.' + item.column : item.table))
          .join(', ');
        const row = el('div', { class: 'attr-item attr-multiline' }, [
          el('div', { class: 'attr-line' }, [
            el('span', { class: 'attr-name', text: attribute.name }),
            el('span', { class: 'attr-type mono', text: attribute.dataType || '--' })
          ]),
          el('div', { class: 'attr-line sub' }, [
            el('span', { text: '单位 ' + (attribute.unit || '--') + ' · 值域 ' + range })
          ]),
          mapping ? el('div', { class: 'attr-line sub mono', text: mapping }) : null
        ]);
        section.appendChild(row);
      });
      detailBody.appendChild(section);
    }

    const relations = asArray(data.relations);
    if (relations.length) {
      const section = el('div', { class: 'entity-section' }, [sectionTitle('branch', '对象关系')]);
      const chips = el('div', { class: 'chip-row' });
      relations.forEach((relation) => {
        const targetName = relation.target ? relation.target.name : '';
        const label = relation.direction === 'in'
          ? targetName + ' <- ' + relation.name
          : relation.name + ' -> ' + targetName;
        chips.appendChild(el('button', {
          class: 'o-chip cyan',
          title: relation.code || label,
          text: label,
          on: {
            click: () => {
              if (relation.target && relation.target.code) {
                select(relation.target.code, {});
              }
            }
          }
        }));
      });
      section.appendChild(chips);
      detailBody.appendChild(section);
    }

    const mappings = asArray(data.mappings);
    if (mappings.length) {
      const section = el('div', { class: 'entity-section' }, [sectionTitle('database', '映射到数据源')]);
      const chips = el('div', { class: 'chip-row' });
      mappings.forEach((mapping) => {
        const label = mapping.column ? mapping.table + '.' + mapping.column : mapping.table;
        if (mapping.valueExpr) {
          const pre = el('pre', { class: 'ti ti-sql mapping-expr' });
          pre.textContent = mapping.valueExpr;
          section.appendChild(pre);
        }
        chips.appendChild(el('span', {
          class: 'o-chip green' + (mapping.kind === 'instance' ? ' instance' : ''),
          title: mapping.kind === 'instance' ? '实例级映射' : '类级映射',
          text: label + (mapping.kind === 'instance' ? '（实例级）' : '')
        }));
      });
      section.appendChild(chips);
      detailBody.appendChild(section);
    }

    const synonyms = asArray(data.synonyms);
    if (synonyms.length) {
      const section = el('div', { class: 'entity-section' }, [sectionTitle('target', '同义词')]);
      const chips = el('div', { class: 'chip-row' });
      synonyms.forEach((word) => {
        chips.appendChild(el('span', { class: 'o-chip purple', text: word }));
      });
      section.appendChild(chips);
      detailBody.appendChild(section);
    }
  }

  function renderInstancesTab(data) {
    const instances = asArray(data.instances);
    const total = Number.isFinite(Number(data.instanceTotal)) ? Number(data.instanceTotal) : instances.length;
    const search = el('input', {
      class: 'qa-input instance-search',
      placeholder: '搜索实例名称或编码...',
      value: state.instanceFilter,
      on: {
        input: (event) => {
          state.instanceFilter = event.target.value;
          renderInstanceList(data);
        }
      }
    });
    detailBody.appendChild(el('div', { class: 'entity-head' }, [
      el('span', { class: 'entity-name', text: data.name || data.code || '' }),
      el('span', { class: 'entity-type', text: '共 ' + fmtNum(total) + ' 条实例' })
    ]));
    detailBody.appendChild(search);
    const listBox = el('div', { class: 'instance-list' });
    detailBody.appendChild(listBox);
    renderInstanceList(data);
  }

  function renderInstanceList(data) {
    const listBox = detailBody.querySelector('.instance-list');
    if (!listBox) {
      return;
    }
    listBox.innerHTML = '';
    const keyword = state.instanceFilter.trim().toLowerCase();
    const instances = asArray(data.instances).filter((item) => {
      if (!keyword) {
        return true;
      }
      return (item.name || '').toLowerCase().indexOf(keyword) >= 0
        || (item.code || '').toLowerCase().indexOf(keyword) >= 0;
    });
    if (!instances.length) {
      listBox.appendChild(el('div', { class: 'state-desc', text: keyword ? '没有匹配的实例' : '该类暂无实例' }));
      return;
    }
    instances.slice(0, INSTANCE_LIST_MAX).forEach((item) => {
      listBox.appendChild(el('button', {
        class: 'instance-item',
        type: 'button',
        on: {
          click: () => {
            select(item.code, {});
          }
        }
      }, [
        el('span', { class: 'instance-name', text: item.name || item.code }),
        el('span', { class: 'instance-code mono', text: item.code || '' })
      ]));
    });
    const total = Number.isFinite(Number(data.instanceTotal)) ? Number(data.instanceTotal) : instances.length;
    listBox.appendChild(el('div', {
      class: 'instance-note',
      text: '本地过滤后 ' + Math.min(instances.length, INSTANCE_LIST_MAX) + ' / ' + instances.length
        + ' 条（接口最多下发 50 条，全量 ' + fmtNum(total) + ' 条）'
    }));
  }

  return {
    destroy: () => {
      // 事件均绑定在随 DOM 移除的元素上，页面级 abort 由 app.js 统一处理
    }
  };
}
