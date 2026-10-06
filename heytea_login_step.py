#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
登录步骤执行器 —— 给 Go 后端一次性调用。

每次调用是一个独立进程, 读 stdin 一个 JSON, 往 stdout 写一个 JSON。
这样 Go 侧不需要常驻 Python 服务, 登录失败也不会影响上传。

    echo '{"op":"ping"}'                  | python heytea_login_step.py
    echo '{"op":"sms","phone":"..."}'               | python heytea_login_step.py
    echo '{"op":"sms","phone":"...","captcha":"..."}'   | python heytea_login_step.py
    echo '{"op":"login","phone":"...","code":"..."}'        | python heytea_login_step.py
    echo '{"op":"login","phone":"...","code":"...","captcha":"..."}' | python heytea_login_step.py

人机验证（腾讯滑块，appid=197451715）
------------------------------------
ticket 是**一次性**的：发短信要一张、登录要另一张（Go 侧 takeCaptcha 取走即清）。
所以 captcha 是选填的 —— 不带也能调，脚本会先把请求发出去探一下：

    返回 {"ok":false,"needCaptcha":true,"stage":"sms"}   -> 弹人机，拿到 ticket 再带回来重试
    返回 {"ok":true, ...}                                  -> 过了

调用方循环：先不带 ticket 调一次；若 needCaptcha 则弹窗取 ticket，带上再调一次。

输出恒为一行 JSON:  {"ok":true,...} 或 {"ok":false,"error":"..."}
日志走 stderr, 不污染 stdout。
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
for p in (os.path.join(HERE, "lib"),):
    if os.path.isdir(p) and p not in sys.path:
        sys.path.insert(0, p)

HOST = os.environ.get("APP_HOST", "https://app-go.heytea.com")
DOMAIN = "app-go.heytea.com"
SMS_PATH = "/api/service-member/openapi/vip/user/sms/verifiyCode/send"
LOGIN_V1 = "/api/service-login/openapi/vip/user/login_v1"
LOGIN_V1_PMS = "/api/service-login-pms/openapi/vip/user/login_v1"
USER_INFO = "/api/service-member/vip/user/info"
APP_CODE = os.environ.get("APP_CODE", "164")
BRAND = "1000001"
SIGN_MODE = os.environ.get("SIGN_MODE", "exec")
SIGN_JAR = os.environ.get("SIGN_JAR", "")
SIGN_SO = os.environ.get("SIGN_SO", "")
SIGN_ENV = os.environ.get("SIGN_ENV", "prod")


def log(tag, msg):
    sys.stderr.write("[%s] %s: %s\n" % (tag, time_str(), msg))
    sys.stderr.flush()


def time_str():
    import time
    return time.strftime("%H:%M:%S")


def app_headers():
    return {
        "User-Agent": ("Mozilla/5.0 (Linux; Android 13; 2410DPN6CC Build/TP1A.220624.014) "
                       "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 "
                       "Chrome/120.0.0.0 Mobile Safari/537.36"),
        "Accept": "application/prs.heytea.v1+json",
        "Content-Type": "application/json",
        "Accept-Language": "zh-CN",
        "Client": "2", "GMT-Zone": "+08:00", "Region": "1", "X-Region-Id": "10",
        "X-version": APP_CODE, "version": APP_CODE,
        "client-version": APP_CODE, "X-client-version": APP_CODE,
        "X-client": "app",
        "current-page": "/pages/login/login_app/index",
    }


# ----------------------------------------------------------------- oracle
def oracle_call(payload):
    """exec 模式: 一次性起一个 sign-oracle 进程, 用完即弃"""
    if not SIGN_JAR or not SIGN_SO:
        raise RuntimeError("需要 SIGN_JAR / SIGN_SO")
    import subprocess
    p = subprocess.Popen(["java", "-jar", SIGN_JAR, SIGN_SO, SIGN_ENV],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL, bufsize=0)
    try:
        t0 = __import__("time").time()
        while __import__("time").time() - t0 < 60:
            l = p.stdout.readline()
            if not l:
                raise RuntimeError("oracle 启动失败")
            if l.decode("utf-8", "replace").startswith("READY"):
                break
        else:
            raise RuntimeError("oracle READY 超时")
        p.stdin.write((payload + "\n").encode())
        p.stdin.flush()
        while True:
            o = p.stdout.readline()
            if not o:
                raise RuntimeError("oracle 无响应")
            s = o.decode("utf-8", "replace").strip()
            if s.startswith("RESULT:"):
                return json.loads(s[len("RESULT:"):])
    finally:
        try:
            p.kill()
        except Exception:
            pass


def unwrap(obj):
    inner = obj
    if isinstance(inner, str):
        inner = json.loads(inner)
    if isinstance(inner, dict) and isinstance(inner.get("data"), str):
        try:
            nxt = json.loads(inner["data"])
            if isinstance(nxt, dict) and nxt.get("data"):
                return nxt["data"]
        except Exception:
            pass
    return inner["data"] if isinstance(inner, dict) else inner


