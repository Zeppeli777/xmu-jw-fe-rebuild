// 教务系统(jw.xmu.edu.cn, 金智 EMAP 平台) API 客户端
// 负责: 会话 cookie 管理(持久化)、_WEU 令牌轮换、登录态检测、各业务接口封装
//
// 关键机制(逆向实测):
// 1. 登录后服务端下发 GS_SESSIONID(HttpOnly) + _WEU(轮换令牌)
// 2. _WEU 会在每个响应中轮换; 客户端必须始终使用最新值 → 请求串行执行 + 每响应更新 cookie
// 3. 新会话的 _WEU 是"未激活"态(约113字符), 访问 /jwapp/sys/jwai/index.do 后升级为完整态
//    (未升级时调用业务 API 会返回 403)
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { addCookie, cookieHeader, serializeJar, deserializeJar } from "./cas.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SESSION_FILE = path.join(__dirname, ".session.json");
const JW = "https://jw.xmu.edu.cn";
const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
const PORTAL_REFERER = JW + "/new/index.html";

/** 会话状态 */
const state = {
  jar: new Map(),
  xh: null,
  user: null,
};

function persist() {
  try {
    fs.writeFileSync(
      SESSION_FILE,
      JSON.stringify({ jar: serializeJar(state.jar), xh: state.xh, user: state.user }, null, 2)
    );
  } catch {}
}

export function loadSession() {
  try {
    const raw = JSON.parse(fs.readFileSync(SESSION_FILE, "utf8"));
    state.jar = deserializeJar(raw.jar);
    state.xh = raw.xh || null;
    state.user = raw.user || null;
  } catch {}
}

export function isLoggedIn() {
  return state.jar.size > 0;
}

export function currentUser() {
  return state.user;
}

export function setJar(jar) {
  state.jar = jar;
}

export function clearSession() {
  state.jar = new Map();
  state.xh = null;
  state.user = null;
  try { fs.unlinkSync(SESSION_FILE); } catch {}
}

// ---------- 请求串行化(_WEU 轮换竞态保护) ----------
let queue = Promise.resolve();
function enqueue(fn) {
  const p = queue.then(fn, fn);
  queue = p.catch(() => {});
  return p;
}

function expireError() {
  const e = new Error("会话已失效, 请重新登录");
  e.code = "SESSION_EXPIRED";
  return e;
}

/** 通用请求: 自动带 cookie、收集 Set-Cookie(轮换 _WEU)、识别会话失效 */
async function request(url, { method = "POST", params, raw = false, referer = PORTAL_REFERER } = {}) {
  const headers = {
    "User-Agent": UA,
    Cookie: cookieHeader(state.jar, "jw.xmu.edu.cn"),
    Referer: referer,
  };
  let body;
  if (method === "POST") {
    body = new URLSearchParams(params || {}).toString();
    headers["Content-Type"] = "application/x-www-form-urlencoded; charset=UTF-8";
    headers["X-Requested-With"] = "XMLHttpRequest";
    headers["Accept"] = "application/json, text/javascript, */*; q=0.01";
  }
  const r = await fetch(url, { method, redirect: "manual", headers, body });
  // 收集轮换 cookie
  for (const c of r.headers.getSetCookie()) addCookie(state.jar, c);
  if (process.env.DEBUG) {
    const weu = [...state.jar.values()].find((c) => c.name === "_WEU");
    console.log(`[jw] ${method} ${url.replace(JW, "").slice(0, 60)} → ${r.status} _WEU=${weu ? weu.value.length : 0}`);
  }
  if (r.status === 302 || r.status === 401) {
    const loc = r.headers.get("location") || "";
    if (loc.includes("ids.xmu.edu.cn") || r.status === 401) throw expireError();
  }
  if (r.status === 403) {
    // 403: 多为 _WEU 未激活/陈旧, 抛特殊错误由外层预热后重试
    const e = new Error("会话需要重新激活");
    e.code = "NEED_WARMUP";
    throw e;
  }
  if (!r.ok) throw new Error(`教务接口错误 HTTP ${r.status}`);
  const text = await r.text();
  if (raw) return text;
  try {
    return JSON.parse(text);
  } catch {
    throw expireError();
  }
}

