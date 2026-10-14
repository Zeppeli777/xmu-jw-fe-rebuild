// cookie 工具: Set-Cookie 解析/序列化/请求头生成。
// 原 CAS 登录(学号密码)实现已移除, 登录改走"官方登录页 + 票据交换"与 Chrome/Cookie 导入。

// 设置序号: 同名 cookie 轮换时(如 _WEU 按 Path 写入新值)只发最新的一条
let cookieSeq = 0;

export function addCookie(jar, setCookie) {
  if (!setCookie) return;
  const [pair] = setCookie.split(";");
  const idx = pair.indexOf("=");
  if (idx < 0) return;
  const name = pair.slice(0, idx).trim();
  const value = pair.slice(idx + 1).trim();
  const domain = /domain=([^;]+)/i.exec(setCookie)?.[1]?.trim().replace(/^\./, "") || "";
  const path = /path=([^;]+)/i.exec(setCookie)?.[1]?.trim() || "/";
  jar.set(name + "|" + domain + "|" + path, { name, value, domain, path, seq: ++cookieSeq });
}

export function cookieHeader(jar, host = "") {
  // 同名 cookie 只发最新设置的那条。服务端轮换 _WEU 时按 Path 写入新值, 旧 Path 的
  // 条目(如导入 cookie 时丢失 Path 信息)若同时发出, 服务端可能取到旧令牌导致 403
  const latest = new Map();
  for (const c of jar.values()) {
    if (!c.value) continue;
    if (host && c.domain && !host.endsWith(c.domain)) continue;
    const prev = latest.get(c.name);
    if (!prev || (c.seq ?? 0) >= (prev.seq ?? 0)) latest.set(c.name, c);
  }
  return [...latest.values()].map((c) => `${c.name}=${c.value}`).join("; ");
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
