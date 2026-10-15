# 教务系统前端重写(厦门大学 · 本地版)

用 Node 本地代理 + 原生前端重写教务系统(jw.xmu.edu.cn)的**学业完成查询**、**GPA 排名**与**证明文件申请**三个页面。
视觉与交互全面重做, 响应式适配手机/平板/桌面, 数据全部实时来自教务系统官方接口。

**仅限个人本机自用**: 只访问自己的数据, 不面向他人提供服务。
本仓库仅为实现便捷性使用的最小目的, 有更追求美观个性化的朋友欢迎 fork 魔改。

## 背景

教务系统原前端(金智 EMAP)在几个高频场景下体验不佳: 交互困难、兼容性差(移动端基本不可用)、页面加载缓慢。
因此把这几个**常用但难用**的页面拿到本地重写: 界面和交互全部重做, 手机/平板/桌面全适配, 数据实时取自官方接口;
服务只跑在本机, 不改动学校侧任何东西, 也不需要安装额外依赖。

## 快速开始

### 懒人一键启动(不用命令行)

| 系统 | 双击这个文件 |
|---|---|
| Windows | `script\start-win.bat` |
| macOS | `script\start-mac.command` |

脚本会自动做三件事: 找本机 Node.js(没装会提示并打开官网)→ 启动服务 → 自动打开浏览器。
macOS 上如果双击没反应, 先在终端执行一次 `chmod +x script/start-mac.command`。

### 或者手动启动

```bash
# 1. 启动(零依赖, 不需要 npm install)
node server/index.js

# 2. 浏览器打开
http://localhost:8787
```

> 需要 Node.js 18+。没有的话去 https://nodejs.org/ 下载 LTS 版, 一直点下一步即可。

首次使用需登录(三选一):

| 方式 | 说明 |
|---|---|
| **官方登录**(推荐) | 跳转学校官方统一身份认证页面登录, 加密/验证码/短信全部由学校页面处理。登录成功后落到 jw.xmu.edu.cn/new/index.html(静态门户, 不消费票据), 地址栏带 `?ticket=ST-...`, 用登录页提供的书签工具一键返回, 或复制网址回本页粘贴同步 |
| **Chrome 导入** | 复制 Chrome 配置档案, 由 headless Chrome 自行解密提取登录态(不影响 Chrome 运行, Windows/macOS 均支持)。需先在 Chrome 里登录过教务系统; 若提示未找到登录态, 请关闭 Chrome 后重试 |
| **Cookie 粘贴** | 浏览器登录教务系统后, F12 → 应用/Application → Cookies → jw.xmu.edu.cn, 列表里 Shift 多选复制, 直接粘贴(表格格式自动识别, 无需手动整理) |

登录态保存在 `server/.session.json`, 重启服务无需重复登录; 失效后前端会提示重新登录。

## 功能

### 学业完成查询
- 方案完成度环形图 + 已获/要求/未修/方案外学分总览
- 模块 → 子模块 → 课组 多级树, 逐级展开, 每级显示要求/已修学分与进度条、通过状态
- 课组下课程明细: 课程号/名称/学分/成绩(按分数段着色)/状态/修读学期/标签
- 「全部课程」视图: 按状态(通过/在修/未通过/未修读)、类别筛选, 课程名/课程号搜索
- 刷新按钮重新从教务系统拉取

### GPA 排名
- 学生信息卡 + 当前有效 GPA 大数字卡片(GPA/加权平均分/算术平均分/计算门数)
- GPA 变化趋势图(历次申请记录, 纯 SVG 绘制)
- 申请计算记录列表, 展开可按学期分组查看参与计算的成绩明细(成绩/绩点/学分/初修重修)
- 新增绩点计算(选择成绩范围, 服务端重新计算)
- 打印绩点证明(类型/内容/排名可选, 新窗口打开学校证明页)

### 证明文件申请
- 我的申请列表: 证明名称(中英文)/申请时间/有效状态/截止时间/打印份数, 按有效/过期筛选与搜索
- 可申请证明卡片墙(在学证明/成绩单/绩点证明/四六级成绩证明等 12 类)
- 在线申请: 选择证明 → 填写打印份数/是否自助机打印/是否随机码 → 提交
- 有效证明一键下载 PDF、发送到邮箱

