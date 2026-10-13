/* ============================================================
   GPA 排名 / 绩点计算页
   ============================================================ */
import { $, $$, esc, toast, fmtNum, fmtFixed, countUp, api, modal, closeModal } from "./app.js";

let data = null; // { user, ranges, records }
let detailCache = new Map(); // cjfwWid -> rows
let expanded = new Set();
let heroAnimated = false;

export function renderJdfpm() {
  const el = $("#page-jdfpm");
  if (!data) { loadInfo(el); return; }
  render(el);
}

async function loadInfo(el) {
  el.innerHTML = `
    <div class="page-head">
      <div><div class="page-title">GPA 排名</div><div class="page-desc">绩点计算 · 平均分统计 · 成绩证明</div></div>
    </div>
    <div class="card rise" style="height:200px"></div>
    <div style="height:16px"></div>
    <div class="card rise-1" style="height:260px"></div>`;
  try {
    data = await api("/api/jdfpm/info");
    render(el);
  } catch (e) {
    el.innerHTML = `<div class="empty"><div class="icon">⚠️</div><div class="t">加载失败</div><div class="d">${esc(e.message)}</div></div>`;
  }
}

function latestRecord() {
  return data.records.find((r) => r.SFYX) || data.records[0] || null;
}

function render(el) {
  const u = data.user || {};
  const latest = latestRecord();
  const valid = latest && latest.SFYX;
  const recs = [...data.records].sort((a, b) => (a.SQSJ < b.SQSJ ? 1 : -1));

  el.innerHTML = `
    <div class="page-head">
      <div>
        <div class="page-title">GPA 排名</div>
        <div class="page-desc">绩点计算 · 平均分统计 · 成绩证明</div>
      </div>
      <div class="page-actions">
        <button class="btn small" id="btn-refresh">⟳ 刷新</button>
        <button class="btn primary small" id="btn-apply">＋ 新增绩点计算</button>
      </div>
    </div>

    <div class="gpa-grid">
      <div style="display:grid;gap:16px">
        <div class="card rise">
          <div style="font-size:12.5px;font-weight:700;color:var(--text-3);margin-bottom:12px">学生信息</div>
          <div style="display:grid;grid-template-columns:auto 1fr;gap:7px 14px;font-size:13px">
            <span style="color:var(--text-3)">学号</span><b style="font-variant-numeric:tabular-nums">${esc(u.XH || "-")}</b>
            <span style="color:var(--text-3)">姓名</span><b>${esc(u.XM || "-")}</b>
            <span style="color:var(--text-3)">年级</span><span>${esc(u.XZNJ || "-")}</span>
            <span style="color:var(--text-3)">院系</span><span>${esc(u.YXDM || "-")}</span>
            <span style="color:var(--text-3)">专业</span><span>${esc(u.ZYDM || "-")}</span>
            <span style="color:var(--text-3)">班级</span><span>${esc(u.BJDM || "-")}</span>
            <span style="color:var(--text-3)">学籍状态</span><span>${esc(u.XJZT || "-")}</span>
          </div>
        </div>

        ${latest ? `
        <div class="card gpa-hero rise-1">
          <div class="big" id="gpa-big">0</div>
          <div class="cap">${valid ? "当前有效 GPA" : "最新一次计算 GPA"}${latest.ZX?.CJFWMC ? " · " + esc(latest.ZX.CJFWMC) : ""}</div>
          <div class="valid">${valid ? '<span class="badge green">有效期内</span>' : '<span class="badge gray">已失效</span>'} <span class="badge blue">主修</span></div>
          <div class="gpa-tri">
            <div><div class="v" id="t-jq" style="color:var(--blue)">0</div><div class="k">加权平均分</div></div>
            <div><div class="v" id="t-ss" style="color:var(--purple)">0</div><div class="k">算术平均分</div></div>
            <div><div class="v" id="t-ms" style="color:var(--text)">0</div><div class="k">计算门数</div></div>
          </div>
          <div style="margin-top:16px;display:flex;gap:8px;justify-content:center;position:relative">
            <button class="btn small" id="btn-print">🖨 打印绩点证明</button>
            <button class="btn small" id="btn-detail">📋 成绩明细</button>
          </div>
        </div>` : `
        <div class="card empty rise-1"><div class="icon">📊</div><div class="t">还没有绩点计算记录</div><div class="d">点击右上角「新增绩点计算」生成第一份 GPA</div></div>`}
      </div>

      <div style="display:grid;gap:16px;min-width:0">
        ${recs.length > 1 ? `
        <div class="card rise-1">
          <div style="font-size:12.5px;font-weight:700;color:var(--text-3);margin-bottom:6px">GPA 变化趋势</div>
          ${trendChart(recs)}
          <div class="chart-legend">
            <span><i style="background:#818cf8"></i>GPA</span>
            <span><i style="background:#22d3ee"></i>加权平均分 / 25</span>
          </div>
        </div>` : ""}

        <div class="card rise-2" style="padding:14px">
          <div style="font-size:12.5px;font-weight:700;color:var(--text-3);margin:2px 0 10px 4px">申请计算记录 (${recs.length})</div>
          <div class="notice" style="margin-bottom:12px">
            <span class="icon">ℹ️</span>
            <span>根据《厦门大学本科生成绩管理办法》, 同一门课程每次修读的成绩均纳入加权学分绩点计算。现阶段暂不开放绩点排名, 仅提供 GPA 计算查询与开具。</span>
          </div>
          <div style="display:grid;gap:9px">
            ${recs.map((r) => recItemHtml(r)).join("") || '<div class="empty"><div class="t">暂无记录</div></div>'}
          </div>
        </div>
      </div>
    </div>`;

  // 数字动画(仅首次; 重复渲染时直接落值, 避免重新滚动)
  if (latest) {
    const z = latest.ZX || {};
    const set = (id, v, d) => {
      const el = $(id);
      if (el) el.textContent = fmtFixed(Number(v), d);
    };
    if (heroAnimated) {
      set("#gpa-big", Number(z.GPA), 2);
      set("#t-jq", Number(z.JQPJF), 2);
      set("#t-ss", Number(z.SSPJF), 2);
      set("#t-ms", Number(z.CYJSCJMS), 0);
    } else {
      heroAnimated = true;
      requestAnimationFrame(() => {
        countUp($("#gpa-big"), Number(z.GPA), { digits: 2 });
        countUp($("#t-jq"), Number(z.JQPJF), { digits: 2 });
        countUp($("#t-ss"), Number(z.SSPJF), { digits: 2 });
        countUp($("#t-ms"), Number(z.CYJSCJMS), { digits: 0 });
      });
    }
  }

  // 事件
  $("#btn-refresh", el)?.addEventListener("click", async (e) => {
    e.target.disabled = true;
    try { data = await api("/api/jdfpm/info"); detailCache.clear(); expanded.clear(); render(el); toast("已刷新", "ok"); }
    catch (err) { toast(err.message, "err"); }
  });
  $("#btn-apply", el)?.addEventListener("click", showApplyModal);
  $("#btn-print", el)?.addEventListener("click", showPrintModal);
  $("#btn-detail", el)?.addEventListener("click", () => {
    const latest = latestRecord();
    if (!latest) return;
    if (expanded.has(latest.SQJLWID)) {
      expanded.delete(latest.SQJLWID);
      render(el);
    } else {
      expanded.add(latest.SQJLWID);
      render(el);
      loadDetail(latest);
    }
  });
  $$(".rec-head", el).forEach((h) =>
    h.addEventListener("click", (e) => {
      // 记录内的"打印"按钮不触发展开
      const printBtn = e.target.closest("[data-print]");
      if (printBtn) {
        e.stopPropagation();
        showPrintModal(printBtn.dataset.print);
        return;
      }
      const id = h.dataset.id;
      if (expanded.has(id)) expanded.delete(id);
      else { expanded.add(id); loadDetail(data.records.find((r) => r.SQJLWID === id)); }
      render(el);
    })
  );
}

