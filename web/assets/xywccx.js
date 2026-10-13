/* ============================================================
   学业完成查询页
   ============================================================ */
import { $, $$, esc, toast, fmtNum, fmtFixed, fmtPct, countUp, api } from "./app.js";

// 课程状态: 1=通过 0=未通过 2=未修读 3=待选课 4=已选课
const STATUS = {
  1: { label: "通过", cls: "g" },
  0: { label: "未通过", cls: "r" },
  2: { label: "未修读", cls: "n" },
  3: { label: "待选课", cls: "b" },
  4: { label: "已选课", cls: "a" },
};
const statusInfo = (s) => STATUS[String(s)] || { label: String(s ?? "-"), cls: "n" };

let overview = null;
let view = "tree"; // tree | all
let filters = { status: "all", cat: "all", q: "" };
let heroAnimated = false;

export function renderXywccx() {
  const el = $("#page-xywccx");
  if (!overview) { loadOverview(el); return; }
  if (view === "tree") renderTree(el); else renderAll(el);
}

// ---------- 数据加载 ----------
async function loadOverview(el) {
  el.innerHTML = `
    <div class="page-head">
      <div><div class="page-title">学业完成查询</div><div class="page-desc">培养方案完成情况 · 课程修读进度</div></div>
    </div>
    <div class="card rise" style="height:190px"></div>
    <div style="height:16px"></div>
    <div class="card rise-1" style="height:320px"></div>`;
  try {
    overview = await api("/api/xywccx/overview");
    view = "tree";
    renderTree(el);
  } catch (e) {
    el.innerHTML = `<div class="empty"><div class="icon">⚠️</div><div class="t">加载失败</div><div class="d">${esc(e.message)}</div></div>`;
  }
}

// ---------- 视图切换头部 ----------
function pageHeadHtml() {
  return `
    <div class="page-head">
      <div>
        <div class="page-title">学业完成查询</div>
        <div class="page-desc">培养方案完成情况 · 课程修读进度</div>
      </div>
      <div class="page-actions">
        <div class="seg">
          <button class="${view === "tree" ? "on" : ""}" data-view="tree">按培养方案</button>
          <button class="${view === "all" ? "on" : ""}" data-view="all">全部课程</button>
        </div>
        <button class="btn small" id="btn-refresh" title="重新从教务系统拉取">⟳ 刷新</button>
      </div>
    </div>`;
}

function bindHead(el) {
  $$("[data-view]", el).forEach((b) =>
    b.addEventListener("click", () => { view = b.dataset.view; renderXywccx(); })
  );
  $("#btn-refresh", el)?.addEventListener("click", async (e) => {
    e.target.disabled = true; e.target.textContent = "…";
    overview = null;
    try { overview = await api("/api/xywccx/overview"); } catch (err) { toast(err.message, "err"); }
    renderXywccx();
  });
}

