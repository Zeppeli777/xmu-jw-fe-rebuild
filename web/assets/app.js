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
  if (!r.ok) throw new Error((await r.json().catch(() => ({}))).message || `HTTP ${r.status}`);
  return r.json();
}

// ---------- 登录态 ----------
export let state = { user: null };

export function showLogin(msg = "") {
  $("#login-layer").hidden = false;
  $("#app").hidden = true;
  if (msg) $("#login-msg").textContent = msg;
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
      $("#tab-pwd").hidden = b.dataset.tab !== "pwd";
      $("#tab-chrome").hidden = b.dataset.tab !== "chrome";
      $("#tab-cookie").hidden = b.dataset.tab !== "cookie";
    })
  );

  // 密码登录
  $("#login-btn").addEventListener("click", async () => {
    const xh = $("#login-xh").value.trim();
    const pwd = $("#login-pwd").value;
    const captcha = $("#login-captcha").value.trim();
    const msg = $("#login-msg");
    if (!xh || !pwd) { msg.textContent = "请输入学号和密码"; return; }
    msg.textContent = "";
    const btn = $("#login-btn");
    btn.disabled = true; btn.textContent = "登录中…";
    try {
      const r = await api("/api/login", { method: "POST", body: { username: xh, password: pwd, captcha } });
      if (r.needCaptcha) {
        msg.textContent = r.message || "需要验证码";
        $("#captcha-field").hidden = false;
        $("#captcha-img").src = "/api/captcha?_=" + Date.now();
        return;
      }
      if (r.ok) { toast("登录成功", "ok"); onLoggedIn(r.user); }
      else msg.textContent = r.message || "登录失败";
    } catch (e) {
      if (e.message !== "session expired") msg.textContent = e.message;
    } finally {
      btn.disabled = false; btn.textContent = "登 录";
    }
  });
  $("#captcha-img").addEventListener("click", (e) => { e.target.src = "/api/captcha?_=" + Date.now(); });
  $("#login-pwd").addEventListener("keydown", (e) => { if (e.key === "Enter") $("#login-btn").click(); });

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
