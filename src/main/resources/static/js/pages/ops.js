// OntoQuery 本体运维页 · 作者：月夜烛峰
// 实例/同义词/映射编辑 -> 保存自动热加载与本体侧基准校验 -> 变更可回退；覆盖缺口扫描。
// 动态文本一律经 el() 的 text 通道输出，禁止 innerHTML 拼接用户数据；全站无 emoji。
import { el, asArray } from '../util.js';
import { icon } from '../icons.js';
import {
  adminInstances, adminCreateInstance, adminUpdateInstance, adminDeleteInstance,
  adminAddSynonym, adminDeleteSynonym, adminUpdateMapping, adminReload,
  adminChanges, adminRevert, adminCoverage, ontologyTree
} from '../api.js';

/** 实例类展示顺序（临床实体优先，与本体图谱页一致） */
const CLASS_ORDER = ['CLS_DISEASE', 'CLS_DRUG', 'CLS_LAB_TEST', 'CLS_DEPARTMENT'];

const KIND_TEXT = { instance: '实例', synonym: '同义词', mapping: '映射' };
const OP_TEXT = { create: '新建', update: '修改', delete: '删除', revert: '回退' };

/** 实例可挂载的类树根：临床实体子树（分组类与就诊/诊断等事实镜像类不收实例） */
const INSTANCE_ROOT_CLASS = 'CLS_ENTITY_GROUP';

/** 递归展平类树为 [{code, name}]；类来自 ont_class 本身，空类也在列，树序即展示序 */
function flattenClasses(nodes, result) {
  asArray(nodes).forEach((node) => {
    if (node.code) {
      result.push({ code: node.code, name: node.name || node.code });
    }
    flattenClasses(node.children, result);
  });
  return result;
}