def hmac_for(biz, path, ts):
    if SIGN_MODE != "exec":
        raise RuntimeError("step 脚本目前只支持 SIGN_MODE=exec")
    return unwrap(oracle_call("TRADE %s|%s|%s" % (biz, path, ts)))


# ----------------------------------------------------------------- SDK
_SDK = None


def get_sdk():
    global _SDK
    if _SDK is None:
        from heytea_secure_sdk import HeyTeaSecureSDK
        import heytea_secure_sdk
        so = heytea_secure_sdk.SO_PATH
        if not os.path.isfile(so):
            raise RuntimeError("找不到 libsdk_core.so: %s (设 HEYTEA_SDK_SO)" % so)
        log("sdk", "握手 %s" % so)
        _SDK = HeyTeaSecureSDK(HOST, app_headers(), tenant="heyteago-android",
                               version=2, client="app")
        _SDK.ensure_session(force=True)
        log("sdk", "握手 OK ticket=%s..." % ((_SDK.ticket or "")[:20]))
    return _SDK


def post(sdk, path, body, extra=None):
    import requests
    enc = sdk.encrypt_request(body, path, domain=DOMAIN)
    h = sdk.secure_headers()
    if extra:
        h.update(extra)
    r = requests.post(HOST + path, headers=h, json=enc, timeout=30)
    r.encoding = "utf-8"
    try:
        j = r.json()
    except Exception:
        return {"code": -1, "message": r.text[:200]}
    if not isinstance(j, dict):
        return {"code": -1, "message": "响应不是对象"}
    if "code" not in j and "errno" in j:
        j["code"] = j["errno"]

    d = j.get("data")
    if "secure_encrypted_s_data" in j:
        try:
            from heytea_secure_sdk import HeyTeaSecureSDK  # noqa
            plain = sdk.decrypt_response(j["secure_encrypted_s_data"])
            merged = dict(plain) if isinstance(plain, dict) else {"data": plain}
            merged.setdefault("code", j.get("code"))
            j = merged
        except Exception as e:
            log("raw", "decrypt(顶层) 失败 %s" % type(e).__name__)
    elif isinstance(d, dict) and d.get("secure_encrypted_s_data"):
        try:
            j["data"] = sdk.decrypt_response(d["secure_encrypted_s_data"])
        except Exception as e:
            log("raw", "decrypt(dict) 失败 %s" % type(e).__name__)
    elif isinstance(d, str) and d.startswith("HEYTEA_ENCRYPTION_TRANSMISSION"):
        try:
            j["data"] = sdk.decrypt_response(d[len("HEYTEA_ENCRYPTION_TRANSMISSION"):])
        except Exception as e:
            log("raw", "decrypt(str) 失败 %s" % type(e).__name__)
    return j


def grab(o, key):
    if isinstance(o, dict):
        if o.get(key):
            return o[key]
        for v in o.values():
            g = grab(v, key)
            if g:
                return g
    return None


# ------------------------------------------------------------- 人机验证判定
def looks_like_need_captcha(j, raw_text=""):
    """判断响应是不是在说"要过人机验证"。

    依据服务端的三种返回（带路径只是便于查证契约，不是运行依赖）：

      1. 没随请求带 ticket -> HTTP 400 {"message":"缺少人机验证","needCaptcha":true}
      2. ticket 被拒       -> HTTP 502 {"ok":false,"code":..,"message":..,"needCaptcha":true}
                            那边注释写的是「票据多半是废的, 让前端重新过一次」
      3. 成功             -> HTTP 200 {"ok":true,..., "note":"点「登录」时会自动再弹一次人机验证"}

    第 3 条 note 里也带「人机验证」字样，但它其实是成功，所以必须先看显式的
    needCaptcha 字段：只有它为 true 才算要人机。字段缺失时才退回关键词匹配，
    且仅在明确失败（ok=false 或 code!=0）时才匹配，避免把成功的 note 误判。
    """
    if not isinstance(j, dict):
        return False
    if j.get("needCaptcha") is True:
        return True
    failed = (j.get("ok") is False) or (
        j.get("code") is not None and str(j.get("code")) != "0")
    if not failed:
        return False
    blob = " ".join(str(x) for x in
                    (j.get("message"), raw_text, j.get("note"), j.get("error")))
    return any(k in blob for k in ("人机", "captcha", "Captcha", "CAPTCHA"))


def need_captcha_reply(what, j=None):
    """统一的人机缺失回执。ok=False + needCaptcha=True，让调用方知道该弹窗了。"""
    msg = ""
    if isinstance(j, dict):
        msg = str(j.get("message") or j.get("error") or "")[:120]
    return {"ok": False, "needCaptcha": True, "stage": what,
            "error": msg or ("需要人机验证（%s）" % what)}


# ----------------------------------------------------------------- ops
def op_ping(_req):
    import heytea_secure_sdk
    return {"ok": True, "so": heytea_secure_sdk.SO_PATH,
            "signMode": SIGN_MODE, "appCode": APP_CODE}


