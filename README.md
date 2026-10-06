# heytea_phone_login

用手机号 + 短信验证码登录喜茶 GO 的后端实现。

对外只暴露一个命令行入口:读 stdin 一个 JSON,写 stdout 一个 JSON。
上层服务负责起短命进程调用它。

```
调用方
  │  stdin:  {"op":"sms","phone":"..."}
  ▼
heytea_login_step.py        ← 编排:加密请求、组装头、解析响应
  │
  ├── lib/heytea_cryption.py    手机号 AES 加密
  └── lib/heytea_secure_sdk.py  Secure-Transmission 握手 / 会话密钥 / 反滥用签名
  ▼
stdout: {"ok":true,...}
```

## 能力

| op | 作用 | 需要什么 |
|---|---|---|
| `ping` | 探活,返回签名模式和 so 路径 | 无 |
| `sms` | 发短信验证码 | `phone`(+ 首次需人机 ticket) |
| `login` | 短信码换 token | `phone`、`code`(+ 人机 ticket) |

返回恒为一行 JSON:`{"ok":true,...}` 或 `{"ok":false,"error":"..."}`。
日志走 stderr,不污染 stdout。

## 人机验证

腾讯滑块(appid `197451715`)。**ticket 是一次性的** —— 发短信消耗一张,
登录消耗另一张,所以整条流程要过两次。

`captcha` 字段是**选填**的。脚本不带票也会先把请求发出去探一次,
服务端回 `needCaptcha` 时如实转达:

```json
{"ok":false,"needCaptcha":true,"stage":"sms","error":"..."}
```

调用方按这个流程走:

```
1. 不带 ticket 调一次
2. 若 needCaptcha -> 弹腾讯滑块 -> 拿 ticket
3. 带上 ticket 再调一次
```

判定逻辑在 `looks_like_need_captcha()`:优先看显式的 `needCaptcha` 字段;
字段缺失时,只在明确失败(`ok=false` 或 `code!=0`)才用「人机 / captcha」
关键词兜底。**不能只按关键词匹配** —— 成功响应里也带
「点「登录」时会自动再弹一次人机验证」的 note,会误判。

## 调用示例

```bash
echo '{"op":"ping"}'                        | python heytea_login_step.py
echo '{"op":"sms","phone":"138..."}'         | python heytea_login_step.py
echo '{"op":"sms","phone":"138...","captcha":"<ticket>"}' | python heytea_login_step.py
echo '{"op":"login","phone":"138...","code":"123456"}' | python heytea_login_step.py
```

## 配置(环境变量)

| 变量 | 默认 | 说明 |
|---|---|---|
| `HEYTEA_SDK_SO` | 无 | `libsdk_core.so` 路径 —— **必须设** |
| `SIGN_MODE` | `exec` | 签名方式,目前只支持 `exec` |
| `SIGN_JAR` | 空 | 签名 oracle jar 路径 |
| `SIGN_SO` | 空 | 签名用 `.so` 路径 |
| `SIGN_ENV` | `prod` | `prod` / `test` |
| `APP_CODE` | `164` | versionCode,**不是版本名** |
| `APP_HOST` | `https://app-go.heytea.com` | 目标域名 |

## 依赖的专有二进制

以下两个不在仓库里,需自行从 APK 获取后用环境变量指向:

| 文件 | 位置 |
|---|---|
| `libsdk_core.so` | 喜茶 GO APK 的 `lib/arm64-v8a/` |
| `libheyteago.so` | 喜茶 GO APK 的 `lib/arm64-v8a/` |

## 安装

```bash
pip install -r requirements.txt
```

## 已知问题

`lib/heytea_secure_sdk.py` 的 `encrypt_request()` / `decrypt_response()`
实测**原样返回**(模拟层的 JNI 分发未跑通)。握手本身是好的,ticket 和
secure 请求头都是真实可用的。

`login_v1` 接受明文 body,所以登录流程能走通;但要求密文的接口会失败。

## 注意事项

- 同一账号在新设备登录会把旧会话踢下线
- 短信条数有限
- 人机验证必须手动过,本项目不做自动绕过
- 自动化调用第三方接口通常违反其服务条款,请自行评估风险

## 目录

```
heytea_login_step.py          步骤执行器(入口)
lib/heytea_cryption.py        手机号加密
lib/heytea_secure_sdk.py      Secure-Transmission + 反滥用签名
st-probe/                     协议逆向用的探针(分析工具,非运行时依赖)
fixtures/                     测试用样本
```

`st-probe/` 是分析 Secure-Transmission 协议时写的探针,用于核对签名、
偏移量和握手流程。它不是运行登录的依赖,需要单独的 classpath 才能跑。