// ---------- 记录项 ----------
function recItemHtml(r) {
  const z = r.ZX || {};
  const open = expanded.has(r.SQJLWID);
  const detail = detailCache.get(z.CJFWWID);
  return `
    <div class="rec-item ${open ? "open" : ""}">
      <div class="rec-head" data-id="${esc(r.SQJLWID)}">
        <span class="arrow"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg></span>
        <div>
          <div class="rt">${esc(r.SQSJ)}</div>
          <div class="rr">${esc(z.CJFWMC || "成绩范围已更新")} · 截止计算日 ${esc(z.JSSJ || "-")}</div>
        </div>
        <div class="rg">
          ${r.SFYX ? '<span class="badge green">有效</span>' : '<span class="badge gray">已失效</span>'}
          <span class="g">${esc(z.GPA ?? "-")}</span>
          <span style="font-size:12px;color:var(--text-3)">${esc(z.CYJSCJMS ?? "-")} 门</span>
          <button class="btn small" data-print="${esc(z.WID)}">打印</button>
        </div>
      </div>
      <div class="rec-body" ${open ? "" : "hidden"}>
        ${detail ? detailTableHtml(detail) : '<div class="skeleton" style="height:90px"></div>'}
      </div>
    </div>`;
}

async function loadDetail(r) {
  if (!r || !r.ZX?.CJFWWID) return;
  const wid = r.ZX.CJFWWID;
  if (detailCache.has(wid)) { render($("#page-jdfpm")); return; }
  try {
    const rows = await api("/api/jdfpm/detail?cjfwWid=" + encodeURIComponent(wid));
    detailCache.set(wid, rows);
  } catch (e) {
    detailCache.set(wid, []);
    toast("成绩明细加载失败: " + e.message, "err");
  }
  render($("#page-jdfpm"));
}