/** 会话预热(直接请求, 不进串行队列, 避免死锁) */
async function warmUpDirect() {
  // 中文界面(否则 _DISPLAY 字段返回英文)
  addCookie(state.jar, "EMAP_LANG=zh; Path=/");
  addCookie(state.jar, "amp.locale=zh_CN; Path=/");
  const seq = [
    { m: "GET", u: JW + "/jwapp/sys/jwai/*default/index.do" },
    { m: "GET", u: JW + "/jwapp/sys/jwai/index.do" },
    { m: "POST", u: JW + "/jwapp/sys/jwai/api/user/getCurrentUser.do" },
  ];
  for (const s of seq) {
    try {
      const r = await fetch(s.u, {
        method: s.m,
        headers: {
          "User-Agent": UA,
          Cookie: cookieHeader(state.jar, "jw.xmu.edu.cn"),
          Referer: PORTAL_REFERER,
          "X-Requested-With": "XMLHttpRequest",
          ...(s.m === "POST" ? { "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8" } : {}),
        },
        body: s.m === "POST" ? "" : undefined,
        redirect: "manual",
      });
      for (const c of r.headers.getSetCookie()) addCookie(state.jar, c);
      await r.text();
    } catch {}
  }
  persist();
}

/**
 * 设置应用角色(新会话必须!)。
 * 教务系各应用按角色鉴权, 新会话未设角色时业务 API 一律 403。
 * 每个应用有各自的角色(如 zmsqxmu 是"证明申请本科生"), 需分别 changeAppRole。
 * 角色 ID 从各应用自己的配置接口动态获取。内部用裸 fetch, 避免 403 重试递归。
 */
async function setupAppRole() {
  const post = async (path, params = {}) => {
    const r = await fetch(JW + path, {
      method: "POST",
      redirect: "manual",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
        "X-Requested-With": "XMLHttpRequest",
        Accept: "application/json, text/javascript, */*; q=0.01",
        Cookie: cookieHeader(state.jar, "jw.xmu.edu.cn"),
        "User-Agent": UA,
        Referer: PORTAL_REFERER,
      },
      body: new URLSearchParams(params).toString(),
    });
    for (const c of r.headers.getSetCookie()) addCookie(state.jar, c);
    return r.json().catch(() => null);
  };
  const APPS = ["xywccx", "jdfpm", "zmsqxmu"];
  try {
    for (const appName of APPS) {
      // 1. 从应用页提取 appId
      const page = await fetch(JW + `/jwapp/sys/${appName}/*default/index.do`, {
        headers: { "User-Agent": UA, Cookie: cookieHeader(state.jar, "jw.xmu.edu.cn"), Referer: PORTAL_REFERER },
        redirect: "manual",
      });
      for (const c of page.headers.getSetCookie()) addCookie(state.jar, c);
      const html = await page.text();
      const appId = html.match(/"appId":"(\d+)"/)?.[1];
      if (!appId) continue;

      // 2. 该应用的配置(角色列表)
      const cfg = await post(`/jwapp/sys/funauthapp/api/getAppConfig/${appName}-${appId}.do`);
      const role = cfg?.HEADER?.dropMenu?.find((r) => r.active) || cfg?.HEADER?.dropMenu?.[0];
      if (!role?.id) continue;

      // 3. 切换该应用的角色
      const res = await post(`/jwapp/sys/funauthapp/api/changeAppRole/${appName}/${role.id}.do`);
      if (process.env.DEBUG) console.log(`[jw] changeAppRole ${appName}/${role.text} →`, JSON.stringify(res));
    }
    // 4. 公共角色(用 xywccx 的本科生角色)
    const xpage = await fetch(JW + "/jwapp/sys/xywccx/*default/index.do", {
      headers: { "User-Agent": UA, Cookie: cookieHeader(state.jar, "jw.xmu.edu.cn"), Referer: PORTAL_REFERER },
      redirect: "manual",
    });
    const xhtml = await xpage.text();
    const xappId = xhtml.match(/"appId":"(\d+)"/)?.[1];
    if (xappId) {
      const xcfg = await post(`/jwapp/sys/funauthapp/api/getAppConfig/xywccx-${xappId}.do`);
      const xrole = xcfg?.HEADER?.dropMenu?.find((r) => r.text === "本科生") || xcfg?.HEADER?.dropMenu?.[0];
      if (xrole?.id) await post("/jwapp/sys/jwpubapp/pub/setJwCommonAppRole.do", { ROLEID: xrole.id });
    }
  } catch (e) {
    if (process.env.DEBUG) console.log("[jw] 角色设置失败:", e.message);
  }
  persist();
}