// ---------- 树视图 ----------
function renderTree(el) {
  const { plan, groups, courses, missing } = overview;

  if (!plan) {
    el.innerHTML = pageHeadHtml() + `<div class="empty"><div class="icon">📭</div><div class="t">暂无培养方案数据</div><div class="d">教务系统中没有查询到你的培养方案</div></div>`;
    bindHead(el);
    return;
  }

  const pct = fmtPct(plan.WCXF, plan.YQXF);
  const outside = courses.filter((c) => c.KCLBDM_DISPLAY === "方案外课程");
  const outsideXf = outside.reduce((s, c) => s + (Number(c.XF) || 0), 0);
  const passed = courses.filter((c) => String(c.SFTG) === "1");
  const taking = courses.filter((c) => ["3", "4"].includes(String(c.SFTG)));

  // 构建模块树: FKZH="-1" 为顶级, 其余按 FKZH 挂到父节点(模块/子模块/课组可任意层级)
  const byId = new Map(groups.map((g) => [g.KZH, { ...g, children: [] }]));
  const roots = [];
  for (const g of byId.values()) {
    const parent = g.FKZH && g.FKZH !== "-1" ? byId.get(g.FKZH) : null;
    if (parent) parent.children.push(g);
    else roots.push(g);
  }
  const tree = roots;

  el.innerHTML = pageHeadHtml() + `
    <div class="card rise plan-hero">
      <div class="ring-wrap">
        <svg viewBox="0 0 120 120">
          <defs>
            <linearGradient id="ringGrad" x1="0" y1="0" x2="1" y2="1">
              <stop offset="0%" stop-color="#818cf8"/><stop offset="100%" stop-color="#22d3ee"/>
            </linearGradient>
          </defs>
          <circle class="ring-bg" cx="60" cy="60" r="52" fill="none" stroke-width="10"/>
          <circle class="ring-fg" id="ring" cx="60" cy="60" r="52" fill="none" stroke-width="10"
            stroke-dasharray="${2 * Math.PI * 52}" stroke-dashoffset="${2 * Math.PI * 52}"/>
        </svg>
        <div class="ring-center">
          <div>
            <div class="pct" id="ring-pct">0%</div>
            <div class="lbl">方案完成度</div>
          </div>
        </div>
      </div>
      <div class="plan-info">
        <h2>${esc(plan.PYFAMC)}</h2>
        <div class="plan-meta">统计时间 ${esc(plan.CZSJ || "-")} · 数据由教务系统计算, 仅供自查</div>
        <div class="plan-credits">
          <div class="credit-item"><div class="v g" id="c-got">0</div><div class="k">已获学分</div></div>
          <div class="credit-item"><div class="v" id="c-req">0</div><div class="k">要求学分</div></div>
          <div class="credit-item"><div class="v a" id="c-miss">0</div><div class="k">未修学分</div></div>
          <div class="credit-item"><div class="v b" id="c-out">0</div><div class="k">方案外学分</div></div>
        </div>
      </div>
    </div>

    <div class="stat-chips">
      <div class="stat-chip"><span class="dot" style="background:var(--green)"></span>已通过 <b>${passed.length}</b> 门</div>
      <div class="stat-chip"><span class="dot" style="background:var(--amber)"></span>本学期在修/待选 <b>${taking.length}</b> 门</div>
      <div class="stat-chip"><span class="dot" style="background:var(--text-3)"></span>未修读 <b>${courses.length - passed.length - taking.length}</b> 门</div>
      ${missing ? `<div class="stat-chip"><span class="dot" style="background:var(--red)"></span>方案未修 <b>${esc(missing.FAWMS)}</b> 门 / <b>${esc(missing.FAWXF)}</b> 学分</div>` : ""}
    </div>

    <div class="tree" id="tree"></div>`;

  // 进度环 + 数字(仅首次动画; 重复渲染直接落值)
  const C = 2 * Math.PI * 52;
  const setV = (id, v, d = 1) => {
    const el = $(id);
    if (el) el.textContent = fmtFixed(Number(v), d);
  };
  if (heroAnimated) {
    $("#ring").style.strokeDashoffset = C * (1 - Math.min(1, plan.WCXF / plan.YQXF));
    setV("#ring-pct", Number(pct));
    setV("#c-got", plan.WCXF); setV("#c-req", plan.YQXF);
    setV("#c-miss", Math.max(0, plan.YQXF - plan.WCXF)); setV("#c-out", outsideXf);
  } else {
    heroAnimated = true;
    requestAnimationFrame(() => {
      $("#ring").style.strokeDashoffset = C * (1 - Math.min(1, plan.WCXF / plan.YQXF));
      countUp($("#ring-pct"), Number(pct), { digits: 1, suffix: "%" });
      countUp($("#c-got"), plan.WCXF, { digits: 1 });
      countUp($("#c-req"), plan.YQXF, { digits: 1 });
      countUp($("#c-miss"), Math.max(0, plan.YQXF - plan.WCXF), { digits: 1 });
      countUp($("#c-out"), outsideXf, { digits: 1 });
    });
  }

  // 模块树
  const treeEl = $("#tree", el);
  const buildNode = (g, lvl) => {
    const kids = g.children || [];
    const done = String(g.SFTG) === "1";
    const p = fmtPct(Number(g.WCXF), Number(g.YQXF));
    return `
      <div class="tnode lvl${Math.min(lvl, 2)}" data-kzh="${esc(g.KZH)}">
        <div class="tnode-head">
          <span class="arrow"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg></span>
          <span class="tname">${esc(g.KZM)}${kids.length ? ` <small>${kids.length} 个子项</small>` : ""}</span>
          ${done ? '<span class="badge green">已通过</span>' : '<span class="badge amber">未完成</span>'}
          <span class="tnum">要求 <b>${fmtNum(g.YQXF)}</b> · 已修 <b>${fmtNum(g.WCXF)}</b> · <b>${p}%</b></span>
        </div>
        <div class="tnode-body" hidden>
          <div style="padding:8px 16px 4px 40px"><div class="pbar ${done ? "done" : Number(g.WCXF) < Number(g.YQXF) ? "warn" : ""}"><i data-w="${p}"></i></div></div>
          ${kids.length ? `<div style="padding:4px 0">${kids.map((k) => buildNode(k, lvl + 1)).join("")}</div>` : ""}
          ${courseTableHtml(g.KZH)}
        </div>
      </div>`;
  };

  treeEl.innerHTML = tree.map((m) => buildNode(m, 1)).join("");

  // 展开/收起 + 进度条动画
  $$(".tnode-head", treeEl).forEach((h) =>
    h.addEventListener("click", () => {
      const node = h.parentElement;
      node.classList.toggle("open");
      $(".tnode-body", node).hidden = !node.classList.contains("open");
    })
  );
  setTimeout(() => $$(".pbar > i", treeEl).forEach((i) => (i.style.width = i.dataset.w + "%")), 60);

  bindHead(el);
}

