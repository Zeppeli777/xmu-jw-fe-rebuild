/* ============================================================
   核心: 主题 / 登录态 / 路由 / API / 工具
   ============================================================ */

// ---------- 工具 ----------
export const $ = (s, p = document) => p.querySelector(s);
export const $$ = (s, p = document) => [...p.querySelectorAll(s)];

export function esc(str) {
  return String(str ?? "").replace(/[&<>"']/g, (c) => (
    { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]
  ));
}

export function toast(msg, type = "") {
  const el = document.createElement("div");
  el.className = `toast ${type}`;
  el.textContent = (type === "ok" ? "✓ " : type === "err" ? "✕ " : "") + msg;
  $("#toast-wrap").appendChild(el);
  setTimeout(() => {
    el.style.transition = "opacity .3s, transform .3s";
    el.style.opacity = "0";
    el.style.transform = "translateY(-8px)";
    setTimeout(() => el.remove(), 320);
  }, 2600);
}

export function fmtNum(n, digits = 1) {
  const v = Number(n);
  if (!Number.isFinite(v)) return "-";
  return fmtFixed(v, digits);
}

/** 固定位数格式化并去掉多余的尾零(3.50→3.5, 50→50, 不会把 50 变成 5) */
export function fmtFixed(n, digits = 1) {
  let s = Number(n).toFixed(digits);
  if (s.includes(".")) s = s.replace(/\.?0+$/, "");
  return s;
}

export function fmtPct(cur, total) {
  if (!total) return "0";
  return ((cur / total) * 100).toFixed(1).replace(/\.0+$/, "");
}

/** 数字滚动动画 */
export function countUp(el, target, { digits = 1, dur = 900, suffix = "" } = {}) {
  const start = performance.now();
  const from = 0;
  function tick(t) {
    const p = Math.min(1, (t - start) / dur);
    const eased = 1 - Math.pow(1 - p, 3);
    el.textContent = fmtFixed(from + (target - from) * eased, digits) + suffix;
    if (p < 1) requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);
}

// ---------- 主题 ----------
const THEME_KEY = "jw-theme";
export function initTheme() {
  const saved = localStorage.getItem(THEME_KEY) || "dark";
  document.documentElement.dataset.theme = saved;
  updateThemeBtn();
  $("#theme-btn").addEventListener("click", () => {
    const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    localStorage.setItem(THEME_KEY, next);
    updateThemeBtn();
    // 图表颜色依赖主题, 刷新当前页
    window.dispatchEvent(new Event("themechange"));
  });
}
function updateThemeBtn() {
  const btn = $("#theme-btn");
  if (btn) btn.textContent = document.documentElement.dataset.theme === "dark" ? "🌙 深色" : "☀️ 浅色";
}

// ---------- API ----------
export async function api(path, opts = {}) {
  const r = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...opts,
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  if (r.status === 401) {
    const d = await r.json().catch(() => ({}));
    if (d.error === "SESSION_EXPIRED") {
      showLogin("会话已失效, 请重新登录");
      throw new Error("session expired");
    }
  }
  if (!r.ok) {
    const d = await r.json().catch(() => ({}));
    throw new Error(d.message || d.error || `HTTP ${r.status}`);
  }
  return r.json();
}

// ---------- 登录态 ----------
export let state = { user: null };

export function showLogin(msg = "") {
  $("#login-layer").hidden = false;
  $("#app").hidden = true;
  if (!msg) return;
  // 各登录方式有自己的提示区, 消息显示在当前标签页上
  const tab = $$("#login-tabs button.on")[0]?.dataset.tab || "official";
  const el = { official: "#official-msg", chrome: "#chrome-msg", cookie: "#cookie-msg" }[tab];
  if (el) $(el).textContent = msg;
}

export function showApp(user) {
  state.user = user;
  $("#login-layer").hidden = true;
  $("#app").hidden = false;
  const u = user || {};
  $("#user-chip").innerHTML = `
    <div class="avatar">${esc((u.XM || "?")[0])}</div>
    <div style="min-width:0">
      <div class="name">${esc(u.XM || "")}</div>
      <div class="meta">${esc(u.ZYDM || "")} · ${esc(u.XZNJ || "")}</div>
    </div>`;
}