### 体验
- 深/浅色双主题(记忆选择), 桌面侧栏 + 移动端底部导航
- 骨架屏、数字滚动、进度条动画、Toast 提示
- 全程响应式: 375px 手机到宽屏桌面

## 目录结构

```
xmu-jw-fe-rebuild/
├── server/                 # 本地代理服务(零依赖, Node 18+)
│   ├── index.js            # HTTP 服务: 静态文件 + /api 路由 + mock 模式
│   ├── cas.js              # cookie 工具(Set-Cookie 解析/同名去重/序列化)
│   ├── jw.js               # 教务系统 API 客户端(会话/令牌轮换/角色/业务接口)
│   ├── chrome_cookies.py   # 从本机 Chrome 提取登录态(复制 profile 解密, Windows/macOS)
│   ├── chrome_history.py   # 从 Chrome 历史发现应用入口地址(辅助)
│   └── .session.json       # 登录态持久化(自动生成, 已在 .gitignore)
├── script/                 # 懒人一键启动脚本(不用命令行)
│   ├── start-win.bat       # Windows: 双击即用
│   └── start-mac.command   # macOS: 双击即用(已带可执行位)
├── android/                # 安卓 App(WebView 壳 + Kotlin 内置服务端)
│   ├── app/src/main/java/com/xmu/jw/
│   │   ├── MainActivity.kt # 全屏 WebView + 请求拦截 + 官方登录票据捕获
│   │   ├── ApiServer.kt    # /api 路由层(移植自 server/index.js)
│   │   ├── JsBridge.kt     # 原生能力: 打开 CAS 登录页 / 存 PDF / 打开学校打印页
│   │   ├── Assets.kt       # 静态资源服务 + 注入 fetch 包装(POST 体走 X-JW-Body 头)
│   │   ├── Downloads.kt    # MediaStore 保存 PDF
│   │   ├── DemoData.kt     # 演示模式(仅 debug): 用 analysis 样本渲染界面
│   │   └── core/           # 纯 JVM 层: HTTP/cookie/教务客户端(与 server/*.js 一一对应)
│   └── tools/demo-proof.pdf# 演示模式用的示例 PDF
├── web/                    # 前端(原生 HTML/CSS/JS, 无构建)
│   ├── index.html          # 应用壳: 侧栏/登录层/路由容器
│   └── assets/
│       ├── app.css         # 设计系统(主题变量/组件/响应式)
│       ├── app.js          # 核心: 主题/登录/路由/API/工具
│       ├── xywccx.js       # 学业完成查询页
│       ├── jdfpm.js        # GPA 排名页
│       ├── zmsq.js         # 证明文件申请页
│       └── main.js         # 入口
└── analysis/               # 接口逆向分析资料(数据样本 + 说明)
```

## 环境变量

| 变量 | 说明 |
|---|---|
| `PORT` | 服务端口, 默认 `8787` |
| `MOCK=1` | 使用 analysis/ 中已抓取的数据样本, 不访问教务系统(前端开发用) |
| `DEBUG=1` | 打印每次上游请求的状态码与令牌长度 |
| `PYTHON` | Chrome cookie 提取用的 Python 路径, 默认自动探测(`python3`/`python`/`py`) |

## 实现说明(接口逆向要点)

教务系统为金智 EMAP 平台, 前端重写需要复刻几个非显而易见的机制, 详见 `analysis/README.md`:

1. **登录方式**: 不自行复刻 CAS 表单提交(验证码/风控/限流规则多变, 极易失效), 而是引导至学校官方登录页完成认证, 再经静态门户把票据(ticket)带回本服务交换会话; 也支持从本机 Chrome 直接导入登录态。学校登录页的密码加密为 `AES-128-CBC`(key=页面下发的 16 位 salt, iv 随机, 明文=随机 64 字符+密码)——本项目不再使用, 仅作背景记录。
2. **_WEU 令牌轮换**: 会话令牌 `_WEU` 在每个响应中轮换, 客户端必须始终使用最新值; 服务端将所有上游请求串行执行避免竞态, 且**同名 cookie 只发送最新设置的那条**(轮换按 Path 写入新值时, 旧条目若同时发出会导致 403)。
3. **会话激活**: 新登录的会话令牌处于"未激活"态(调用业务 API 返回 403), 需先请求 `/jwapp/sys/jwai/index.do` 等端点激活。
4. **应用角色**: 各应用按角色鉴权, 新会话必须经 `changeAppRole` + `setJwCommonAppRole` 设置角色(本科生), 否则业务 API 403。注意每个应用有各自的角色(证明文件申请是"证明申请本科生")。
5. **方案 ID 发现**: 学业完成查询先用 `cxxsscfa.do`(空参数, 服务端按会话取当前学生)拿到培养方案 ID(PYFADM), 再查课组与课程。
6. **Referer 校验**: EMAP 对码表/保存类端点校验 Referer 必须为对应的应用页; 且同应用的列表接口在 `modules/<路由>/` 下、动作接口在应用根 `/<路由>/` 下。