/** 某个课组下的课程表(按状态排序: 在修>未通过>通过>待选>未修读) */
function courseTableHtml(kzh) {
  const list = overview.courses
    .filter((c) => c.KZH === kzh)
    .sort((a, b) => statusOrder(a.SFTG) - statusOrder(b.SFTG) || (Number(b.XF) || 0) - (Number(a.XF) || 0));
  if (!list.length) return "";
  return `<div class="course-list">${list.map(courseRowHtml).join("")}</div>`;
}

function statusOrder(s) {
  return { "4": 0, "3": 1, "0": 2, "1": 3, "2": 4 }[String(s)] ?? 9;
}

function courseRowHtml(c) {
  const st = statusInfo(c.SFTG);
  const score = gradeHtml(c);
  return `
    <div class="course-row">
      <div class="cname"><span class="code">${esc(c.KCH)}</span>${esc(c.KCM)}</div>
      ${score}
      <span class="tag ${st.cls}">${st.label}</span>
      <div class="cmeta">
        <span class="tag">${fmtNum(c.XF)} 学分</span>
        ${c.XNXQDM_DISPLAY ? `<span class="tag b">${esc(c.XNXQDM_DISPLAY)}</span>` : ""}
        ${c.KCXZDM_DISPLAY ? `<span class="tag">${esc(c.KCXZDM_DISPLAY)}</span>` : ""}
        ${c.BQDM_DISPLAY ? `<span class="tag p">${esc(c.BQDM_DISPLAY)}</span>` : ""}
        ${c.XS ? `<span class="tag n">${esc(c.XS)} 学时</span>` : ""}
      </div>
    </div>`;
}

/** 成绩展示: 数值成绩按分段着色, 等级成绩中性 */
function gradeHtml(c) {
  const cj = c.CJ;
  if (cj == null || cj === "") return `<span class="cscore" style="color:var(--text-3)">-</span>`;
  const n = Number(cj);
  if (!Number.isFinite(n)) return `<span class="cscore" style="color:var(--text-2)">${esc(cj)}</span>`;
  const color = n >= 90 ? "var(--green)" : n >= 80 ? "var(--blue)" : n >= 70 ? "var(--amber)" : n >= 60 ? "var(--text-2)" : "var(--red)";
  return `<span class="cscore" style="color:${color}">${esc(cj)}</span>`;
}

