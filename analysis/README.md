# 接口逆向分析(教务系统 jw.xmu.edu.cn · 金智 EMAP 平台)

## 认证机制

### CAS 统一身份认证(ids.xmu.edu.cn)

```
GET  https://ids.xmu.edu.cn/authserver/login?service=<业务系统URL>
     → 登录页(含 pwdEncryptSalt / execution / lt)
POST https://ids.xmu.edu.cn/authserver/login
     → 302 到 service?ticket=ST-xxx
     → 业务系统校验 ticket, 种下自己的会话 cookie
```

**密码加密**(与学校登录页 `encrypt.js` 完全一致):

```
password_field = Base64(AES-128-CBC(
  key  = pwdEncryptSalt(16字符, 登录页下发),
  iv   = 随机16字符(字符集 ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678),
  data = 随机64字符 + 明文密码
))
```

失败后同一会话会被要求输入验证码; 全新会话无验证码。服务器对登录失败次数可能有限制, 不要暴力尝试。

### 会话 cookie(jw.xmu.edu.cn)

| Cookie | 说明 |
|---|---|
| `GS_SESSIONID` | HttpOnly, path=/jwapp/, 真正的服务端会话 ID |
| `_WEU` | 轮换令牌, path=/jwapp/, 每个响应都会下发新值 |
| `route` / `route_amp` | 负载均衡粘性 |
| `EMAP_LANG` / `amp.locale` | 语言(zh 中文), 不设置则 `_DISPLAY` 字段返回英文 |

## 关键机制(踩坑记录)

### 1. _WEU 令牌轮换

`_WEU` 在**每个**响应中轮换, 客户端必须始终使用最新值。并发请求会因令牌竞态导致 403。
→ 服务端将所有上游请求**串行执行**(队列), 且每个响应都更新 cookie。

### 2. 新会话激活(否则 403)

新登录(或票据交换)得到的 `_WEU` 约 113 字符, 是"未激活"态, 调用任何业务 API 都返回 **403**。
按顺序访问以下端点后令牌升级(约 190+ 字符), 业务 API 恢复:

```
GET  /jwapp/sys/jwai/*default/index.do
GET  /jwapp/sys/jwai/index.do            ← 升级发生在这
POST /jwapp/sys/jwai/api/user/getCurrentUser.do
```

### 3. 应用角色鉴权(否则 403)

教务系各应用按**角色**鉴权。新会话即使已激活, 未设角色时学生类应用的业务 API 仍返回 **403**:

```
# 每个应用有各自的角色(从各自的应用配置取):
GET  /jwapp/sys/funauthapp/api/getAppConfig/<appname>-<appId>.do   → HEADER.dropMenu[]
POST /jwapp/sys/funauthapp/api/changeAppRole/<appname>/<roleId>.do
POST /jwapp/sys/jwpubapp/pub/setJwCommonAppRole.do   body: ROLEID=<roleId>
```

`appId` 可从应用页 HTML 的 `_JW_INIT_CONFIG.appId` 提取。本校角色:
- xywccx/jdfpm: 本科生 `20200406114614081`
- zmsqxmu: 证明申请本科生 `20240513172418331`

### 4. Referer 校验与双路径

EMAP 对**码表**(`/jwapp/code/*.do`)和**保存类**端点校验 Referer 必须为对应应用页(用门户页作 Referer 会返回 `{"code":"404"}`)。
同一应用的接口还在两个路径下:
- 列表/模型类: `/jwapp/sys/<app>/modules/<路由>/<action>.do`(如 `modules/xszmsq/xszmsqcx.do`)
- 动作类(save/down/mail): `/jwapp/sys/<app>/<路由>/<action>.do`(如 `xszmsq/saveZmsq.do`)

### 4. Referer 校验

部分接口对 Referer 敏感: 从门户页/应用页调用正常, 从错误页(404)上下文调用返回 403。
服务端统一使用门户页 `https://jw.xmu.edu.cn/new/index.html` 作为 Referer。

## 业务接口

统一为 `POST application/x-www-form-urlencoded`, 会话 cookie 认证。

### 学业完成查询(app: xywccx)

| 接口 | 参数 | 返回 |
|---|---|---|
| `/jwapp/sys/xywccx/modules/xywccx/cxxsscfa.do` | 空(服务端按会话取学生) | 学生的培养方案: PYFADM/PYFAMC/WCXF/YQXF |
| `/jwapp/sys/xywccx/modules/xywccx/cxscfa.do` | XH, SCLBDM=04, PYFADM, BYNJDM=-, PCDM=- | 方案摘要(PYFADM 为空时查不到数据) |
| `/jwapp/sys/xywccx/modules/xywccx/cxscfakz.do` | XH, PYFADM, SCLBDM=04, XNXQDM | 课组列表(27 行), FKZH 父节点/KZLXDM 类型 |
| `/jwapp/sys/xywccx/modules/xywccx/cxscfakzkchxkqkx.do` | SCLBDM, BYNJDM, PCDM, XH, PYFADM, pageSize | 全部课程(402 行) |
| `/jwapp/sys/xywccx/modules/xywccx/cxfawxfms.do` | XH, PYFADM | 方案未修学分/门数 |