function detailTableHtml(rows) {
  if (!rows.length) return `<div class="empty" style="padding:20px"><div class="t">暂无成绩明细</div></div>`;
  // 按学期分组
  const byTerm = new Map();
  for (const c of rows) {
    const k = c.XNXQDM_DISPLAY || "其他";
    if (!byTerm.has(k)) byTerm.set(k, []);
    byTerm.get(k).push(c);
  }
  const groups = [...byTerm.entries()].sort((a, b) => (a[0] < b[0] ? 1 : -1));
  return `
    <div style="display:grid;gap:12px">
      ${groups.map(([term, list]) => {
        const xf = list.reduce((s, c) => s + (Number(c.XF) || 0), 0);
        return `
        <div>
          <div style="display:flex;align-items:baseline;gap:8px;margin-bottom:6px">
            <b style="font-size:13px">${esc(term)}</b>
            <span style="font-size:11.5px;color:var(--text-3)">${list.length} 门 · ${fmtNum(xf)} 学分</span>
          </div>
          <div class="table-wrap"><table class="data" style="min-width:520px">
            <thead><tr><th>课程</th><th>性质</th><th>学分</th><th>成绩</th><th>绩点</th><th>类型</th></tr></thead>
            <tbody>
              ${list.map((c) => `<tr>
                <td class="course-name">${esc(c.KCM)}</td>
                <td>${c.KCXZDM_DISPLAY ? `<span class="tag">${esc(c.KCXZDM_DISPLAY)}</span>` : "-"}</td>
                <td class="num">${fmtNum(c.XF)}</td>
                <td>${gradeCell(c.ZCJ)}</td>
                <td class="num" style="color:var(--accent);font-weight:700">${esc(c.GPA ?? "-")}</td>
                <td>${c.CXCKDM_DISPLAY && c.CXCKDM_DISPLAY !== "初修" ? `<span class="tag a">${esc(c.CXCKDM_DISPLAY)}</span>` : '<span class="tag n">初修</span>'}</td>
              </tr>`).join("")}
            </tbody>
          </table></div>
        </div>`;
      }).join("")}
    </div>`;
}

function gradeCell(cj) {
  if (cj == null || cj === "") return `<span style="color:var(--text-3)">-</span>`;
  const n = Number(cj);
  if (!Number.isFinite(n)) return `<span class="num">${esc(cj)}</span>`;
  const color = n >= 90 ? "var(--green)" : n >= 80 ? "var(--blue)" : n >= 70 ? "var(--amber)" : n >= 60 ? "var(--text-2)" : "var(--red)";
  return `<span class="num" style="color:${color};font-weight:700">${esc(cj)}</span>`;
}