/** 带 403 预热重试的请求(重试在串行队列之外) */
async function withWarmupRetry(fn) {
  try {
    return await fn();
  } catch (e) {
    if (e.code === "NEED_WARMUP") {
      await warmUpDirect();
      await setupAppRole();
      return fn(); // 再试一次(仍在队列内)
    }
    throw e;
  }
}

export async function jwPost(apiPath, params = {}, opts = {}) {
  const url = apiPath.startsWith("http") ? apiPath : JW + apiPath;
  return withWarmupRetry(() => enqueue(() => request(url, { method: "POST", params, ...opts })));
}

export async function jwGet(apiPath, opts = {}) {
  const url = apiPath.startsWith("http") ? apiPath : JW + apiPath;
  return withWarmupRetry(() => enqueue(() => request(url, { method: "GET", ...opts })));
}

/** 新会话建立后调用: 预热 + 设置角色 + 持久化 */
export async function initSession() {
  await warmUpDirect();
  await setupAppRole();
  persist();
}

// ---------- 业务接口 ----------

/** 学生基本信息(同时用于获取学号 XH 与校验登录态) */
export async function getStudentInfo() {
  const res = await jwPost("/jwapp/sys/jdfpm/modules/jdjs/cxxsjbxx.do", {});
  const r = rows(res, "cxxsjbxx")[0];
  if (!r) throw expireError();
  state.xh = r.XH;
  state.user = {
    XH: r.XH, XM: r.XM, XZNJ: r.XZNJ_DISPLAY,
    YXDM: r.YXDM_DISPLAY, ZYDM: r.ZYDM_DISPLAY,
    BJDM: r.BJDM_DISPLAY, XZ: r.XZ, XJZT: r.XJZTDM_DISPLAY,
  };
  persist();
  return state.user;
}

/**
 * 学业完成查询 - 全量聚合:
 *  方案摘要 + 课组树(模块/课组) + 全部课程(方案内+方案外) + 未修统计
 */
export async function getXywccxOverview() {
  if (!state.xh) await getStudentInfo();
  const XH = state.xh;

  // 1. 先查学生的培养方案(拿到 PYFADM; 空参数时服务端按会话取当前学生)
  const scfaRes = await jwPost("/jwapp/sys/xywccx/modules/xywccx/cxxsscfa.do", {});
  const fa = rows(scfaRes, "cxxsscfa")[0];
  if (!fa) return { hasPlan: false };

  const PYFADM = fa.PYFADM;
  const [kzRes, kcRes, fawRes] = await Promise.all([
    jwPost("/jwapp/sys/xywccx/modules/xywccx/cxscfakz.do", {
      XH, PYFADM, BYNJDM: "-", PCDM: "-", SCLBDM: "04", XNXQDM: currentXnxq(),
    }),
    jwPost("/jwapp/sys/xywccx/modules/xywccx/cxscfakzkchxkqkx.do", {
      SCLBDM: "04", BYNJDM: "-", PCDM: "-", XH, "*order": "+SFTG", PYFADM, pageSize: 1000, pageNumber: 1,
    }),
    jwPost("/jwapp/sys/xywccx/modules/xywccx/cxfawxfms.do", { XH, PYFADM, BYNJDM: "-", PCDM: "-" }),
  ]);

  return {
    hasPlan: true,
    plan: {
      PYFAMC: fa.PYFAMC, WCXF: num(fa.WCXF), YQXF: num(fa.YQXF), XKXF: num(fa.XKXF), CZSJ: fa.CZSJ,
    },
    groups: rows(kzRes, "cxscfakz"),
    courses: rows(kcRes, "cxscfakzkchxkqkx"),
    missing: rows(fawRes, "cxfawxfms")[0] || null,
  };
}