课程状态 `SFTG`: 1=通过, 0=未通过, 2=未修读, 3=待选课, 4=已选课(本学期)。

课组层级: `FKZH="-1"` 为顶级模块, 其余按 FKZH 挂父节点, 模块/子模块/课组可任意深度。

### GPA 排名(app: jdfpm)

| 接口 | 参数 | 返回 |
|---|---|---|
| `/jwapp/sys/jdfpm/modules/jdjs/cxxsjbxx.do` | 空 | 学生基本信息(学号/姓名/院系/专业/班级) |
| `/jwapp/sys/jdfpm/modules/jdjs/cxxskxcjfw.do` | 空 | 可选成绩范围(8 个) |
| `/jwapp/sys/jdfpm/api/jdjs/getJdjssq.do` | 空 | 绩点计算申请记录(数组) |
| `/jwapp/sys/jdfpm/api/jdjs/addJdjssq.do` | CJFWWID, CJFWWID_DISPLAY | 新增计算申请 |
| `/jwapp/sys/jdfpm/modules/jdjs/cxzdxscjxq.do` | SFZX=1, CJFWWID, querySetting=[...], pageSize | 某次申请的成绩明细 |
| `/jwapp/sys/jdfpm/api/jdjs/printZm.do?wid=&type=&DYNR=&SFXYPM=` | GET | 绩点证明(HTML) |

申请记录结构: `{ SQSJ, SFYX, ZX: { GPA, JQPJF(加权), SSPJF(算术), CYJSCJMS(门数), CJFWMC, CYJSZYRS(专业人数), SFXSPM(是否显示排名) } }`。
现阶段学校不开放排名(`SFXSPM=false`, 排名字段为 `*`), 仅提供 GPA 计算与开具。

### 证明文件申请(app: zmsqxmu)

| 接口 | 路径 | 返回 |
|---|---|---|
| 我的申请 | `modules/xszmsq/xszmsqcx.do` | 申请列表(名称中英文/申请时间/有效状态/截止/打印份数) |
| 可申请证明 | `modules/xszmsq/dsqzmcx.do` | 12 类可申请证明 |
| 提交申请 | `xszmsq/saveZmsq.do` body: paramStr=JSON({SFDZZJDY,SQDYS,SFSYSJM}), zmid | `{success, isTrue}` |
| 可打印方式 | `xszmsq/queryKdyfs.do` body: zmids | 打印方式与剩余份数 |
| 下载证明 | `xszmsq/downZm.do` body: wid | PDF 文件(过期则 JSON 错误) |
| 邮件发送 | `xszmsq/sendMail.do` body: wids, mail | 发送结果 |

申请表单字段(zmsqbd 模型): SFDZZJDY=是否到自助机打印, SQDYS=申请打印份数, SFSYSJM=是否使用随机码。
证明有效期 7 天; 同一证明多次申请仅能下载最新一次。

## 数据样本

- `xywccx-data.json` — 学业完成查询全量响应(方案/27 课组/402 课程/未修统计)
- `jdfpm-data.json` — GPA 信息(8 范围/5 申请记录/53 门成绩明细)

服务端 `MOCK=1` 启动时使用这些样本, 便于离线开发前端。

## 应用入口地址(syscode)速查

从本机 Chrome 历史恢复的地址(换环境失效时可用 `server/chrome_history.py` 重新发现):

| 应用 | 地址 |
|---|---|
| 学业完成查询 | `/jwapp/sys/xywccx/*default/index.do` |
| GPA 排名 | `/jwapp/sys/jdfpm/*default/index.do` |
| 证明文件申请 | `/jwapp/sys/zmsqxmu/*default/index.do` |
| 成绩查询 | `/jwapp/sys/cjcx/*default/index.do` |
| 我的考试安排 | `/jwapp/sys/studentWdksapApp/*default/index.do` |
| 学生特殊选课 | `/jwapp/sys/tsxsxk/*default/index.do` |

## 辅助登录(票据交换)

用户浏览器已登录统一身份认证时, 可不输密码:

1. 在 jw.xmu.edu.cn 域的页面里 iframe 访问
   `https://ids.xmu.edu.cn/authserver/login?service=https://jw.xmu.edu.cn/new/index.html`
2. CAS 已有 SSO 会话 → 302 到 `new/index.html?ticket=ST-xxx`(静态门户不消费票据)
3. 父页面(同域)读取 iframe URL 拿到 ticket
4. 服务端用 ticket 访问 `/jwapp/sys/emapangular/index.do?ticket=...` 换取会话

票据一次性且短时效, 拿到后需立即使用。
