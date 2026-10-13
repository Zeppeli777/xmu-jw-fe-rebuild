/* ============================================================
   证明文件申请页
   ============================================================ */
import { $, $$, esc, toast, fmtNum, api, modal, closeModal } from "./app.js";

let apps = null; // 我的申请
let avail = null; // 可申请证明
let tab = "mine"; // mine | avail
let filters = { status: "all", q: "" };

export function renderZmsq() {
  const el = $("#page-zmsq");
  if (apps === null || avail === null) { loadData(el); return; }
  render(el);
}

async function loadData(el) {
  el.innerHTML = `
    <div class="page-head">
      <div><div class="page-title">证明文件申请</div><div class="page-desc">在学证明 · 成绩证明 · 绩点证明 在线申请与下载</div></div>
    </div>
    <div class="card rise" style="height:200px"></div>`;
  try {
    [apps, avail] = await Promise.all([
      api("/api/zmsq/applications"),
      api("/api/zmsq/available"),
    ]);
    tab = "mine";
    render(el);
  } catch (e) {
    el.innerHTML = `<div class="empty"><div class="icon">⚠️</div><div class="t">加载失败</div><div class="d">${esc(e.message)}</div></div>`;
  }
}

function headHtml() {
  return `
    <div class="page-head">
      <div>
        <div class="page-title">证明文件申请</div>
        <div class="page-desc">在学证明 · 成绩证明 · 绩点证明 在线申请与下载</div>
      </div>
      <div class="page-actions">
        <button class="btn small" id="btn-refresh">⟳ 刷新</button>
        <button class="btn primary small" id="btn-new-apply">＋ 在线申请</button>
      </div>
    </div>`;
}

function render(el) {
  el.innerHTML = headHtml() + `
    <div class="seg" style="margin-bottom:16px">
      <button class="${tab === "mine" ? "on" : ""}" data-tab="mine">我的申请 <span class="cnt">${apps.length}</span></button>
      <button class="${tab === "avail" ? "on" : ""}" data-tab="avail">可申请证明 <span class="cnt">${avail.length}</span></button>
    </div>
    <div id="zmsq-body">${tab === "mine" ? mineHtml() : availHtml()}</div>`;

  $$("[data-tab]", el).forEach((b) =>
    b.addEventListener("click", () => { tab = b.dataset.tab; render(el); })
  );
  $("#btn-refresh", el)?.addEventListener("click", async (e) => {
    e.target.disabled = true;
    apps = null; avail = null;
    loadData(el);
  });
  $("#btn-new-apply", el)?.addEventListener("click", () => { tab = "avail"; render(el); });

  if (tab === "mine") bindMine(el); else bindAvail(el);
}

// ---------- 我的申请 ----------
function mineHtml() {
  const filtered = apps.filter((r) => {
    if (filters.status === "valid" && r.WJYXZT !== "1") return false;
    if (filters.status === "invalid" && r.WJYXZT === "1") return false;
    if (filters.q) {
      const q = filters.q.toLowerCase();
      if (!String(r.ZMWJMC).toLowerCase().includes(q) && !String(r.YWZMWJMC || "").toLowerCase().includes(q)) return false;
    }
    return true;
  });
  const validCount = apps.filter((r) => r.WJYXZT === "1").length;

  return `
    <div class="stat-chips">
      <div class="stat-chip"><span class="dot" style="background:var(--green)"></span>有效 <b>${validCount}</b> 份</div>
      <div class="stat-chip"><span class="dot" style="background:var(--text-3)"></span>已过期 <b>${apps.length - validCount}</b> 份</div>
      <div class="stat-chip" style="color:var(--text-3)">证明有效期均为 7 天, 过期后需重新申请</div>
    </div>
    <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-bottom:14px">
      <div class="seg">
        <button class="${filters.status === "all" ? "on" : ""}" data-s="all">全部</button>
        <button class="${filters.status === "valid" ? "on" : ""}" data-s="valid">有效</button>
        <button class="${filters.status === "invalid" ? "on" : ""}" data-s="invalid">已过期</button>
      </div>
      <input class="input" id="zm-q" placeholder="搜索证明名称" value="${esc(filters.q)}" style="width:200px;margin-left:auto" />
    </div>
    <div class="table-wrap" style="max-height:62vh;overflow-y:auto">
      <table class="data" style="min-width:760px">
        <thead><tr>
          <th>证明文件</th><th>申请时间</th><th>有效状态</th><th>有效截止</th><th>打印份数</th><th>操作</th>
        </tr></thead>
        <tbody>
          ${filtered.map((r) => `
            <tr>
              <td class="course-name">${esc(r.ZMWJMC)}${r.YWZMWJMC ? `<div style="font-size:11px;color:var(--text-3)">${esc(r.YWZMWJMC)}</div>` : ""}</td>
              <td class="num" style="color:var(--text-2)">${esc(r.SQSJ || "-")}</td>
              <td>${r.WJYXZT === "1" ? '<span class="badge green">有效</span>' : '<span class="badge gray">已过期</span>'}</td>
              <td class="num" style="color:var(--text-2)">${esc(r.WJYXJZSJ || "-")}</td>
              <td class="num">${esc(r.SYKDYFS ?? "-")} / ${esc(r.KSQZDYFS ?? "-")}</td>
              <td style="white-space:nowrap">
                ${r.WJYXZT === "1" ? `
                  <button class="btn small" data-dl="${esc(r.WID)}" data-name="${esc(r.ZMWJMC)}">⬇ 下载</button>
                  <button class="btn small" data-mail="${esc(r.WID)}" data-name="${esc(r.ZMWJMC)}">✉ 邮件</button>
                ` : '<span style="color:var(--text-3);font-size:12px">已过期</span>'}
              </td>
            </tr>`).join("") || `<tr><td colspan="6"><div class="empty"><div class="icon">🔍</div><div class="t">没有匹配的记录</div></div></td></tr>`}
        </tbody>
      </table>
    </div>`;
}

