// 厦门大学教务系统前端重写 - 本地服务(零依赖)
// 启动: node server/index.js  →  http://localhost:8787
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import * as jw from "./jw.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const WEB_ROOT = path.join(__dirname, "..", "web");
const PORT = Number(process.env.PORT || 8787);

jw.loadSession();

const MOCK = process.env.MOCK === "1";
const MOCK_DATA = {
  xywccx: JSON.parse(fs.readFileSync(path.join(__dirname, "..", "analysis", "xywccx-data.json"), "utf8")),
  jdfpm: JSON.parse(fs.readFileSync(path.join(__dirname, "..", "analysis", "jdfpm-data.json"), "utf8")),
};
if (MOCK) console.log("  [MOCK 模式] 使用已抓取的真实数据, 不访问教务系统");

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
};

function send(res, status, data, headers = {}) {
  const isObj = typeof data === "object" && !Buffer.isBuffer(data);
  const body = isObj ? JSON.stringify(data) : data;
  res.writeHead(status, {
    "Content-Type": isObj ? "application/json; charset=utf-8" : "text/plain; charset=utf-8",
    ...headers,
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let buf = "";
    req.on("data", (c) => {
      buf += c;
      if (buf.length > 1e6) req.destroy();
    });
    req.on("end", () => {
      try { resolve(buf ? JSON.parse(buf) : {}); } catch (e) { reject(e); }
    });
    req.on("error", reject);
  });
}

/** 统一包装教务接口调用, 处理会话失效 */
async function withSession(res, fn) {
  try {
    const data = await fn();
    send(res, 200, data);
  } catch (e) {
    if (e.code === "SESSION_EXPIRED") {
      send(res, 401, { error: "SESSION_EXPIRED", message: e.message });
    } else {
      console.error("[api]", e);
      send(res, 500, { error: "UPSTREAM", message: e.message });
    }
  }
}

/** 接受完整 URL(如 https://jw.xmu.edu.cn/new/index.html?ticket=ST-xxx)或裸票据 */
function extractTicket(raw) {
  const s = String(raw || "").trim();
  const m = /ticket=([^&\s]+)/.exec(s);
  if (m) return decodeURIComponent(m[1]);
  return s.startsWith("ST-") ? s : "";
}

/**
 * 解析粘贴的 Cookie, 支持三种常见来源:
 * 1. 请求头字符串 "name=value; name2=value2"
 * 2. DevTools → Application → Cookies 表格多选复制(TSV: Name/Value/Domain/Path...)
 * 3. JSON 数组(扩展导出等): [{name, value, domain, path}]
 */
function parseCookieInput(raw) {
  const s = String(raw || "").trim();
  const jar = new Map();
  if (!s) return jar;
  const put = (name, value, domain = "", p = "/") => {
    const n = String(name || "").trim();
    if (!n || n.toLowerCase() === "name") return;
    jar.set(n + "|" + domain + "|" + p, { name: n, value: String(value ?? "").trim(), domain, path: p });
  };
  if (s.startsWith("[")) {
    try {
      for (const c of JSON.parse(s)) put(c?.name, c?.value, c?.domain || "", c?.path || "/");
      return jar;
    } catch { /* 不是合法 JSON, 按文本继续解析 */ }
  }
  for (const line of s.split(/\r?\n/)) {
    const t = line.trim();
    if (!t) continue;
    if (t.includes("\t")) {
      const cols = t.split("\t");
      put(cols[0], cols[1], (cols[2] || "").trim().replace(/^\./, ""), (cols[3] || "").trim() || "/");
    } else if (t.includes("=")) {
      for (const part of t.split(";")) {
        const p = part.trim();
        if (!p || !p.includes("=")) continue;
        const i = p.indexOf("=");
        put(p.slice(0, i), p.slice(i + 1));
      }
    }
  }
  return jar;
}

/** 找可用的 Python 解释器(macOS 上通常只有 python3; Windows 还常用 py 启动器) */
async function findPython() {
  if (process.env.PYTHON) return process.env.PYTHON;
  const { execFile } = await import("node:child_process");
  const ok = (cmd) => new Promise((res) => execFile(cmd, ["--version"], (e) => res(!e)));
  for (const cmd of ["python3", "python", "py"]) if (await ok(cmd)) return cmd;
  return "python3";
}

