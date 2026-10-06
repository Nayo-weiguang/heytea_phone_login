# 手机号登录后端（Python 侧）

这个目录**只有登录**,没有界面。前端用 `heyteago-diy` 自带的 Next.js WebUI,
我们只在他们的 `web/components/` 里加了一个登录面板。

```
浏览器 (他们的 WebUI)
   │  腾讯滑块 -> ticket
   ▼
Go 服务 heyteago-diy  (他们的代码, 只多了 /api/auth/* 三个路由)
   │  exec  python heytea_login_step.py  {"op":"sms"|"login",...}
   ▼
本目录  ← Secure-Transmission 握手 / 加密 / 反滥用签名
```

上传、存草稿、画布渲染全部走他们原封不动的 `heyteaapi` client,和这里无关。

## 文件

| 文件 | 作用 |
|---|---|
| `heytea_login_step.py` | 步骤执行器。stdin 一个 JSON, stdout 一个 JSON |
| `lib/heytea_secure_sdk.py` | Secure-Transmission(Unicorn 模拟 `libsdk_core.so`) |
| `lib/heytea_cryption.py` | 手机号 AES 加密 |

## 调用方式

Go 侧每次起一个短命进程:

```bash
echo '{"op":"ping"}'                                            | python heytea_login_step.py
echo '{"op":"sms","phone":"138...","captcha":"<ticket>"}'       | python heytea_login_step.py
echo '{"op":"login","phone":"...","code":"123456","captcha":"<ticket>"}' | python heytea_login_step.py
```

返回恒为一行 JSON:`{"ok":true,...}` 或 `{"ok":false,"error":"..."}`。
日志走 stderr。

## 配置(环境变量)

| 变量 | 说明 |
|---|---|
| `HEYTEA_LOGIN_PYTHON` | python 路径(Go 侧 `HEYTEA_LOGIN_PYTHON`) |
| `HEYTEA_LOGIN_SCRIPT` | 本脚本路径(Go 侧 `HEYTEA_LOGIN_SCRIPT`) |
| `HEYTEA_SDK_SO` | `libsdk_core.so` 位置 —— **必须设**,它不在仓库里 |
| `SIGN_MODE` | `exec`(目前只支持这个) |
| `SIGN_JAR` | `sign-oracle.jar`,用他们 `bin/` 里那份 |
| `SIGN_SO` | `libheyteago.so`,用他们 `bin/` 里那份 |
| `SIGN_ENV` | 默认 `prod` |
| `APP_CODE` | 默认 `164`(versionCode,不是版本名) |
| `APP_HOST` | 默认 `https://app-go.heytea.com` |

## 需要的两个专有 .so

都不在仓库里,版权不属于本项目:

- `libsdk_core.so`(100 KB)—— 在喜茶GO APK 的 `apk/lib/arm64-v8a/`,设 `HEYTEA_SDK_SO` 指过去
- `libheyteago.so` —— 他们 `bin/` 里已有一份(SHA256 与逆向出来的一致),直接用

## 为什么要过两次人机验证

腾讯滑块的 ticket **一次性**。发短信消耗一张,登录消耗一张。
所以 WebUI 上点「获取验证码」弹一次,点「登录」再弹一次。

## 已知问题

`lib/heytea_secure_sdk.py` 里 `encrypt_request()` 和 `decrypt_response()`
实测是**原样返回**(Unicorn 那侧的 JNI 分发没跑通)。握手是好的,ticket 和
secure 头都是真的。

login_v1 接受明文 body,所以流程能走通;但要求密文的接口会失败。

## 注意

每次重新登录都会把手机上的喜茶GO App 踢下线(同账号单 app 会话),
每天短信条数有限。验证码必须手动过,本工具不做自动绕过。

自动化第三方接口通常违反其服务条款,请自行评估风险。