// ---------- GPA 趋势图(纯 SVG) ----------
function trendChart(recs) {
  const pts = recs
    .filter((r) => r.ZX && Number(r.ZX.GPA) > 0)
    .map((r) => ({ t: r.SQSJ?.slice(0, 10) || "", gpa: Number(r.ZX.GPA), jq: Number(r.ZX.JQPJF) || 0 }))
    .reverse(); // 时间正序
  if (pts.length < 2) {
    return `<div class="chart-box" style="padding:18px;text-align:center;color:var(--text-3);font-size:12.5px">至少需要 2 次有效计算才能绘制趋势</div>`;
  }
  const W = 560, H = 190, P = { l: 34, r: 14, t: 14, b: 26 };
  const iw = W - P.l - P.r, ih = H - P.t - P.b;
  const maxG = Math.ceil(Math.max(...pts.map((p) => p.gpa)) * 2) / 2 + 0.1;
  const minG = Math.floor(Math.min(...pts.map((p) => p.gpa)) * 2) / 2 - 0.1;
  const x = (i) => P.l + (pts.length === 1 ? iw / 2 : (i / (pts.length - 1)) * iw);
  const y = (v) => P.t + ih - ((v - minG) / (maxG - minG)) * ih;
  const yj = (v) => P.t + ih - (v / 25 / (maxG - minG)) * ih;

  const line = (key) => pts.map((p, i) => `${i ? "L" : "M"}${x(i).toFixed(1)},${y(key(p)).toFixed(1)}`).join(" ");
  const area = `${line((p) => p.gpa)} L${x(pts.length - 1)},${P.t + ih} L${x(0)},${P.t + ih} Z`;
  const gridVals = [minG, (minG + maxG) / 2, maxG];

  return `
    <div class="chart-box">
      <svg viewBox="0 0 ${W} ${H}" role="img" aria-label="GPA趋势图">
        <defs>
          <linearGradient id="areaGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#818cf8" stop-opacity="0.32"/>
            <stop offset="100%" stop-color="#818cf8" stop-opacity="0"/>
          </linearGradient>
        </defs>
        ${gridVals.map((v) => `
          <line x1="${P.l}" y1="${y(v)}" x2="${W - P.r}" y2="${y(v)}" stroke="var(--border)" stroke-width="1"/>
          <text x="${P.l - 7}" y="${y(v) + 4}" text-anchor="end" font-size="10" fill="var(--text-3)">${v.toFixed(1)}</text>
        `).join("")}
        <path d="${area}" fill="url(#areaGrad)"/>
        <path d="${line((p) => p.jq)}" fill="none" stroke="#22d3ee" stroke-width="2" stroke-dasharray="1 5" stroke-linecap="round"/>
        <path d="${line((p) => p.gpa)}" fill="none" stroke="#818cf8" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"
          style="filter:drop-shadow(0 2px 6px rgba(129,140,248,.4))"/>
        ${pts.map((p, i) => `
          <g>
            <circle cx="${x(i)}" cy="${y(p.gpa)}" r="4" fill="var(--bg)" stroke="#818cf8" stroke-width="2.4"/>
            <text x="${x(i)}" y="${y(p.gpa) - 9}" text-anchor="middle" font-size="10.5" font-weight="700" fill="var(--text)">${p.gpa.toFixed(2)}</text>
            <text x="${x(i)}" y="${H - 7}" text-anchor="middle" font-size="9.5" fill="var(--text-3)">${esc(p.t.slice(5))}</text>
          </g>
        `).join("")}
      </svg>
    </div>`;
}

// ---------- 新增计算弹窗 ----------
function showApplyModal() {
  const m = modal(`
    <h3>新增绩点计算</h3>
    <div class="field">
      <label>成绩范围</label>
      <select class="input" id="apply-range" style="width:100%">
        ${data.ranges.map((r) => `<option value="${esc(r.WID)}">${esc(r.XSMC)}</option>`).join("")}
      </select>
      <div class="hint">计算范围由教务系统按学籍状态生成, 提交后系统重新计算该范围内的加权学分绩点。</div>
    </div>
    <div class="actions">
      <button class="btn" id="apply-cancel">取消</button>
      <button class="btn primary" id="apply-ok">开始计算</button>
    </div>`);
  $("#apply-cancel", m).addEventListener("click", closeModal);
  $("#apply-ok", m).addEventListener("click", async (e) => {
    const wid = $("#apply-range", m).value;
    e.target.disabled = true; e.target.textContent = "计算中…";
    try {
      data = await api("/api/jdfpm/apply", { method: "POST", body: { cjfwWid: wid } });
      detailCache.clear(); expanded.clear();
      closeModal();
      render($("#page-jdfpm"));
      toast("绩点计算完成", "ok");
    } catch (err) {
      toast(err.message, "err");
      e.target.disabled = false; e.target.textContent = "开始计算";
    }
  });
}

// ---------- 打印弹窗 ----------
function showPrintModal(wid) {
  wid = wid || latestRecord()?.ZX?.WID;
  if (!wid) { toast("没有可打印的记录", "err"); return; }
  const m = modal(`
    <h3>打印绩点证明</h3>
    <div class="field">
      <label>证明类型</label>
      <select class="input" id="p-lx" style="width:100%">
        <option value="1">绩点证明</option>
        <option value="2">成绩证明</option>
      </select>
    </div>
    <div class="field">
      <label>打印内容</label>
      <select class="input" id="p-dynr" style="width:100%">
        <option value="1">中文</option>
        <option value="2">中英文</option>
      </select>
    </div>
    <div class="field">
      <label>是否需要排名</label>
      <select class="input" id="p-sfxypm" style="width:100%">
        <option value="0">不需要</option>
        <option value="1">需要</option>
      </select>
      <div class="hint">现阶段学校暂不开放绩点排名, 选择"需要"可能无法生成排名信息。</div>
    </div>
    <div class="actions">
      <button class="btn" id="p-cancel">取消</button>
      <button class="btn primary" id="p-ok">生成并打开</button>
    </div>`);
  $("#p-cancel", m).addEventListener("click", closeModal);
  $("#p-ok", m).addEventListener("click", () => {
    const q = new URLSearchParams({
      wid, lx: $("#p-lx", m).value,
      dynr: $("#p-dynr", m).value, sfxypm: $("#p-sfxypm", m).value,
    });
    window.open("/api/jdfpm/print?" + q, "_blank");
    closeModal();
  });
}