## 安卓 App(本分支内容)

电脑版要在电脑上跑 `node server/index.js`; 安卓版把同一套服务端逻辑用 Kotlin 移植进了 App 内部
(`android/app/src/main/java/com/xmu/jw/core/` 对应 `server/` 下的 js), 手机里不需要 node、也不需要连电脑:
WebView 加载内置的 `web/` 前端, `/api/*` 请求由 App 内的服务端直接访问教务系统。
登录态存在 App 私有目录, 与电脑版互不影响(同一会话在两端同时用会互相顶掉令牌, 属正常现象)。

### 构建 APK

```bash
cd android
./gradlew assembleDebug          # 产物: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

装到手机: 把 `app-debug.apk` 通过微信/QQ/数据线传到手机, 点开安装(需允许"安装未知应用")。
要求: JDK 17+、Android SDK(platforms;android-36, build-tools 36.1.0), 首次构建需联网下载 AGP/Kotlin 插件。
`android/local.properties` 里写死本机 SDK 路径(不入库), 换机器要改这一行。
前端改完不用手动同步: Gradle 的 `syncWeb` 任务每次构建都会把 `../web` 拷进 assets。

### 登录方式(与电脑版的差异)

电脑版靠"浏览器 + 书签工具"把票据带回本应用; 安卓端没这个问题——**官方登录直接在应用内的 WebView 里打开学校登录页**,
用户用什么方式登录都行(账号密码/扫码/短信), 登录成功后学校 302 跳回静态门户, 地址栏里的 `?ticket=ST-...`
被应用拦下来换成本地会话, 然后自动回到主界面。不需要书签工具, 也不需要复制粘贴。

| | 电脑版(node) | 安卓 App |
|---|---|---|
| 登录方式 | 官方登录(书签工具带回票据) / Chrome 导入 / Cookie | **官方登录(应用内 WebView, 自动捕获票据)** / Cookie 粘贴 |
| Chrome 导入 | 复制 profile 由 headless Chrome 解密 | 不支持(读不到其他 App 的 cookie 库) |
| 证明 PDF 下载 | 浏览器下载 | 原生保存到系统「下载」目录(WebView 不支持 blob 下载) |
| 绩点证明打印 | 新窗口打开 | 原生带上会话直接打开学校打印页, 返回键回到 App |
| 登录态 | `server/.session.json` | App 私有 SharedPreferences |

前端里 `window.JWAndroid` 存在时走原生分支, 不存在时(电脑版)行为完全不变。

### 演示模式(仅 debug 包)

没登录态也能看界面: 往 App 里放一个标记文件, 重启后用 `analysis/` 里抓包的真实样本数据渲染三个页面。

```bash
adb shell "run-as com.xmu.jw sh -c 'mkdir -p files && echo x > files/jw-demo.on'"
```

### 单元测试

`android/app/src/test/` 下有一套 JVM 单测: 用本地桩服务模拟 EMAP + CAS,
逐条断言请求参数、Referer、_WEU 令牌轮换、同名 cookie 去重、403 预热重试、票据交换失败不清会话、
返回给前端的 JSON 形状(不联网、不用真实账号), 另有少量不打登录态的真实网络用例。

```bash
cd android && ./gradlew testDebugUnitTest
```

## 隐私与边界

- 所有数据流向: 浏览器 → localhost 代理 → 教务系统官方接口, 无任何第三方服务器
- 登录态(.session.json)仅存本机, 包含会话 cookie, 不要分享或提交到 git
- 本项目为个人学习用途, 与厦门大学无关; 接口可能随学校系统升级失效, 届时需重新分析