// ---------- 全部课程视图 ----------
function renderAll(el) {
  const cats = [...new Set(overview.courses.map((c) => c.KCLBDM_DISPLAY).filter(Boolean))];
  el.innerHTML = pageHeadHtml() + `
    <div class="card rise">
      <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-bottom:14px">
        <div class="seg" id="status-seg">
          <button class="${filters.status === "all" ? "on" : ""}" data-s="all">全部 <span class="cnt">${overview.courses.length}</span></button>
          <button class="${filters.status === "1" ? "on" : ""}" data-s="1">通过 <span class="cnt">${overview.courses.filter((c) => String(c.SFTG) === "1").length}</span></button>
          <button class="${filters.status === "4" ? "on" : ""}" data-s="4">在修 <span class="cnt">${overview.courses.filter((c) => ["3", "4"].includes(String(c.SFTG))).length}</span></button>
          <button class="${filters.status === "0" ? "on" : ""}" data-s="0">未通过 <span class="cnt">${overview.courses.filter((c) => String(c.SFTG) === "0").length}</span></button>
          <button class="${filters.status === "2" ? "on" : ""}" data-s="2">未修读 <span class="cnt">${overview.courses.filter((c) => String(c.SFTG) === "2").length}</span></button>
        </div>
        <select class="input" id="cat-sel" style="margin-left:auto">
          <option value="all">全部类别</option>
          ${cats.map((c) => `<option value="${esc(c)}" ${filters.cat === c ? "selected" : ""}>${esc(c)}</option>`).join("")}
        </select>
        <input class="input" id="q-input" placeholder="搜索课程名 / 课程号" value="${esc(filters.q)}" style="width:190px" />
      </div>
      <div class="table-wrap" style="max-height:62vh;overflow-y:auto">
        <table class="data">
          <thead><tr>
            <th>课程</th><th>类别</th><th>性质</th><th>学分</th><th>成绩</th><th>状态</th><th>修读学期</th>
          </tr></thead>
          <tbody id="all-tbody"></tbody>
        </table>
      </div>
    </div>`;

  const filtered = overview.courses.filter((c) => {
    if (filters.status === "4" ? !["3", "4"].includes(String(c.SFTG)) : filters.status !== "all" && String(c.SFTG) !== filters.status) return false;
    if (filters.cat !== "all" && c.KCLBDM_DISPLAY !== filters.cat) return false;
    if (filters.q) {
      const q = filters.q.toLowerCase();
      if (!String(c.KCM).toLowerCase().includes(q) && !String(c.KCH).toLowerCase().includes(q)) return false;
    }
    return true;
  });

  $("#all-tbody", el).innerHTML = filtered.length
    ? filtered.map((c) => {
        const st = statusInfo(c.SFTG);
        return `<tr>
          <td class="course-name">${esc(c.KCM)}<div style="font-size:11px;color:var(--text-3);font-family:var(--mono)">${esc(c.KCH)}</div></td>
          <td>${c.KCLBDM_DISPLAY ? `<span class="tag">${esc(c.KCLBDM_DISPLAY)}</span>` : "-"}</td>
          <td>${c.KCXZDM_DISPLAY ? `<span class="tag">${esc(c.KCXZDM_DISPLAY)}</span>` : "-"}</td>
          <td class="num">${fmtNum(c.XF)}</td>
          <td>${gradeHtml(c)}</td>
          <td><span class="tag ${st.cls}">${st.label}</span></td>
          <td style="color:var(--text-2)">${esc(c.XNXQDM_DISPLAY || "-")}</td>
        </tr>`;
      }).join("")
    : `<tr><td colspan="7"><div class="empty"><div class="icon">🔍</div><div class="t">没有匹配的课程</div></div></td></tr>`;

  $$("#status-seg button", el).forEach((b) =>
    b.addEventListener("click", () => { filters.status = b.dataset.s; renderAll(el); })
  );
  $("#cat-sel", el).addEventListener("change", (e) => { filters.cat = e.target.value; renderAll(el); });
  $("#q-input", el).addEventListener("input", (e) => {
    filters.q = e.target.value;
    // 保留焦点与光标
    const pos = e.target.selectionStart;
    renderAll(el);
    const input = $("#q-input", el);
    input.focus();
    input.setSelectionRange(pos, pos);
  });

  bindHead(el);
}
