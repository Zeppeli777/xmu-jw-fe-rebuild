// CAS 统一身份认证登录(厦门大学 ids.xmu.edu.cn)
// 密码加密方式与学校登录页一致: AES-128-CBC, key = pwdEncryptSalt(16字节), iv = 随机16字符, 明文 = 随机64字符 + 密码
import crypto from "node:crypto";

const CHARS = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";
const rnd = (n) => Array.from({ length: n }, () => CHARS[crypto.randomInt(CHARS.length)]).join("");

const CAS_HOST = "https://ids.xmu.edu.cn";
const UA =
  "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

/** 与学校登录页 encryptPassword 等价的密码加密 */
export function encryptPassword(password, salt) {
  const key = Buffer.from(String(salt).trim(), "utf8");
  const iv = Buffer.from(rnd(16), "utf8");
  const data = Buffer.from(rnd(64) + password, "utf8");
  const c = crypto.createCipheriv("aes-128-cbc", key, iv);
  return Buffer.concat([c.update(data), c.final()]).toString("base64");
}

/**
 * 登录流程:
 * 1. GET CAS 登录页(带 service), 拿到 salt/lt/execution 和 CAS 会话 cookie
 * 2. POST 用户名+加密密码(+验证码)
 * 3. 成功时 CAS 302 到 service?ticket=ST-xxx, 业务系统(教务)校验 ticket 后种下自己的会话 cookie
 */
export async function casLogin({ username, password, captcha, captchaCookie, service }) {
  const svc = service || "https://jw.xmu.edu.cn/jwapp/sys/emapangular/index.do";
  const loginUrl = `${CAS_HOST}/authserver/login?service=${encodeURIComponent(svc)}`;

  // 1. 取登录页
  const r1 = await fetch(loginUrl, { redirect: "manual", headers: { "User-Agent": UA } });
  const html = await r1.text();
  const jar = new Map();
  for (const c of r1.headers.getSetCookie()) addCookie(jar, c);
  // 验证码会话可能来自上一次请求的 cookie
  if (captchaCookie) for (const c of captchaCookie.split(";")) addCookie(jar, c.trim());

  const salt = html.match(/id="pwdEncryptSalt"\s+value="([^"]*)"/)?.[1];
  const lt = html.match(/name="lt"\s+id="lt"\s+value="([^"]*)"/)?.[1] ?? "";
  const execution = html.match(/name="execution"\s+value="([^"]*)"/)?.[1] ?? "e1s1";
  if (!salt) throw new Error("无法解析 CAS 登录页(可能网络异常或登录页已改版)");

  // 2. 提交登录
  const form = new URLSearchParams({
    username,
    password: encryptPassword(password, salt),
    lt,
    execution,
    _eventId: "submit",
    cllt: "userNameLogin",
    dllt: "generalLogin",
  });
  if (captcha) form.set("captcha", captcha);

  const r2 = await fetch(`${CAS_HOST}/authserver/login`, {
    method: "POST",
    redirect: "manual",
    headers: {
      "Content-Type": "application/x-www-form-urlencoded",
      Cookie: cookieHeader(jar),
      "User-Agent": UA,
      Referer: loginUrl,
      Origin: CAS_HOST,
    },
    body: form,
  });
  const body2 = await r2.text();
  for (const c of r2.headers.getSetCookie()) addCookie(jar, c);

  const captchaMatch = body2.match(/<div class="([^"]*)"[^>]*id="captchaDiv"/);
  const needCaptcha = !!captchaMatch && !captchaMatch[1].includes("hide");
  const errMsg = body2.match(/id="showErrorTip"[^>]*>[\s\S]*?>([^<]+)</)?.[1];

  if (r2.status === 302) {
    const loc = r2.headers.get("location") || "";
    // 3. 跟随 ticket 跳转, 让教务系统种下会话 cookie
    const chain = [];
    let url = loc.startsWith("http") ? loc : CAS_HOST + loc;
    for (let i = 0; i < 8 && url; i++) {
      const rr = await fetch(url, { redirect: "manual", headers: { "User-Agent": UA, Cookie: cookieHeader(jar) } });
      for (const c of rr.headers.getSetCookie()) addCookie(jar, c);
      chain.push({ url, status: rr.status });
      const next = rr.headers.get("location");
      if (rr.status >= 300 && rr.status < 400 && next) {
        url = new URL(next, url).href;
        continue;
      }
      // 到达最终页面, 消费 body 关闭连接
      await rr.arrayBuffer().catch(() => {});
      break;
    }
    return { ok: true, jar, chain };
  }

  if (needCaptcha) {
    return { ok: false, needCaptcha: true, message: errMsg || "需要输入验证码", casCookie: cookieHeader(jar) };
  }
  return { ok: false, needCaptcha: false, message: errMsg || "登录失败, 请检查学号和密码" };
}

/** 获取验证码图片(需要携带 CAS 会话 cookie) */
export async function fetchCaptcha(casCookie) {
  const jar = new Map();
  for (const c of String(casCookie || "").split(";")) addCookie(jar, c.trim());
  const r = await fetch(`${CAS_HOST}/authserver/captcha.do`, {
    headers: { Cookie: cookieHeader(jar), "User-Agent": UA, Referer: `${CAS_HOST}/authserver/login` },
  });
  const buf = Buffer.from(await r.arrayBuffer());
  return { contentType: r.headers.get("content-type") || "image/png", data: buf };
}

// ---------- cookie 工具 ----------

export function addCookie(jar, setCookie) {
  if (!setCookie) return;
  const [pair] = setCookie.split(";");
  const idx = pair.indexOf("=");
  if (idx < 0) return;
  const name = pair.slice(0, idx).trim();
  const value = pair.slice(idx + 1).trim();
  const domain = /domain=([^;]+)/i.exec(setCookie)?.[1]?.trim().replace(/^\./, "") || "";
  const path = /path=([^;]+)/i.exec(setCookie)?.[1]?.trim() || "/";
  jar.set(name + "|" + domain + "|" + path, { name, value, domain, path });
}

export function cookieHeader(jar, host = "") {
  const parts = [];
  for (const c of jar.values()) {
    if (!c.value) continue;
    if (host && c.domain && !host.endsWith(c.domain)) continue;
    parts.push(`${c.name}=${c.value}`);
  }
  return parts.join("; ");
}

export function serializeJar(jar) {
  return JSON.stringify([...jar.values()]);
}

export function deserializeJar(str) {
  const jar = new Map();
  try {
    for (const c of JSON.parse(str)) jar.set(c.name + "|" + c.domain + "|" + c.path, c);
  } catch {}
  return jar;
}