// ---------- 登录交互 ----------
export function initLogin(onLoggedIn) {
  // tab 切换
  $$("#login-tabs button").forEach((b) =>
    b.addEventListener("click", () => {
      $$("#login-tabs button").forEach((x) => x.classList.toggle("on", x === b));
      for (const t of ["official", "chrome", "cookie"]) {
        $("#tab-" + t).hidden = b.dataset.tab !== t;
      }
    })
  );

  // Chrome 导入
  $("#chrome-login-btn").addEventListener("click", async () => {
    const btn = $("#chrome-login-btn"), msg = $("#chrome-msg");
    btn.disabled = true; btn.textContent = "读取中…"; msg.textContent = "";
    try {
      const r = await api("/api/login/by-chrome", { method: "POST", body: {} });
      if (r.ok) { toast("已从 Chrome 导入登录态", "ok"); onLoggedIn(r.user); }
      else msg.textContent = r.message || "导入失败";
    } catch (e) { msg.textContent = e.message; }
    finally { btn.disabled = false; btn.textContent = "从 Chrome 导入登录态"; }
  });

  // Cookie 导入
  $("#cookie-login-btn").addEventListener("click", async () => {
    const cookies = $("#cookie-input").value.trim();
    const msg = $("#cookie-msg");
    if (!cookies) { msg.textContent = "请粘贴 Cookie"; return; }
    const btn = $("#cookie-login-btn");
    btn.disabled = true; btn.textContent = "验证中…"; msg.textContent = "";
    try {
      const r = await api("/api/login/by-cookie", { method: "POST", body: { cookies } });
      if (r.ok) { toast("登录成功", "ok"); onLoggedIn(r.user); }
      else msg.textContent = r.message || "Cookie 无效";
    } catch (e) { msg.textContent = e.message; }
    finally { btn.disabled = false; btn.textContent = "导入 Cookie 登录"; }
  });

  // 官方登录(学校统一身份认证页面)
  // service 指向不消费票据的静态门户, 登录成功后 ticket 留在地址栏, 带回本应用换会话;
  // type=userNameLogin 必须带: 否则官方页默认展示企业微信扫码, 不显示账号密码表单
  const CAS_LOGIN_URL =
    "https://ids.xmu.edu.cn/authserver/login?type=userNameLogin&service=" +
    encodeURIComponent("https://jw.xmu.edu.cn/new/index.html");
  // 安卓 App: 登录页放在应用内的 WebView 里打开, 登录成功后由原生捕获 ?ticket= 换会话,
  // 不需要书签工具, 也不需要复制粘贴
  if (window.JWAndroid) {
    const bm = $("#official-bookmarklet");
    if (bm) bm.hidden = true;
    const notice = $("#tab-official .notice span:last-child");
    if (notice) {
      notice.innerHTML =
        "在应用内打开学校<b>官方统一身份认证页面</b>登录, 加密/验证码/短信全部由学校页面处理, 最可靠。" +
        "登录成功后<b>自动返回本应用</b>, 无需任何复制粘贴。";
    }
    const hint = $("#tab-official .hint");
    if (hint) {
      hint.innerHTML =
        "1. 点击上方按钮, 在应用内打开学校官方登录页(与直接访问教务系统完全一致);<br />" +
        "2. 登录成功后<b>自动返回本应用</b>, 稍等即可看到数据。<br />" +
        "若登录页长时间无响应, 也可在浏览器里登录后, 把地址栏网址贴到下面同步。";
    }
  }
  $("#official-open-btn").addEventListener("click", () => {
    if (window.JWAndroid) { window.JWAndroid.openCasLogin(CAS_LOGIN_URL); return; }
    // 必须屏蔽 referer: CAS 会拒绝 Referer 为 localhost 的请求(返回 404 空白页), 用户会卡在空白页上
    const a = document.createElement("a");
    a.href = CAS_LOGIN_URL;
    a.rel = "noreferrer";
    document.body.appendChild(a);
    a.click();
    a.remove();
  });
  // 书签工具: 在学校门户页点击, 带 ticket 跳回本应用(经 location.origin 适配任意端口)
  $("#official-bookmarklet").href =
    "javascript:location.href=" +
    "'" + location.origin + "/?ticket='+encodeURIComponent(new URLSearchParams(location.search).get('ticket')||'')";
  $("#official-sync-btn").addEventListener("click", async () => {
    const raw = $("#official-ticket").value.trim();
    const msg = $("#official-msg");
    if (!raw) { msg.textContent = "请粘贴登录后的网址或票据"; return; }
    msg.textContent = "";
    const btn = $("#official-sync-btn");
    btn.disabled = true; btn.textContent = "同步中…";
    try {
      const r = await api("/api/login/by-ticket", { method: "POST", body: { ticket: raw } });
      if (r.ok) { toast("登录成功", "ok"); onLoggedIn(r.user); }
      else msg.textContent = r.message || "票据无效, 请确认已登录成功后重试";
    } catch (e) { msg.textContent = e.message; }
    finally { btn.disabled = false; btn.textContent = "同步登录态"; }
  });

  // 退出
  $("#logout-btn").addEventListener("click", async () => {
    await api("/api/logout", { method: "POST", body: {} }).catch(() => {});
    location.reload();
  });
}

// ---------- 路由 ----------
export function initRouter(routes) {
  function render() {
    const hash = location.hash.replace(/^#\//, "") || "xywccx";
    const route = routes[hash] ? hash : "xywccx";
    $$(".nav-item").forEach((n) => n.classList.toggle("active", n.dataset.route === route));
    $$(".page").forEach((p) => (p.hidden = true));
    $("#page-" + route).hidden = false;
    routes[route]();
  }
  window.addEventListener("hashchange", render);
  render();
}

// ---------- 弹窗 ----------
export function modal(html) {
  const root = $("#modal-root");
  root.innerHTML = `<div class="modal-mask"><div class="modal">${html}</div></div>`;
  const mask = $(".modal-mask", root);
  mask.addEventListener("click", (e) => { if (e.target === mask) closeModal(); });
  return root;
}
export function closeModal() { $("#modal-root").innerHTML = ""; }

// ---------- 启动 ----------
export async function boot(routes) {
  initTheme();
  initLogin((user) => { showApp(user); initRouter(routes); });
  // 官方登录回跳: 书签工具带回 ?ticket=ST-xxx, 先换会话再进应用
  const t = new URLSearchParams(location.search).get("ticket");
  if (t) {
    history.replaceState({}, "", location.pathname);
    const fail = (m) => {
      showLogin(m);
      $("#official-msg").textContent = m;
    };
    try {
      const r = await api("/api/login/by-ticket", { method: "POST", body: { ticket: t } });
      if (r.ok) { showApp(r.user); initRouter(routes); return; }
      fail(r.message || "票据无效或已过期, 请重新登录");
    } catch (e) {
      fail(e.message || "票据无效或已过期, 请重新登录");
    }
    return;
  }
  try {
    const s = await api("/api/session");
    if (s.loggedIn && s.user) {
      showApp(s.user);
      initRouter(routes);
    } else {
      showLogin();
    }
  } catch {
    showLogin();
  }
}
