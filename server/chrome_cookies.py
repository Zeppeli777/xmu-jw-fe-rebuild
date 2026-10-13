# 从本机 Chrome 提取 jw.xmu.edu.cn 的完整 cookie(含 HttpOnly), 供本地服务一键导入登录态。
# 原理: 复制最小 profile(Local State + Network/Cookies)到临时目录, 用 headless Chrome 打开,
# 通过 CDP Storage.getCookies 取明文(Chrome 自行完成解密, 真实 profile 不被修改)。
# 输出: JSON { "cookies": "name=value; ...", "count": N }
import http.client
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time

CHROME_USER_DATA = os.path.join(os.environ["LOCALAPPDATA"], "Google", "Chrome", "User Data")
CHROME_PROFILE = "Default"
CHROME_EXE = os.path.join(os.environ["PROGRAMFILES"], "Google", "Chrome", "Application", "chrome.exe")
if not os.path.exists(CHROME_EXE):
    CHROME_EXE = os.path.join(os.environ["LOCALAPPDATA"], "Google", "Chrome", "Application", "chrome.exe")

TARGET_HOSTS = ("jw.xmu.edu.cn", "ids.xmu.edu.cn")


def ws_call(ws_url, method, params=None, msg_id=1):
    """最小 CDP WebSocket 客户端(仅文本帧, 足够用)"""
    import base64
    import hashlib
    import socket
    import struct

    # 解析 ws://host:port/path
    rest = ws_url[len("ws://"):]
    hostport, _, path = rest.partition("/")
    host, _, port = hostport.partition(":")
    sock = socket.create_connection((host, int(port or 80)), timeout=10)
    key = base64.b64encode(os.urandom(16)).decode()
    req = (
        f"GET /{path} HTTP/1.1\r\nHost: {hostport}\r\nUpgrade: websocket\r\n"
        f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n"
    )
    sock.sendall(req.encode())
    resp = b""
    while b"\r\n\r\n" not in resp:
        resp += sock.recv(4096)
    if b"101" not in resp.split(b"\r\n", 1)[0]:
        raise RuntimeError("WebSocket 握手失败: " + resp.split(b"\r\n", 1)[0].decode(errors="replace"))

    payload = json.dumps({"id": msg_id, "method": method, "params": params or {}})
    data = payload.encode()
    header = bytearray([0x81])  # FIN + text
    mask = os.urandom(4)
    n = len(data)
    if n < 126:
        header.append(0x80 | n)
    elif n < 65536:
        header.append(0x80 | 126)
        header += struct.pack(">H", n)
    else:
        header.append(0x80 | 127)
        header += struct.pack(">Q", n)
    header += mask
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    sock.sendall(bytes(header) + masked)

    # 读帧(假设单帧且不大)
    buf = b""
    while len(buf) < 2:
        buf += sock.recv(4096)
    b1, b2 = buf[0], buf[1]
    length = b2 & 0x7F
    idx = 2
    if length == 126:
        while len(buf) < idx + 2:
            buf += sock.recv(4096)
        length = struct.unpack(">H", buf[idx:idx + 2])[0]
        idx += 2
    elif length == 127:
        while len(buf) < idx + 8:
            buf += sock.recv(4096)
        length = struct.unpack(">Q", buf[idx:idx + 8])[0]
        idx += 8
    while len(buf) < idx + length:
        buf += sock.recv(65536)
    frame = buf[idx:idx + length]
    sock.close()
    return json.loads(frame.decode("utf-8", errors="replace"))


def main():
    workdir = tempfile.mkdtemp(prefix="jw-cookie-")
    profile_dir = os.path.join(workdir, "profile")
    os.makedirs(os.path.join(profile_dir, CHROME_PROFILE, "Network"), exist_ok=True)

    def copy_if_exists(src, dst):
        if os.path.exists(src):
            shutil.copy2(src, dst)

    copy_if_exists(os.path.join(CHROME_USER_DATA, "Local State"), os.path.join(profile_dir, "Local State"))
    copy_if_exists(os.path.join(CHROME_USER_DATA, CHROME_PROFILE, "Preferences"),
                   os.path.join(profile_dir, CHROME_PROFILE, "Preferences"))
    for name in ("Cookies", "Cookies-wal", "Cookies-shm"):
        copy_if_exists(os.path.join(CHROME_USER_DATA, CHROME_PROFILE, "Network", name),
                       os.path.join(profile_dir, CHROME_PROFILE, "Network", name))

    cookies_db = os.path.join(profile_dir, CHROME_PROFILE, "Network", "Cookies")
    if not os.path.exists(cookies_db):
        print(json.dumps({"error": "找不到 Chrome Cookies 数据库(Chrome 未安装或未登录过?)"}))
        return

    db_rows = -1
    try:
        con = sqlite3.connect(cookies_db)
        try:
            db_rows = con.execute("SELECT count(*) FROM cookies").fetchone()[0]
        finally:
            con.close()
    except sqlite3.Error:
        pass

    proc = subprocess.Popen(
        [CHROME_EXE, "--headless=new", "--user-data-dir=" + profile_dir,
         "--remote-debugging-port=0", "--no-first-run", "--no-default-browser-check",
         "--disable-sync", "--disable-background-networking", "about:blank"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    try:
        port = None
        port_file = os.path.join(profile_dir, "DevToolsActivePort")
        for _ in range(60):
            if proc.poll() is not None:
                print(json.dumps({"error": "headless Chrome 提前退出"}))
                return
            if os.path.exists(port_file):
                with open(port_file, "r") as f:
                    text = f.read().strip().splitlines()
                if text and text[0].isdigit():
                    port = int(text[0])
                    break
            time.sleep(0.5)
        if port is None:
            print(json.dumps({"error": "等待 Chrome 调试端口超时"}))
            return

        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
        conn.request("GET", "/json/version")
        info = json.loads(conn.getresponse().read().decode("utf-8"))
        ws_url = info["webSocketDebuggerUrl"]

        cookies = None
        for _ in range(6):
            try:
                result = ws_call(ws_url, "Storage.getCookies")
                cookies = result.get("result", {}).get("cookies", [])
            except Exception:
                cookies = None
            if cookies:
                break
            if db_rows <= 0:
                break
            time.sleep(1.5)

        if not cookies:
            print(json.dumps({"error": "未能通过 CDP 获取 cookie"}))
            return

        picked = [c for c in cookies if any(h in c.get("domain", "") for h in TARGET_HOSTS)]
        if not picked:
            print(json.dumps({"error": "Chrome 中没有 jw.xmu.edu.cn 的登录态, 请先在 Chrome 登录教务系统"}))
            return
        cookie_str = "; ".join(f"{c['name']}={c['value']}" for c in picked if c.get("value"))
        print(json.dumps({"cookies": cookie_str, "count": len(picked),
                          "hosts": sorted({c.get("domain", "") for c in picked})}))
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except Exception:
            proc.kill()
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    main()