/** GPA 排名 - 学生信息 + 可选成绩范围 + 申请记录 */
export async function getJdfpmInfo() {
  if (!state.xh) await getStudentInfo();
  const [fwRes, sqRes] = await Promise.all([
    jwPost("/jwapp/sys/jdfpm/modules/jdjs/cxxskxcjfw.do", {}),
    jwPost("/jwapp/sys/jdfpm/api/jdjs/getJdjssq.do", {}),
  ]);
  let records = sqRes?.datas?.getJdjssq;
  if (!records && Array.isArray(sqRes)) records = sqRes;
  return { user: state.user, ranges: rows(fwRes, "cxxskxcjfw"), records: records || [] };
}

/** 新增绩点计算申请 */
export async function applyJdfpm(cjfwWid) {
  const range = (await jwPost("/jwapp/sys/jdfpm/modules/jdjs/cxxskxcjfw.do", {})).datas.cxxskxcjfw.rows.find((r) => r.WID === cjfwWid);
  if (!range) throw new Error("成绩范围不存在");
  await jwPost("/jwapp/sys/jdfpm/api/jdjs/addJdjssq.do", {
    CJFWWID: cjfwWid, CJFWWID_DISPLAY: range.XSMC,
  });
  return { ok: true };
}

/** 某次申请的参与计算成绩明细 */
export async function getJdfpmDetail(cjfwWid) {
  if (!state.xh) await getStudentInfo();
  const querySetting = JSON.stringify([
    { name: "CJFWWID", linkOpt: "AND", builder: "equal", value: cjfwWid },
    { name: "SFZX", linkOpt: "AND", builder: "equal", value: "1" },
  ]);
  const res = await jwPost("/jwapp/sys/jdfpm/modules/jdjs/cxzdxscjxq.do", {
    SFZX: "1", CJFWWID: cjfwWid, querySetting, "*order": "-XNXQDM,+KCH", pageSize: 1000, pageNumber: 1,
  });
  return rows(res, "cxzdxscjxq");
}

/** 打印绩点证明: 代理教务系统的证明 HTML(保持会话) */
export async function printJdfpm(wid, lx, dynr, sfxypm) {
  const url = `${JW}/jwapp/sys/jdfpm/api/jdjs/printZm.do?&wid=${encodeURIComponent(wid)}&type=${encodeURIComponent(lx)}&DYNR=${encodeURIComponent(dynr)}&SFXYPM=${encodeURIComponent(sfxypm)}`;
  return request(url, { method: "GET", raw: true, referer: JW + "/jwapp/sys/jdfpm/" });
}

// ---------- 证明文件申请(app: zmsqxmu) ----------
// 列表类接口(EMAP datatable 模型)在 modules/ 下; 动作类接口(save/down/mail)在应用根下
const ZMSQ_LIST = "/jwapp/sys/zmsqxmu/modules/xszmsq/";
const ZMSQ_ACTION = "/jwapp/sys/zmsqxmu/xszmsq/";
// EMAP 框架对部分端点(码表/保存类)校验 Referer 必须为应用页
const ZMSQ_REF = JW + "/jwapp/sys/zmsqxmu/*default/index.do";

/** 我的证明申请列表 */
export async function getZmApplications() {
  const querySetting = JSON.stringify([{ name: "*order", value: "-SQSJ", linkOpt: "AND", builder: "equal" }]);
  const res = await jwPost(ZMSQ_LIST + "xszmsqcx.do", {
    "*order": "-SQSJ", querySetting, pageSize: 200, pageNumber: 1,
  }, { referer: ZMSQ_REF });
  return rows(res, "xszmsqcx");
}

/** 可申请的证明列表 */
export async function getZmAvailable() {
  const querySetting = JSON.stringify([{ name: "*order", value: "+WID", linkOpt: "AND", builder: "equal" }]);
  const res = await jwPost(ZMSQ_LIST + "dsqzmcx.do", {
    "*order": "+WID", querySetting, pageSize: 200, pageNumber: 1,
  }, { referer: ZMSQ_REF });
  return rows(res, "dsqzmcx");
}