function bindMine(el) {
  $$("[data-s]", el).forEach((b) =>
    b.addEventListener("click", () => { filters.status = b.dataset.s; render(el); })
  );
  $("#zm-q", el)?.addEventListener("input", (e) => {
    filters.q = e.target.value;
    const pos = e.target.selectionStart;
    render(el);
    const input = $("#zm-q", el);
    input?.focus();
    input?.setSelectionRange(pos, pos);
  });
  // 下载
  $$("[data-dl]", el).forEach((b) =>
    b.addEventListener("click", async () => {
      const url = "/api/zmsq/download?wid=" + encodeURIComponent(b.dataset.dl);
      const name = b.dataset.name || "证明文件";
      b.disabled = true;
      try {
        const r = await fetch(url);
        const ct = r.headers.get("content-type") || "";
        if (ct.includes("application/json")) {
          const d = await r.json();
          toast(d.msg || d.message || "下载失败(证明可能已过期)", "err");
          return;
        }
        const blob = await r.blob();
        const a = document.createElement("a");
        a.href = URL.createObjectURL(blob);
        a.download = name + ".pdf";
        document.body.appendChild(a);
        a.click();
        a.remove();
        setTimeout(() => URL.revokeObjectURL(a.href), 5000);
        toast("开始下载: " + name, "ok");
      } catch (e) {
        // 兜底: 直接导航下载
        window.open(url, "_blank");
        toast("已在新标签页打开下载", "");
      } finally {
        b.disabled = false;
      }
    })
  );
  // 邮件
  $$("[data-mail]", el).forEach((b) =>
    b.addEventListener("click", () => showMailModal(b.dataset.mail, b.dataset.name))
  );
}

function showMailModal(wid, name) {
  const m = modal(`
    <h3>发送到邮箱</h3>
    <div style="font-size:13px;color:var(--text-2);margin-bottom:14px">${esc(name)}</div>
    <div class="field">
      <label>邮箱地址</label>
      <input class="input" id="mail-addr" type="email" placeholder="name@example.com" style="width:100%" />
      <div class="hint">学校邮箱(@stu.xmu.edu.cn)接收更可靠</div>
    </div>
    <div class="actions">
      <button class="btn" id="mail-cancel">取消</button>
      <button class="btn primary" id="mail-ok">发送</button>
    </div>`);
  $("#mail-cancel", m).addEventListener("click", closeModal);
  $("#mail-ok", m).addEventListener("click", async (e) => {
    const mail = $("#mail-addr", m).value.trim();
    if (!mail) { toast("请输入邮箱地址", "err"); return; }
    e.target.disabled = true; e.target.textContent = "发送中…";
    try {
      const r = await api("/api/zmsq/email", { method: "POST", body: { wids: [wid], mail } });
      if (r.ok) { toast("已发送到 " + mail, "ok"); closeModal(); }
      else { toast(r.message || "发送失败", "err"); e.target.disabled = false; e.target.textContent = "发送"; }
    } catch (err) { toast(err.message, "err"); e.target.disabled = false; e.target.textContent = "发送"; }
  });
}