def op_sms(req):
    """发短信验证码。

    人机 ticket 是**一次性**的：发短信要一张、登录又要一张（Go 侧 takeCaptcha
    取走就清空 session）。所以这里不带票也先把请求发出去 —— 服务端会回
    needCaptcha，我们据此告诉调用方"该弹人机了"，而不是自己抛异常。

    调用方拿到 needCaptcha 后，弹腾讯验证码，把 ticket 带着再调一次即可。
    """
    import heytea_cryption
    phone = (req.get("phone") or "").strip()
    cap = (req.get("captcha") or "").strip()
    if not phone:
        raise RuntimeError("缺少 phone")
    sdk = get_sdk()
    body = {"client": "app", "brandId": BRAND,
            "mobile": heytea_cryption.encrypt_heytea_mobile(phone),
            "zone": "86", "cryptoLevel": 2,
            "ticket": cap, "ticketFrom": "min"}
    j = post(sdk, SMS_PATH, body)
    log("sms", "code=%s %s" % (j.get("code"), (j.get("message") or "")[:50]))

    # 服务端说还要人机 -> 原样回执，让调用方去弹窗拿票再来
    if looks_like_need_captcha(j):
        return need_captcha_reply("sms", j)

    return {"ok": str(j.get("code")) == "0", "code": j.get("code"),
            "message": j.get("message") or "",
            "note": "短信已发出；点「登录」时会再要一次人机验证（ticket 一次性）。"}


def op_login(req):
    """短信码换 token。同样先探一次，缺票时回 needCaptcha 而不是抛异常。

    注意 ticket 一次性：发短信那张已经用掉了，这里必须**重新**弹一次人机。
    """
    import heytea_cryption
    import uuid
    import time
    phone = (req.get("phone") or "").strip()
    code = (req.get("code") or "").strip()
    cap = (req.get("captcha") or "").strip()
    if not (phone and code):
        raise RuntimeError("缺少 phone / code")
    sdk = get_sdk()
    enc_mobile = heytea_cryption.encrypt_heytea_mobile(phone)
    last = None
    need_cap = None
    for path in (LOGIN_V1, LOGIN_V1_PMS):
        ts = str(int(time.time() * 1000))
        hm = hmac_for("user", path, ts)
        body = {"channel": "A", "client": "app", "loginType": "APP_CODE",
                "brand": BRAND, "phone": enc_mobile, "email": None,
                "smsCode": code, "zone": "86", "cryptoLevel": 2,
                "ticket": cap, "ticketFrom": "min",
                "verifyTicket": "", "deviceId": str(uuid.uuid4())}
        j = post(sdk, path, body,
                 {"biz": "user", "url": path, "timeStamp": ts, "hmacStr": hm})
        last = {"code": j.get("code"), "message": j.get("message") or ""}
        log("login", "%s -> code=%s %s"
            % (path.split("/vip/")[-1], j.get("code"), (j.get("message") or "")[:44]))
        if looks_like_need_captcha(j):
            # 记下第一个要人机的响应，两个路径都试完再统一回执
            need_cap = need_cap or j
            continue
        if str(j.get("code")) != "0":
            continue
        tok = grab(j.get("data"), "token")
        if tok:
            return {"ok": True, "token": tok, "code": 0,
                    "userMainId": str(user_main_id(tok) or "")}
    if need_cap is not None:
        return need_captcha_reply("login", need_cap)
    return {"ok": False, "code": (last or {}).get("code"),
            "message": (last or {}).get("message", "登录失败")}


def user_main_id(token):
    import requests
    try:
        r = requests.get(HOST + USER_INFO, timeout=20, headers={
            "X-client-version": APP_CODE, "X-client": "app",
            "Authorization": "Bearer " + token, "User-Agent": ""})
        r.encoding = "utf-8"
        d = (r.json().get("data") or {})
        log("user", "user_main_id=%s %s" % (d.get("user_main_id"), d.get("name")))
        return d.get("user_main_id")
    except Exception as e:
        log("user", "取用户信息失败 %s" % type(e).__name__)
        return None


OPS = {"ping": op_ping, "sms": op_sms, "login": op_login}


def main():
    raw = sys.stdin.read()
    try:
        req = json.loads(raw or "{}")
    except Exception as e:
        json.dump({"ok": False, "error": "请求不是 JSON: %s" % e}, sys.stdout,
                  ensure_ascii=False)
        return 1
    op = (req.get("op") or "").strip()
    fn = OPS.get(op)
    if not fn:
        json.dump({"ok": False, "error": "未知 op: %r (支持 %s)"
                   % (op, ",".join(OPS))}, sys.stdout, ensure_ascii=False)
        return 2
    try:
        out = fn(req)
    except Exception as e:
        log("err", "%s: %s" % (type(e).__name__, str(e)[:200]))
        json.dump({"ok": False, "error": "%s: %s" % (type(e).__name__, str(e)[:300])},
                  sys.stdout, ensure_ascii=False)
        return 1
    json.dump(out, sys.stdout, ensure_ascii=False)
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())