const server = http.createServer(async (req, res) => {
  const u = new URL(req.url, `http://localhost:${PORT}`);
  const p = u.pathname;

  try {
    // ---------- API ----------
    if (p === "/api/session" && req.method === "GET") {
      const user = jw.currentUser() || (MOCK ? {
        XH: "000000000000", XM: "示例学生", XZNJ: "2023级", YXDM: "示例学院",
        ZYDM: "示例专业", BJDM: "2023示例专业(主修)", XZ: "4", XJZT: "正常",
      } : null);
      return send(res, 200, { loggedIn: MOCK || jw.isLoggedIn(), user });
    }

    if (p === "/api/login/by-cookie" && req.method === "POST") {
      const { cookies } = await readBody(req);
      const jar = parseCookieInput(cookies);
      if (!jar.size) return send(res, 200, { ok: false, message: "未能解析 Cookie, 请检查粘贴内容" });
      jw.setJar(jar);
      try {
        await jw.initSession();
        await jw.getStudentInfo();
        return send(res, 200, { ok: true, user: jw.currentUser() });
      } catch (e) {
        jw.clearSession();
        console.error("[by-cookie]", e);
        return send(res, 200, { ok: false, message: "Cookie 无效或已过期: " + e.message });
      }
    }

    if (p === "/api/login/by-chrome" && req.method === "POST") {
      // 从本机 Chrome 提取登录态(复制 profile 由 headless Chrome 自行解密, 不碰真实 profile)
      const { execFile } = await import("node:child_process");
      const py = await findPython();
      const script = path.join(__dirname, "chrome_cookies.py");
      execFile(py, [script], { timeout: 60000 }, (err, stdout) => {
        try {
          const out = JSON.parse(stdout.trim().split("\n").pop() || "{}");
          if (!out.cookies) return send(res, 200, { ok: false, message: out.error || "未能读取 Chrome 登录态" });
          jw.setJar(parseCookieInput(out.cookies));
          jw.initSession()
            .then(() => jw.getStudentInfo())
            .then(() => send(res, 200, { ok: true, user: jw.currentUser() }))
            .catch((e) => {
              jw.clearSession();
              console.error("[by-chrome]", e);
              send(res, 200, { ok: false, message: "Chrome 中的登录态已失效, 请先在 Chrome 重新登录教务系统: " + e.message });
            });
        } catch {
          send(res, 200, { ok: false, message: "读取 Chrome 登录态失败: " + (err?.message || "未知错误") });
        }
      });
      return;
    }

    if (p === "/api/login/by-ticket" && req.method === "POST") {
      const { ticket } = await readBody(req);
      const st = extractTicket(ticket);
      if (!st) return send(res, 400, { error: "未能识别票据, 请粘贴登录后的完整网址或 ST- 开头的票据" });
      try {
        const user = await jw.loginByTicket(st);
        return send(res, 200, { ok: true, user });
      } catch (e) {
        if (e.code === "BAD_TICKET") return send(res, 200, { ok: false, message: e.message });
        throw e;
      }
    }

    if (p === "/api/logout" && req.method === "POST") {
      jw.clearSession();
      return send(res, 200, { ok: true });
    }

    if (p === "/api/xywccx/overview" && req.method === "GET") {
      if (MOCK) {
        const d = MOCK_DATA.xywccx;
        const fa = d.scfa.datas.cxscfa.rows[0];
        return send(res, 200, {
          hasPlan: true,
          plan: { PYFAMC: fa.PYFAMC, WCXF: Number(fa.WCXF), YQXF: Number(fa.YQXF), XKXF: Number(fa.XKXF), CZSJ: fa.CZSJ },
          groups: d.scfakz.datas.cxscfakz.rows,
          courses: d.kc.datas.cxscfakzkchxkqkx.rows,
          missing: d.fawxfms.datas.cxfawxfms.rows[0],
        });
      }
      return withSession(res, () => jw.getXywccxOverview());
    }

    if (p === "/api/jdfpm/info" && req.method === "GET") {
      if (MOCK) {
        const d = MOCK_DATA.jdfpm;
        return send(res, 200, {
          user: jw.currentUser() || {
            XH: "000000000000", XM: "示例学生", XZNJ: "2023级", YXDM: "示例学院",
            ZYDM: "示例专业", BJDM: "2023示例专业(主修)", XZ: "4", XJZT: "正常",
          },
          ranges: d.cjfw.datas.cxxskxcjfw.rows,
          records: d.jdjssq.datas.getJdjssq,
        });
      }
      return withSession(res, () => jw.getJdfpmInfo());
    }

    if (p === "/api/jdfpm/apply" && req.method === "POST") {
      const { cjfwWid } = await readBody(req);
      return withSession(res, async () => {
        await jw.applyJdfpm(cjfwWid);
        return jw.getJdfpmInfo(); // 返回最新记录
      });
    }

    if (p === "/api/jdfpm/detail" && req.method === "GET") {
      const cjfwWid = u.searchParams.get("cjfwWid");
      if (MOCK) {
        const d = MOCK_DATA.jdfpm;
        const rows = d.cjmx.datas.cxzdxscjxq.rows;
        // mock 下按记录的范围粗过滤: 无范围参数时返回全部
        return send(res, 200, cjfwWid ? rows : rows);
      }
      return withSession(res, () => jw.getJdfpmDetail(cjfwWid));
    }

    // ---------- 证明文件申请 ----------
    if (p === "/api/zmsq/applications" && req.method === "GET") {
      return withSession(res, () => jw.getZmApplications());
    }

    if (p === "/api/zmsq/available" && req.method === "GET") {
      return withSession(res, () => jw.getZmAvailable());
    }

    if (p === "/api/zmsq/apply" && req.method === "POST") {
      const { zmids, form } = await readBody(req);
      if (!Array.isArray(zmids) || !zmids.length) return send(res, 400, { error: "请选择要申请的证明" });
      return withSession(res, async () => {
        const r = await jw.applyZm(zmids, form || {});
        return { ok: r.success === true || r.code === "0", message: r.msg || (r.success ? "" : "申请失败"), raw: r };
      });
    }

    if (p === "/api/zmsq/download" && req.method === "GET") {
      const wid = u.searchParams.get("wid");
      if (!wid) return send(res, 400, { error: "缺少 wid" });
      try {
        const f = await jw.downloadZm(wid);
        if (f.contentType.includes("application/json")) {
          // 无效证明等: 返回 JSON 错误
          return send(res, 200, f.data, { "Content-Type": "application/json; charset=utf-8" });
        }
        const name = /filename\*?="?([^";]+)"?/i.exec(f.disposition)?.[1] || "证明文件";
        return send(res, 200, f.data, {
          "Content-Type": f.contentType,
          "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
        });
      } catch (e) {
        if (e.code === "SESSION_EXPIRED") send(res, 401, { error: "SESSION_EXPIRED", message: e.message });
        else send(res, 500, { error: e.message });
      }
      return;
    }

    if (p === "/api/zmsq/email" && req.method === "POST") {
      const { wids, mail } = await readBody(req);
      if (!Array.isArray(wids) || !wids.length || !mail) return send(res, 400, { error: "参数不完整" });
      return withSession(res, async () => {
        const r = await jw.emailZm(wids, mail);
        return { ok: r.success === true || r.code === "0", message: r.msg || "" };
      });
    }

    if (p === "/api/jdfpm/print" && req.method === "GET") {
      try {
        const html = await jw.printJdfpm(
          u.searchParams.get("wid"),
          u.searchParams.get("lx") || "1",
          u.searchParams.get("dynr") || "1",
          u.searchParams.get("sfxypm") || "0"
        );
        send(res, 200, html, { "Content-Type": "text/html; charset=utf-8" });
      } catch (e) {
        if (e.code === "SESSION_EXPIRED") send(res, 401, { error: "SESSION_EXPIRED", message: e.message });
        else send(res, 500, { error: e.message });
      }
      return;
    }

    // ---------- 静态文件 ----------
    let filePath = path.join(WEB_ROOT, p === "/" ? "index.html" : p);
    if (!filePath.startsWith(WEB_ROOT)) return send(res, 403, "Forbidden");
    if (!fs.existsSync(filePath) || fs.statSync(filePath).isDirectory()) {
      filePath = path.join(WEB_ROOT, "index.html"); // SPA 回退
    }
    const ext = path.extname(filePath).toLowerCase();
    fs.readFile(filePath, (err, data) => {
      if (err) return send(res, 404, "Not Found");
      send(res, 200, data, {
        "Content-Type": MIME[ext] || "application/octet-stream",
        "Cache-Control": "no-store", // 本地工具: 始终拿最新代码
      });
    });
  } catch (e) {
    console.error("[server]", e);
    send(res, 500, { error: e.message });
  }
});

server.listen(PORT, () => {
  console.log(`\n  教务系统前端重写  已启动`);
  console.log(`  打开 http://localhost:${PORT}\n`);
});
