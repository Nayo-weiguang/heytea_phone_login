# 在 heyteago-diy 里接这个登录

上传部分完全用他们原样的代码,这里只提供"手机号换 token"。
前端也用他们的 WebUI,只把粘贴 token 的输入框换成手机号登录面板。

## 1. Python 侧(本目录)

无界面。Go 每次起一个短命进程:

```
echo '{"op":"ping"}'                                                       | python heytea_login_step.py
echo '{"op":"sms","phone":"138...","captcha":"<ticket>"}'                  | python heytea_login_step.py
echo '{"op":"login","phone":"...","code":"123456","captcha":"<ticket>"}'  | python heytea_login_step.py
```

stdout 恒为一行 JSON:`{"ok":true,...}` / `{"ok":false,"error":"..."}`。日志走 stderr。

## 2. Go 侧

### 2.1 adapter —— exec Python

新增 `internal/adapter/pylogin/`:

```go
cfg := pylogin.DefaultConfig()
cfg.Python = os.Getenv("HEYTEA_LOGIN_PYTHON")   // python 路径
cfg.Script = os.Getenv("HEYTEA_LOGIN_SCRIPT")   // heytea_login_step.py 路径
logins := usecase.NewLoginService(pylogin.NewGateway(cfg))
```

### 2.2 usecase —— 持一次性票据

新增 `internal/usecase/login_service.go`。人机 ticket 一次性,
所以只保存"最近一次"拿到的,`takeCaptcha()` 取出即清空:

```go
func (s *LoginService) SetCaptcha(t string)
func (s *LoginService) SendSMS(ctx, phone) (SMSResult, error)
func (s *LoginService) Login(ctx, phone, code) (LoginResult, error)
```

`SendSMS` / `Login` 在没有票据时返回 `ErrNoCaptcha`。

### 2.3 transport —— 三个路由

```go
mux.HandleFunc("/api/auth/captcha", s.handleAuthCaptcha)   // GET 查状态 / POST 存票据
mux.HandleFunc("POST /api/auth/sms", s.handleAuthSMS)
mux.HandleFunc("POST /api/auth/login", s.handleAuthLogin)
```

行为:

| 情况 | 响应 |
|---|---|
| 没票据就调 sms/login | 400 `{"message":"请先完成人机验证","needCaptcha":true}` |
| 票据被服务端拒 | 502 `{"ok":false,"code":...,"message":...,"needCaptcha":true}` |
| 登录成功 | 200 `{"ok":true,"token":"...","userMainId":"..."}` |

登录成功才返回 token;服务端不保存。上传/草稿路由一行没改。

### Python 侧(`heytea_login_step.py`)的回执

`captcha` 字段现在是**选填**的。脚本不带票也会先把请求发出去探一次:

| 情况 | stdout JSON |
|---|---|
| 要人机(缺票,或票被拒) | `{"ok":false,"needCaptcha":true,"stage":"sms","error":"..."}` |
| 短信已发出 | `{"ok":true,"code":"0","note":"短信已发出;点「登录」时会再要一次人机验证(ticket 一次性)。"}` |
| 登录成功 | `{"ok":true,"token":"...","userMainId":"..."}` |

`stage` 是 `sms` / `login`,告诉调用方该在哪一步弹窗。调用方循环:

```
1. 不带 ticket 调一次
2. 若 needCaptcha -> 弹腾讯滑块(appid=197451715) -> 拿 ticket
3. 带上 ticket 再调一次
```

判定逻辑在 `looks_like_need_captcha()`:优先看显式的 `needCaptcha` 字段;字段缺失时
只在明确失败(`ok=false` 或 `code!=0`)时才用「人机 / captcha」关键词兜底 —— 因为**成功**
响应里也带「点「登录」时会自动再弹一次人机验证」的 note,不能按关键词误判成要人机。

## 3. 前端

`web/components/PhoneLoginPanel.tsx`,props 与原 `TokenPanel` 完全一致:

```tsx
token, remember, user, busy, onTokenChange, onUserChange, onStatus
```

所以 `page.tsx` 只改 import 和组件名,state / localStorage / 上传逻辑全不动。

流程:

1. 填手机号 → 点「获取验证码」→ **腾讯滑块自动弹出** → 通过后自动发短信
2. 填短信码 → 点「登录」→ **滑块再弹一次** → 通过后自动提交

票据一次性,所以必须过两次。登录成功后 token 回到 `onTokenChange`,
进现有的 `token` state —— 和他原来的设计一致(浏览器侧持有)。

## 4. 环境变量

Go 侧:

| 变量 | 说明 |
|---|---|
| `HEYTEA_LOGIN_PYTHON` | python 可执行文件 |
| `HEYTEA_LOGIN_SCRIPT` | `heytea_login_step.py` |
| `HEYTEA_SIGN_JAR` | `bin/sign-oracle.jar`(他们已有) |
| `HEYTEA_SIGN_SO` | `bin/libheyteago.so`(他们已有) |
| `HEYTEA_SDK_SO` | `libsdk_core.so` —— **必须自己准备** |
| `SIGN_MODE` | `exec` |

`libsdk_core.so`(100 KB)在喜茶GO APK 的 `apk/lib/arm64-v8a/`,
版权不属于本项目,请自行获取。

## 5. 启动

```powershell
# Go (后端, 8790)
cd heyteago-diy-master
$env:HEYTEA_LOGIN_PYTHON = "C:\heytea_phone_login\..\python.exe"
$env:HEYTEA_LOGIN_SCRIPT = "C:\heytea_phone_login\heytea_login_step.py"
$env:HEYTEA_SDK_SO       = "<你的>/libsdk_core.so"
$env:SIGN_MODE           = "exec"
.\heytea-diy.exe

# Web (前端, 3000, /api/* 已由 next.config.ts 代理到 8790)
cd heyteago-diy-master\web
pnpm install
pnpm dev
```

## 注意事项

- 每次重新登录都会把手机上的喜茶GO App 踢下线(同账号单 app 会话)
- 每天短信条数有限
- 验证码必须手动过,本工具不做自动绕过
- `encrypt_request` / `decrypt_response` 目前是空操作(见本目录 README),
  `login_v1` 吃明文所以能走通
- 自动化第三方接口通常违反其服务条款,请自行评估风险
