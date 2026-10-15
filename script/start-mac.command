#!/bin/bash
# 教务系统一键启动(macOS): 自动找 Node.js, 起服务, 打开浏览器
# 双击本文件即可运行; 若系统提示无法打开, 先在终端执行: chmod +x script/start-mac.command

cd "$(dirname "$0")/.." || exit 1

echo ""
echo "  ============================================"
echo "   教务系统前端重写 - 一键启动"
echo "  ============================================"
echo ""

# ---- 1. 找 Node.js(GUI 启动的终端 PATH 可能不全, 补几个常见位置) ----
find_node() {
  if command -v node >/dev/null 2>&1; then command -v node; return 0; fi
  for p in \
    "$HOME/.nvm/versions/node"/*/bin/node \
    /opt/homebrew/bin/node \
    /usr/local/bin/node \
    /usr/bin/node; do
    [ -x "$p" ] && { echo "$p"; return 0; }
  done
  return 1
}

NODE_BIN="$(find_node)"
if [ -z "$NODE_BIN" ]; then
  echo "  [未找到 Node.js]"
  echo ""
  echo "  本机还没有安装 Node.js, 需要先装一下:"
  echo "    1. 打开 https://nodejs.org/ 下载 LTS 版(.pkg, 一直点继续即可)"
  echo "    2. 装完后重新双击本脚本"
  echo ""
  echo "  正在为你打开 Node.js 官网..."
  open https://nodejs.org/ || echo "  请手动打开 https://nodejs.org/"
  echo ""
  printf "  按回车键退出..."; read -r _
  exit 1
fi
echo "  已找到 Node.js: $("$NODE_BIN" -v)"

# ---- 2. 端口被占用就提醒 ----
if lsof -nP -iTCP:8787 -sTCP:LISTEN >/dev/null 2>&1; then
  echo ""
  echo "  [提示] 8787 端口已被占用, 服务可能已经在运行。"
  echo "  直接打开浏览器看看, 不行就关掉其它占用该端口的程序再重试。"
  echo ""
  open http://localhost:8787
  printf "  按回车键退出..."; read -r _
  exit 0
fi

# ---- 3. 后台启动服务(脱离本终端, 关掉窗口也不会停), 等它就绪 ----
echo "  正在启动服务..."
nohup "$NODE_BIN" server/index.js >/dev/null 2>&1 &
SERVER_PID=$!
echo "  服务进程 PID: $SERVER_PID"

for _ in $(seq 1 30); do
  if curl -s -o /dev/null --max-time 2 http://localhost:8787/api/session; then break; fi
  sleep 1
done

if ! curl -s -o /dev/null --max-time 2 http://localhost:8787/api/session; then
  echo ""
  echo "  [启动超时] 请把上面显示的任何错误信息记下来反馈。"
  echo ""
  printf "  按回车键退出..."; read -r _
  exit 1
fi

echo "  服务已就绪, 正在打开浏览器..."
open http://localhost:8787

echo ""
echo "  ============================================"
echo "   已启动! 浏览器应该已经打开了。"
echo ""
echo "   以后要用: 直接双击本脚本即可。"
echo "   要停止服务, 在终端执行:"
echo "     kill $SERVER_PID"
echo "  ============================================"
echo ""
printf "  按回车键关闭本窗口(服务会继续在后台运行)..."; read -r _
exit 0
