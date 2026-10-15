@echo off
setlocal
rem 教务系统一键启动(Windows): 自动找 Node.js, 起服务, 打开浏览器
rem 注意: 本文件按 GBK 编码保存(中文 Windows 默认代码页), 不要转成 UTF-8, 否则中文会变乱码

cd /d "%~dp0.."

echo.
echo   ============================================
echo    教务系统前端重写 - 一键启动
echo   ============================================
echo.

rem ---- 1. 找 Node.js ----
where node >nul 2>nul
if errorlevel 1 (
  echo   [未找到 Node.js]
  echo.
  echo   本机还没有安装 Node.js, 需要先装一下:
  echo     1. 打开 https://nodejs.org/ 下载 LTS 版, 一直点下一步即可
  echo     2. 装完后关掉这个窗口, 重新双击本脚本
  echo.
  echo   正在为你打开 Node.js 官网...
  start "" https://nodejs.org/
  echo.
  pause
  exit /b 1
)

for /f "tokens=*" %%v in ('node -v') do echo   已找到 Node.js %%v

rem ---- 2. 端口被占用就提醒 ----
netstat -ano | findstr ":8787" | findstr "LISTENING" >nul 2>nul
if not errorlevel 1 (
  echo.
  echo   [提示] 8787 端口已被占用, 服务可能已经在运行。
  echo   直接打开浏览器看看, 不行就关掉其它占用该端口的程序再重试。
  echo.
  start "" http://localhost:8787
  pause
  exit /b 0
)

rem ---- 3. 后台启动服务, 等它就绪 ----
echo   正在启动服务...
set "JWLOG=%TEMP%\xmu-jw-server.log"
start "" /b node server\index.js > "%JWLOG%" 2>&1

where curl >nul 2>nul
if errorlevel 1 (
  rem 老版本 Windows 没有 curl, 等 2 秒直接开浏览器
  timeout /t 2 /nobreak >nul
  goto ready
)

set /a tries=0
:wait
curl -s -o nul --max-time 2 http://localhost:8787/api/session >nul 2>nul
if not errorlevel 1 goto ready
set /a tries+=1
if %tries% geq 30 (
  echo.
  echo   [启动失败] 服务没有起来, 错误日志在:
  echo   %JWLOG%
  echo.
  pause
  exit /b 1
)
timeout /t 1 /nobreak >nul
goto wait

:ready
echo   服务已就绪, 正在打开浏览器...
start "" http://localhost:8787
echo.
echo   ============================================
echo    已启动! 浏览器应该已经打开了。
echo.
echo    以后要用: 直接双击本脚本即可。
echo    要停止: 关闭本窗口。
echo    服务日志: %JWLOG%
echo   ============================================
echo.
pause