// ---------- 可申请证明 ----------
function availHtml() {
  const filtered = avail.filter((r) => {
    if (filters.q) {
      const q = filters.q.toLowerCase();
      if (!String(r.ZMWJMC).toLowerCase().includes(q) && !String(r.YWZMWJMC || "").toLowerCase().includes(q)) return false;
    }
    return true;
  });
  return `
    <div class="notice" style="margin-bottom:14px">
      <span class="icon">ℹ️</span>
      <span>同一证明文件申请多次, 仅能下载最新的一次申请文件; 证明有效期为 ${avail[0]?.WJYXTS || 7} 天, 请按需申请。</span>
    </div>
    <div style="margin-bottom:14px">
      <input class="input" id="av-q" placeholder="搜索证明名称" value="${esc(filters.q)}" style="width:240px" />
    </div>
    <div style="display:grid;grid-template-columns:repeat(auto-fill,minmax(280px,1fr));gap:12px">
      ${filtered.map((r) => `
        <div class="card" style="padding:16px;display:flex;flex-direction:column;gap:10px">
          <div style="font-weight:700;font-size:14.5px;line-height:1.4">${esc(r.ZMWJMC)}</div>
          ${r.YWZMWJMC ? `<div style="font-size:11.5px;color:var(--text-3)">${esc(r.YWZMWJMC)}</div>` : ""}
          <div style="display:flex;gap:6px;flex-wrap:wrap;margin-top:auto">
            <span class="tag">有效期 ${esc(r.WJYXTS || 7)} 天</span>
            ${r.SFWLJZM_DISPLAY ? `<span class="tag p">${esc(r.SFWLJZM_DISPLAY)}</span>` : ""}
            ${r.SFZJ_DISPLAY ? `<span class="tag">${esc(r.SFZJ_DISPLAY)}</span>` : ""}
          </div>
          <button class="btn primary small" data-apply="${esc(r.WID)}" data-name="${esc(r.ZMWJMC)}" style="margin-top:4px">申请</button>
        </div>`).join("") || `<div class="empty"><div class="icon">🔍</div><div class="t">没有匹配的证明</div></div>`}
    </div>`;
}

function bindAvail(el) {
  $("#av-q", el)?.addEventListener("input", (e) => {
    filters.q = e.target.value;
    const pos = e.target.selectionStart;
    render(el);
    const input = $("#av-q", el);
    input?.focus();
    input?.setSelectionRange(pos, pos);
  });
  $$("[data-apply]", el).forEach((b) =>
    b.addEventListener("click", () => showApplyModal(b.dataset.apply, b.dataset.name))
  );
}

function showApplyModal(wid, name) {
  const m = modal(`
    <h3>申请证明</h3>
    <div style="font-size:13.5px;font-weight:600;margin-bottom:14px">${esc(name)}</div>
    <div class="field">
      <label>申请打印份数</label>
      <input class="input" id="ap-count" type="number" min="1" value="1" style="width:100%" />
    </div>
    <div class="field">
      <label>是否到自助机打印</label>
      <select class="input" id="ap-zzj" style="width:100%">
        <option value="0">否</option>
        <option value="1">是</option>
      </select>
    </div>
    <div class="field">
      <label>是否使用随机码</label>
      <select class="input" id="ap-sjm" style="width:100%">
        <option value="0">否</option>
        <option value="1">是(可在自助机直接输入随机码打印)</option>
      </select>
    </div>
    <div class="actions">
      <button class="btn" id="ap-cancel">取消</button>
      <button class="btn primary" id="ap-ok">提交申请</button>
    </div>`);
  $("#ap-cancel", m).addEventListener("click", closeModal);
  $("#ap-ok", m).addEventListener("click", async (e) => {
    const count = Number($("#ap-count", m).value) || 1;
    e.target.disabled = true; e.target.textContent = "提交中…";
    try {
      const r = await api("/api/zmsq/apply", {
        method: "POST",
        body: {
          zmids: [wid],
          form: { SFDZZJDY: $("#ap-zzj", m).value, SQDYS: count, SFSYSJM: $("#ap-sjm", m).value },
        },
      });
      if (r.ok) {
        toast("申请成功, 请到「我的申请」下载", "ok");
        closeModal();
        apps = null;
        loadData($("#page-zmsq"));
      } else {
        toast(r.message || "申请失败", "err");
        e.target.disabled = false; e.target.textContent = "提交申请";
      }
    } catch (err) {
      toast(err.message, "err");
      e.target.disabled = false; e.target.textContent = "提交申请";
    }
  });
}