/**
 * 提交证明申请
 * @param {string[]} zmids 证明 WID 列表
 * @param {object} form { SFDZZJDY: 是否自助机打印, SQDYS: 份数, SFSYSJM: 是否随机码 }
 */
export async function applyZm(zmids, form) {
  const paramStr = JSON.stringify({
    SFDZZJDY: String(form.SFDZZJDY ?? "0"),
    SQDYS: String(form.SQDYS ?? "1"),
    SFSYSJM: String(form.SFSYSJM ?? "0"),
  });
  const res = await jwPost(ZMSQ_ACTION + "saveZmsq.do", {
    paramStr, zmid: zmids.join(","),
  }, { referer: ZMSQ_REF });
  return res;
}

/** 查询可打印方式(提交打印申请前调用) */
export async function queryZmPrintMethods(zmids) {
  const res = await jwPost(ZMSQ_ACTION + "queryKdyfs.do", { zmids: zmids.join(",") }, { referer: ZMSQ_REF });
  return res;
}

/** 下载证明文件(有效期内返回文件, 否则返回 JSON 错误) */
export async function downloadZm(wid) {
  const url = JW + ZMSQ_ACTION + "downZm.do";
  return withWarmupRetry(() =>
    enqueue(async () => {
      const r = await fetch(url, {
        method: "POST",
        redirect: "manual",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
          "X-Requested-With": "XMLHttpRequest",
          Cookie: cookieHeader(state.jar, "jw.xmu.edu.cn"),
          "User-Agent": UA,
          Referer: ZMSQ_REF,
        },
        body: new URLSearchParams({ wid }).toString(),
      });
      for (const c of r.headers.getSetCookie()) addCookie(state.jar, c);
      if (r.status === 403) {
        const e = new Error("会话需要重新激活");
        e.code = "NEED_WARMUP";
        throw e;
      }
      const buf = Buffer.from(await r.arrayBuffer());
      return {
        status: r.status,
        contentType: r.headers.get("content-type") || "application/octet-stream",
        disposition: r.headers.get("content-disposition") || "",
        data: buf,
      };
    })
  );
}

/** 邮件发送证明 */
export async function emailZm(wids, mail) {
  const res = await jwPost(ZMSQ_ACTION + "sendMail.do", { wids: wids.join(","), mail }, { referer: ZMSQ_REF });
  return res;
}

/**
 * 用 CAS 票据(ticket)交换教务系统会话。
 * 用于"浏览器辅助登录": 用户浏览器已登录统一身份认证时无需再输密码。
 */
export async function loginByTicket(ticket) {
  const jar = new Map();
  state.jar = jar;
  let url = `${JW}/jwapp/sys/emapangular/index.do?ticket=${encodeURIComponent(ticket)}`;
  for (let i = 0; i < 8 && url; i++) {
    const r = await fetch(url, {
      redirect: "manual",
      headers: { "User-Agent": UA, Cookie: cookieHeader(jar, "jw.xmu.edu.cn"), Referer: PORTAL_REFERER },
    });
    for (const c of r.headers.getSetCookie()) addCookie(jar, c);
    const next = r.headers.get("location");
    if (r.status >= 300 && r.status < 400 && next) {
      url = new URL(next, url).href;
      continue;
    }
    await r.arrayBuffer().catch(() => {});
    break;
  }
  if (![...jar.keys()].some((k) => k.startsWith("GS_SESSIONID|") || k.startsWith("_WEU|"))) {
    const e = new Error("票据无效或已过期");
    e.code = "BAD_TICKET";
    throw e;
  }
  await initSession(); // 预热激活 _WEU
  await getStudentInfo(); // 验证会话
  return state.user;
}

// ---------- 工具 ----------

function rows(res, dataName) {
  const d = res?.datas?.[dataName];
  if (!d) return [];
  return d.rows || (Array.isArray(d) ? d : []);
}

function num(v) { const n = Number(v); return Number.isFinite(n) ? n : 0; }

/** 当前学年学期(教务系统惯例: 9 月后为第一学期) */
export function currentXnxq() {
  const now = new Date();
  const y = now.getFullYear();
  const m = now.getMonth() + 1;
  return m >= 9 ? `${y}-${y + 1}-1` : `${y - 1}-${y}-2`;
}