export function mount(root, ctx) {
  const state = {
    keyword: '',
    instances: [],
    selectedCode: null,
    validation: null,
    changes: [],
    coverage: null,
    busy: false
  };

  const listWrap = el('div', { class: 'ops-left' });
  const editorCard = el('div', { class: 'ops-card' });
  const validationCard = el('div', { class: 'ops-card' });
  const changesCard = el('div', { class: 'ops-card' });
  const coverageCard = el('div', { class: 'ops-card' });

  const searchInput = el('input', {
    class: 'ops-search-input', type: 'text', placeholder: '搜索实例编码 / 名称 / 同义词'
  });
  searchInput.addEventListener('input', () => {
    state.keyword = searchInput.value.trim();
    renderList();
  });

  root.appendChild(el('div', { class: 'ops-page' }, [
    el('div', { class: 'ops-toolbar' }, [
      el('div', { class: 'ops-search' }, [
        el('span', { class: 'ops-search-ic', html: icon('search', 14) }),
        searchInput
      ]),
      el('button', {
        class: 'btn', type: 'button',
        on: { click: () => openCreateForm() }
      }, [el('span', { text: '新建实例' })]),
      el('button', {
        class: 'btn', type: 'button',
        on: { click: () => doReload() }
      }, [el('span', { text: '手动热加载' })]),
      el('button', {
        class: 'btn primary', type: 'button',
        on: { click: () => refreshInstances() }
      }, [el('span', { text: '刷新列表' })])
    ]),
    el('div', { class: 'ops-body' }, [
      listWrap,
      el('div', { class: 'ops-right' }, [editorCard, validationCard, changesCard, coverageCard])
    ])
  ]));

  renderEditor();
  renderValidation();
  renderChanges();
  renderCoverage();
  refreshInstances();
  refreshChanges();

  return { destroy() { /* 页面节点由路由统一清空，无额外资源需要释放 */ } };

  // ---------------- 实例列表 ----------------

  async function refreshInstances() {
    try {
      state.instances = asArray(await adminInstances(null, ctx.signal));
    } catch (err) {
      if (!err.aborted) {
        ctx.toast(err.message || '实例列表加载失败', 'error');
      }
      state.instances = [];
    }
    renderList();
    renderEditor();
  }

  function renderList() {
    listWrap.innerHTML = '';
    const keyword = state.keyword.toLowerCase();
    const match = (item) => !keyword
      || String(item.code).toLowerCase().indexOf(keyword) >= 0
      || String(item.nameCn).toLowerCase().indexOf(keyword) >= 0
      || asArray(item.synonyms).some((term) => String(term).toLowerCase().indexOf(keyword) >= 0);
    const groups = new Map();
    state.instances.forEach((item) => {
      if (!match(item)) {
        return;
      }
      const key = item.classCode || '其他';
      if (!groups.has(key)) {
        groups.set(key, []);
      }
      groups.get(key).push(item);
    });
    const keys = Array.from(groups.keys()).sort((a, b) => {
      const ia = CLASS_ORDER.indexOf(a);
      const ib = CLASS_ORDER.indexOf(b);
      return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib) || a.localeCompare(b);
    });
    if (!keys.length) {
      listWrap.appendChild(el('div', { class: 'ops-empty', text: keyword ? '无匹配实例' : '暂无实例' }));
      return;
    }
    keys.forEach((classCode) => {
      const items = groups.get(classCode);
      listWrap.appendChild(el('div', { class: 'ops-group-title' }, [
        el('span', { text: (items[0].className || classCode) + ' · ' + classCode }),
        el('span', { class: 'ops-group-count', text: String(items.length) })
      ]));
      items.forEach((item) => {
        listWrap.appendChild(el('button', {
          class: 'ops-item' + (state.selectedCode === item.code ? ' active' : ''),
          type: 'button',
          on: { click: () => select(item.code) }
        }, [
          el('span', { class: 'ops-item-name', text: item.nameCn }),
          el('span', { class: 'ops-code', text: item.code }),
          item.protected ? el('span', { class: 'ops-badge', text: '核心保护' }) : null
        ]));
      });
    });
  }

  function select(code) {
    state.selectedCode = code;
    renderList();
    renderEditor();
  }

  function selected() {
    return state.instances.find((item) => item.code === state.selectedCode) || null;
  }

  // ---------------- 实例详情（可编辑） ----------------

  function renderEditor() {
    const item = selected();
    editorCard.innerHTML = '';
    editorCard.appendChild(el('div', { class: 'ops-card-title' }, [
      el('span', { text: '实例详情' }),
      item ? el('span', { class: 'ops-code', text: item.code }) : null
    ]));
    if (!item) {
      editorCard.appendChild(el('div', { class: 'ops-empty', text: '选择左侧实例查看详情' }));
      return;
    }
    if (item.protected) {
      editorCard.appendChild(el('div', {
        class: 'ops-protected-note',
        text: '核心保护实例：演示口径依赖，改名/删除/改映射被拒绝；加同义词放行（新写法上线主场景）'
      }));
    }

    // 基本信息
    const nameInput = el('input', {
      class: 'ops-input', type: 'text', value: item.nameCn, disabled: Boolean(item.protected)
    });
    const remarkInput = el('input', {
      class: 'ops-input', type: 'text', value: item.remark || '', disabled: Boolean(item.protected)
    });
    editorCard.appendChild(el('div', { class: 'ops-form' }, [
      el('label', { class: 'ops-label', text: '名称' }), nameInput,
      el('label', { class: 'ops-label', text: '备注' }), remarkInput,
      el('button', {
        class: 'btn btn-sm', type: 'button', disabled: Boolean(item.protected),
        on: { click: () => saveInstance(item, nameInput.value, remarkInput.value) }
      }, [el('span', { text: '保存基本信息' })])
    ]));

    // 同义词胶囊
    const termInput = el('input', {
      class: 'ops-input ops-term-input', type: 'text', placeholder: '新同义词条'
    });
    editorCard.appendChild(el('div', { class: 'ops-section' }, [
      el('div', { class: 'ops-label', text: '同义词' }),
      el('div', { class: 'ops-chip-wrap' },
        asArray(item.synonyms).map((term) => el('span', { class: 'ops-chip' }, [
          el('span', { text: term }),
          el('button', {
            class: 'ops-chip-x', type: 'button', title: '删除该同义词', html: icon('close', 10),
            on: { click: () => removeTerm(term) }
          })
        ]))),
      el('div', { class: 'ops-inline-form' }, [
        termInput,
        el('button', {
          class: 'btn btn-sm', type: 'button',
          on: { click: () => addTerm(item, termInput) }
        }, [el('span', { text: '添加同义词' })])
      ])
    ]));

    // 物理映射
    const mapping = item.mapping || { table: '', column: '', valueExpr: '' };
    const tableInput = el('input', {
      class: 'ops-input', type: 'text', value: mapping.table, disabled: Boolean(item.protected)
    });
    const columnInput = el('input', {
      class: 'ops-input', type: 'text', value: mapping.column, disabled: Boolean(item.protected)
    });
    const exprInput = el('input', {
      class: 'ops-input', type: 'text', value: mapping.valueExpr, disabled: Boolean(item.protected),
      placeholder: "形如 {t}.drug_code = 'D_XXX'"
    });
    editorCard.appendChild(el('div', { class: 'ops-section' }, [
      el('div', { class: 'ops-label', text: '物理映射' }),
      el('div', { class: 'ops-form' }, [
        el('label', { class: 'ops-label', text: '表' }), tableInput,
        el('label', { class: 'ops-label', text: '列' }), columnInput,
        el('label', { class: 'ops-label', text: '取值表达式' }), exprInput,
        el('button', {
          class: 'btn btn-sm', type: 'button', disabled: Boolean(item.protected),
          on: { click: () => saveMapping(item, tableInput.value, columnInput.value, exprInput.value) }
        }, [el('span', { text: '保存映射' })])
      ])
    ]));

    // 删除
    editorCard.appendChild(el('div', { class: 'ops-section' }, [
      el('button', {
        class: 'btn btn-sm danger', type: 'button', disabled: Boolean(item.protected),
        on: { click: () => confirmDelete(item) }
      }, [el('span', { text: '删除该实例（级联删同义词与映射）' })])
    ]));
  }

  // ---------------- 手动热加载 ----------------

  async function doReload() {
    if (state.busy) {
      return;
    }
    state.busy = true;
    try {
      await adminReload(ctx.signal);
      ctx.toast('热加载完成，本体与管线已刷新', 'ok');
    } catch (err) {
      if (!err.aborted) {
        ctx.toast(err.message || '热加载失败', 'error');
      }
    }
    state.busy = false;
  }

  // ---------------- 变更验证卡 ----------------

  function renderValidation() {
    validationCard.innerHTML = '';
    validationCard.appendChild(el('div', { class: 'ops-card-title' }, [
      el('span', { text: '变更验证' }),
      el('span', { class: 'ops-muted', text: '本体侧 36 题内存比对' })
    ]));
    const v = state.validation;
    if (!v) {
      validationCard.appendChild(el('div', { class: 'ops-empty', text: '保存一次变更后，这里显示基准前后对比' }));
      return;
    }
    const regressed = asArray(v.regressed);
    const improved = asArray(v.improved);
    const changed = Number(v.beforeAccuracy) !== Number(v.afterAccuracy)
      || asArray(v.beforeFailed).length !== asArray(v.afterFailed).length;
    validationCard.appendChild(el('div', { class: 'ops-acc-row' }, [
      accBlock('变更前', v.beforeAccuracy),
      el('span', { class: 'ops-acc-arrow', html: icon('chevron-right', 16) }),
      accBlock('变更后', v.afterAccuracy)
    ]));
    validationCard.appendChild(el('div', { class: 'ops-diff-list' }, [
      el('div', {
        class: 'ops-diff' + (regressed.length ? ' regress' : ''),
        text: regressed.length ? '回归题号：' + regressed.join('、') : '无回归题'
      }),
      el('div', {
        class: 'ops-diff' + (improved.length ? ' improve' : ''),
        text: improved.length ? '提升题号：' + improved.join('、') : '无提升题'
      }),
      el('div', { class: 'ops-muted', text: changed ? '基准结果已变化，是否回退由你决定' : '基准无变化' })
    ]));
  }

  function accBlock(label, accuracy) {
    return el('div', { class: 'ops-acc' }, [
      el('div', { class: 'ops-acc-label', text: label }),
      el('div', { class: 'ops-acc-num', text: accuracy === null || accuracy === undefined ? '--' : String(accuracy) }),
      el('div', { class: 'ops-acc-unit', text: '分' })
    ]);
  }

  // ---------------- 写操作编排 ----------------

  async function applyChange(action, okMessage) {
    if (state.busy) {
      return;
    }
    state.busy = true;
    try {
      const result = await action();
      state.validation = result && result.validation ? result.validation : null;
      renderValidation();
      ctx.toast(okMessage + '（changeset=' + (result ? result.changesetId : '-') + '）', 'ok');
      await refreshInstances();
      await refreshChanges();
    } catch (err) {
      if (!err.aborted) {
        ctx.toast(err.message || '操作失败', 'error');
        if (err.code === 'B0502') {
          // 数据已提交但热加载失败：刷新列表与历史反映真实库状态
          await refreshInstances();
          await refreshChanges();
        }
      }
    }
    state.busy = false;
  }

  function saveInstance(item, nameCn, remark) {
    applyChange(() => adminUpdateInstance({
      code: item.code, nameCn: nameCn, remark: remark
    }, ctx.signal), '已保存');
  }

  function addTerm(item, termInput) {
    const term = termInput.value.trim();
    if (!term) {
      ctx.toast('请输入同义词条', 'warn');
      return;
    }
    const task = applyChange(() => adminAddSynonym(term, item.code, ctx.signal), '同义词已添加');
    task.then(() => {
      termInput.value = '';
    });
  }

  function removeTerm(term) {
    applyChange(() => adminDeleteSynonym(term, ctx.signal), '同义词已删除');
  }

  function saveMapping(item, table, column, valueExpr) {
    applyChange(() => adminUpdateMapping({
      instanceCode: item.code,
      table: table.trim(),
      column: column.trim(),
      valueExpr: valueExpr.trim()
    }, ctx.signal), '映射已保存');
  }

  function confirmDelete(item) {
    ctx.modal({
      title: '删除实例',
      body: el('div', { class: 'ops-modal-text' }, [
        el('p', { text: '将删除实例及其全部同义词与物理映射：' }),
        el('p', { class: 'ops-modal-strong', text: item.nameCn + '（' + item.code + '）' }),
        el('p', { class: 'ops-muted', text: '删除后可通过变更历史回退恢复。' })
      ]),
      actions: [
        { label: '取消' },
        {
          label: '确认删除', primary: true,
          onClick: () => {
            const task = applyChange(() => adminDeleteInstance(item.code, ctx.signal), '实例已删除');
            task.then(() => {
              if (state.selectedCode === item.code) {
                state.selectedCode = null;
                renderEditor();
              }
            });
          }
        }
      ]
    });
  }

  // ---------------- 新建实例弹层 ----------------

  async function openCreateForm() {
    let classes = [];
    try {
      const tree = asArray(await ontologyTree(ctx.signal));
      const root = tree.find((node) => node.code === INSTANCE_ROOT_CLASS);
      if (root) {
        classes = flattenClasses(root.children, []);
      }
    } catch (err) {
      ctx.toast('类树加载失败，下拉退化为现有实例的类', 'warn');
    }
    if (!classes.length) {
      const seen = new Set();
      state.instances.forEach((item) => {
        if (!seen.has(item.classCode)) {
          seen.add(item.classCode);
          classes.push({ code: item.classCode, name: item.className });
        }
      });
    }
    const classSelect = el('select', { class: 'ops-input' },
      classes.map((cls) => el('option', { value: cls.code, text: cls.name + ' ' + cls.code })));
    const codeInput = el('input', { class: 'ops-input', type: 'text', placeholder: '如 D_NEW_DRUG（字母数字下划线）' });
    const nameInput = el('input', { class: 'ops-input', type: 'text', placeholder: '标准名称（如 新降糖药）' });
    const remarkInput = el('input', { class: 'ops-input', type: 'text', placeholder: '备注（可选）' });
    const synonymsInput = el('input', { class: 'ops-input', type: 'text', placeholder: '同义词，逗号分隔（可选）' });
    const tableInput = el('input', { class: 'ops-input', type: 'text', placeholder: '如 fact_medication（可选）' });
    const columnInput = el('input', { class: 'ops-input', type: 'text', placeholder: '如 drug_code（可选）' });
    const exprInput = el('input', {
      class: 'ops-input', type: 'text', placeholder: "如 {t}.drug_code = 'D_NEW_DRUG'（可选）"
    });
    ctx.modal({
      title: '新建实例',
      body: el('div', { class: 'ops-form' }, [
        el('label', { class: 'ops-label', text: '归属类' }), classSelect,
        el('label', { class: 'ops-label', text: '编码' }), codeInput,
        el('label', { class: 'ops-label', text: '标准名称' }), nameInput,
        el('label', { class: 'ops-label', text: '备注' }), remarkInput,
        el('label', { class: 'ops-label', text: '同义词' }), synonymsInput,
        el('label', { class: 'ops-label', text: '映射表' }), tableInput,
        el('label', { class: 'ops-label', text: '映射列' }), columnInput,
        el('label', { class: 'ops-label', text: '取值表达式' }), exprInput
      ]),
      actions: [
        { label: '取消' },
        { label: '创建并验证', primary: true, onClick: () => submitCreate() }
      ]
    });

    function submitCreate() {
      const code = codeInput.value.trim();
      const nameCn = nameInput.value.trim();
      if (!code || !nameCn) {
        ctx.toast('编码与标准名称必填', 'warn');
        return;
      }
      const synonyms = synonymsInput.value.split(/[,，]/)
        .map((term) => term.trim()).filter((term) => term.length > 0);
      const body = {
        classCode: classSelect.value,
        code: code,
        nameCn: nameCn,
        remark: remarkInput.value.trim()
      };
      if (synonyms.length) {
        body.synonyms = synonyms;
      }
      const hasTable = tableInput.value.trim().length > 0;
      const hasColumn = columnInput.value.trim().length > 0;
      const hasExpr = exprInput.value.trim().length > 0;
      if (hasTable && hasColumn && hasExpr) {
        body.mapping = {
          table: tableInput.value.trim(),
          column: columnInput.value.trim(),
          valueExpr: exprInput.value.trim()
        };
      } else if (hasTable || hasColumn || hasExpr) {
        ctx.toast('映射三字段需同时填写', 'warn');
        return;
      }
      applyChange(() => adminCreateInstance(body, ctx.signal), '实例已创建');
    }
  }

  // ---------------- 变更历史卡（回退按钮 Task 16 追加） ----------------

  async function refreshChanges() {
    try {
      state.changes = asArray(await adminChanges(ctx.signal));
    } catch (err) {
      if (!err.aborted) {
        ctx.toast(err.message || '变更历史加载失败', 'error');
      }
      state.changes = [];
    }
    renderChanges();
  }

  function renderChanges() {
    changesCard.innerHTML = '';
    changesCard.appendChild(el('div', { class: 'ops-card-title' }, [
      el('span', { text: '变更历史' }),
      el('button', {
        class: 'btn btn-sm', type: 'button',
        on: { click: () => refreshChanges() }
      }, [el('span', { text: '刷新' })])
    ]));
    if (!state.changes.length) {
      changesCard.appendChild(el('div', { class: 'ops-empty', text: '暂无变更记录' }));
      return;
    }
    // 仅当最新一个变更集非回退集时提供回退入口（与后端 A0410 语义一致：只回退最近一次动作）
    const newest = state.changes[0];
    const revertTarget = newest && !newest.isRevert ? newest : null;
    state.changes.forEach((set) => {
      changesCard.appendChild(el('div', { class: 'ops-change-item' }, [
        el('div', { class: 'ops-change-head' }, [
          el('span', { class: 'ops-change-id', text: '#' + set.changesetId }),
          set.isRevert ? el('span', { class: 'ops-badge revert', text: '回退集' }) : null,
          el('span', { class: 'ops-muted', text: String(set.createTime || '') })
        ]),
        el('div', { class: 'ops-change-ops' },
          asArray(set.ops).map((op) => el('span', { class: 'ops-chip', text: opLabel(op) }))),
        (!set.isRevert && set === revertTarget)
          ? el('button', {
              class: 'btn btn-sm danger', type: 'button',
              on: { click: () => confirmRevert(set) }
            }, [el('span', { text: '回退此变更' })])
          : null
      ]));
    });
  }

  function confirmRevert(set) {
    ctx.modal({
      title: '回退变更 #' + set.changesetId,
      body: el('div', { class: 'ops-modal-text' }, [
        el('p', { text: '将按操作前镜像逆序恢复以下变更：' }),
        el('div', { class: 'ops-change-ops' },
          asArray(set.ops).map((op) => el('span', { class: 'ops-chip', text: opLabel(op) }))),
        el('p', { class: 'ops-muted', text: '回退本身会记录为新的回退变更集；最新变更已是回退时不可连续回退。' })
      ]),
      actions: [
        { label: '取消' },
        { label: '确认回退', primary: true, onClick: () => applyChange(() => adminRevert(ctx.signal), '已回退') }
      ]
    });
  }

  function opLabel(op) {
    const kind = KIND_TEXT[op.targetKind] || op.targetKind;
    const action = OP_TEXT[op.opType] || op.opType;
    return action + ' ' + kind + ' ' + op.targetCode;
  }

  // ---------------- 覆盖缺口卡 ----------------

  function renderCoverage() {
    coverageCard.innerHTML = '';
    coverageCard.appendChild(el('div', { class: 'ops-card-title' }, [
      el('span', { text: '覆盖缺口扫描' }),
      el('button', {
        class: 'btn btn-sm', type: 'button',
        on: { click: () => refreshCoverage() }
      }, [el('span', { text: state.coverage ? '重新扫描' : '扫描' })])
    ]));
    if (!state.coverage) {
      coverageCard.appendChild(el('div', { class: 'ops-empty', text: '扫描数据中的名称写法，检查本体是否收录' }));
      return;
    }
    const columns = asArray(state.coverage.columns);
    if (!columns.length) {
      coverageCard.appendChild(el('div', { class: 'ops-empty', text: '无扫描结果' }));
      return;
    }
    columns.forEach((col) => {
      const unmapped = asArray(col.unmapped);
      coverageCard.appendChild(el('div', { class: 'ops-coverage-col' }, [
        el('div', { class: 'ops-coverage-head', text: col.table + '.' + col.column + ' -> ' + col.classCode }),
        unmapped.length
          ? el('div', { class: 'ops-coverage-rows' }, unmapped.map((row) => el('div', { class: 'ops-coverage-row' }, [
              el('span', { class: 'ops-coverage-term', text: row.term }),
              el('span', { class: 'ops-muted', text: row.rowCount + ' 行' }),
              el('button', {
                class: 'btn btn-sm', type: 'button',
                on: { click: () => openCollect(col, row.term) }
              }, [el('span', { text: '收录为同义词' })])
            ])))
          : el('div', { class: 'ops-empty', text: '全部写法已收录' })
      ]));
    });
  }

  async function refreshCoverage() {
    try {
      state.coverage = await adminCoverage(ctx.signal);
      renderCoverage();
    } catch (err) {
      if (!err.aborted) {
        ctx.toast(err.message || '覆盖扫描失败', 'error');
      }
    }
  }

  /** 收录弹层：把未收录写法挂为某实例同义词（预填写法，实例下拉按类集合可选） */
  function openCollect(col, term) {
    const codes = asArray(col.classCodes);
    const classSet = codes.length ? codes : [col.classCode];
    const candidates = state.instances.filter((item) => classSet.indexOf(item.classCode) >= 0);
    if (!candidates.length) {
      ctx.toast('该类暂无实例可挂载同义词', 'warn');
      return;
    }
    const select = el('select', { class: 'ops-input' },
      candidates.map((item) => el('option', { value: item.code, text: item.nameCn + '（' + item.code + '）' })));
    ctx.modal({
      title: '收录写法',
      body: el('div', { class: 'ops-form' }, [
        el('label', { class: 'ops-label', text: '写法' }),
        el('div', { class: 'ops-modal-strong', text: term }),
        el('label', { class: 'ops-label', text: '挂载到实例' }),
        select
      ]),
      actions: [
        { label: '取消' },
        {
          label: '收录并验证', primary: true,
          onClick: () => {
            const task = applyChange(() => adminAddSynonym(term, select.value, ctx.signal), '已收录');
            task.then(() => refreshCoverage());
          }
        }
      ]
    });
  }
}
