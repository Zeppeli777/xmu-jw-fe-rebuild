import sqlite3, shutil, os, tempfile, glob

# 找 Chrome 历史(可能多个 profile)
candidates = [
    os.path.expanduser("~/AppData/Local/Google/Chrome/User Data/Default/History"),
    os.path.expanduser("~/AppData/Local/Google/Chrome/User Data/Profile 1/History"),
]
src = next((p for p in candidates if os.path.exists(p)), None)
if not src:
    print("NO_HISTORY")
else:
    tmp = os.path.join(tempfile.gettempdir(), "chist.db")
    shutil.copy2(src, tmp)
    con = sqlite3.connect(tmp)
    rows = con.execute(
        "SELECT url, title FROM urls WHERE url LIKE '%jw.xmu.edu.cn%' GROUP BY url ORDER BY last_visit_time DESC LIMIT 200"
    ).fetchall()
    con.close()
    os.unlink(tmp)
    for url, title in rows:
        if "/jwapp/sys/" in url:
            print("APP:", url.split("?")[0], "|", (title or "")[:30])
    # 也打印所有 jwapp 相关的去重 syscode
    codes = set()
    for url, _ in rows:
        import re
        m = re.search(r"/jwapp/sys/(\w+)/", url)
        if m:
            codes.add(m.group(1))
    print("SYSCODES:", sorted(codes))
