package com.heytea.probe;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.DvmObject;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.linux.android.dvm.array.IntArray;
import com.github.unidbg.spi.Loader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 完整 Secure-Transmission 会话验证。
 *
 * 流程（与 C:\heytea_phone_login\lib\heytea_secure_sdk.py 一致）：
 *   1. GET  tenant-config
 *   2. .so  handshakePrepare(config) -> client_public_key / client_random
 *   3. POST handshake                 -> server_random / ticket / route_rules
 *   4. .so  handshakeFinish(serverRandom, routeRules)
 *   5. .so  encode(payload, domain, url, tenant, version) -> secure_encrypted_c_data
 */
public final class StSession {

    private static final String SO =
            "C:\\heytea-android\\app\\src\\main\\assets\\libsdk_core.so";
    private static final String API = "https://app-go.heytea.com";
    private static final String TENANT = "heyteago-android";
    private static final int VERSION = 2;
    private static final String CLIENT = "app";

    public static void main(String[] args) throws Exception {
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .setProcessName("com.heyteago")
                .addBackendFactory(new Unicorn2Factory(true))
                .build();
        try {
            VM vm = emulator.createDalvikVM();
            AndroidResolver resolver = new AndroidResolver(23);
            ((Loader) emulator.getMemory()).setLibraryResolver(resolver);
            emulator.getSyscallHandler().addIOResolver(resolver);

            // 诊断：libc++_shared.so 是从原 APK 里取出来的真实 NDK libc++，
            // 放在 classpath 的 sdkres 目录，让 Class.getResource 能命中。
            System.out.println("  [diag] getResource(/android/sdk23/lib64/libc++_shared.so) = "
                    + emulator.getClass().getResource("/android/sdk23/lib64/libc++_shared.so"));
            System.out.println("  [diag] resolveLibrary(libc++_shared.so) = "
                    + resolver.resolveLibrary(emulator, "libc++_shared.so"));
            System.out.println("  [diag] resolveLibrary(libc.so) = "
                    + resolver.resolveLibrary(emulator, "libc.so"));

            DalvikModule dm = vm.loadLibrary(new File(SO), false);
            dm.callJNI_OnLoad(emulator);
            DvmClass cls = vm.resolveClass("com/securesdk/nativebridge/SdkNative");
            vm.setVerbose(true);
            System.out.println("  [ok] libsdk_core.so 已加载");

            // ---- 1. tenant-config ----
            String cfg = http("GET", "/api/_secure-transmission/tenant-config", null, false);
            System.out.println("  [1] tenant-config " + cfg.length() + " 字节");

            // ---- 2. handshakePrepare ----
            ByteArray out = new ByteArray(vm, new byte[1024]);
            IntArray outLen = new IntArray(vm, new int[1]);
            ByteArray cfgArr = new ByteArray(vm, cfg.getBytes(StandardCharsets.UTF_8));
            long t0 = System.currentTimeMillis();
            cls.callStaticJniMethod(emulator, "handshakePrepare([BI[B)V", out, outLen, cfgArr);
            int len = outLen.getValue()[0];
            String post = new String(out.getValue(), 0, len, StandardCharsets.UTF_8);
            System.out.println("  [2] handshakePrepare " + (System.currentTimeMillis() - t0)
                    + "ms, outLen=" + len);
            System.out.println("      " + post);

            // ---- 3. handshake ----
            String hs = http("POST", "/api/_secure-transmission/handshake", post, true);
            System.out.println("  [3] handshake 响应 " + hs.length() + " 字节");
            System.out.println("      " + hs.substring(0, Math.min(300, hs.length())));

            String ticket = pick(hs, "ticket");
            String serverRandom = pick(hs, "server_random");
            String routeRules = pick(hs, "route_rules");
            if (ticket == null || serverRandom == null) {
                System.out.println("  [!] 握手响应缺字段，终止");
                return;
            }
            System.out.println("      ticket        = " + ticket);
            System.out.println("      server_random = " + serverRandom);
            System.out.println("      route_rules   = " + routeRules);

            byte[] sr = b64url(serverRandom);
            System.out.println("      server_random 解码 " + sr.length + " 字节 ("
                    + hex(java.util.Arrays.copyOfRange(sr, 0, Math.min(8, sr.length))) + "…)");

            // ---- 4. handshakeFinish ----
            ByteArray srArr = new ByteArray(vm, sr);
            ByteArray routeArr = new ByteArray(vm,
                    (routeRules == null ? "[]" : routeRules).getBytes(StandardCharsets.UTF_8));
            t0 = System.currentTimeMillis();
            int rcFinish = cls.callStaticJniMethodInt(
                    emulator, "handshakeFinish([B[B)I", srArr, routeArr);
            System.out.println("  [4] handshakeFinish = " + rcFinish
                    + " (" + (System.currentTimeMillis() - t0) + "ms)");

            if (rcFinish != 0) {
                System.out.println("  [!] handshakeFinish 失败，停止");
                return;
            }
            System.out.println("  [ok] 会话已建立，session_key 在 .so 内部");

            // ---- 5. encode ----
            // 实测签名：4 个参数全是 byte[]（不是 String），返回也是 byte[]。
            // 传 String 会抛 ClassCastException: StringObject cannot be cast to Array。
            String body = "{\"mobile\":\"13800000000\",\"verifyCode\":\"123456\"}";
            String path = "/api/service-login/openapi/vip/user/sms";
            String domain = "app-go.heytea.com";
            byte[] enc = null;
            try {
                ByteArray r = cls.callStaticJniMethodObject(emulator,
                        "encode([B[B[B[BI)[B",
                        b(vm, body), b(vm, domain), b(vm, path), b(vm, TENANT), VERSION);
                enc = r == null ? null : r.getValue();
                System.out.println("  [5] encode 返回 "
                        + (enc == null ? "null" : enc.length + " 字节"));
                if (enc != null) {
                    String s = new String(enc, StandardCharsets.UTF_8);
                    System.out.println("      " + (s.length() > 400 ? s.substring(0, 400) + "…" : s));
                }
            } catch (Throwable t) {
                System.out.println("  [5] encode 异常: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }

            // ---- 6. decode 回环 ----
            // 小程序行为：解密输入是含 secure_encrypted_s_data 的整个响应对象 JSON
            if (enc != null) {
                try {
                    String blob = new String(enc, StandardCharsets.UTF_8);
                    int a = blob.indexOf("\"secure_encrypted_c_data\"");
                    if (a > 0) {
                        int q1 = blob.indexOf('"', blob.indexOf(':', a) + 1);
                        int q2 = blob.indexOf('"', q1 + 1);
                        blob = blob.substring(q1 + 1, q2);
                    }
                    String wrapped = "{\"secure_encrypted_s_data\":\"" + blob + "\"}";
                    ByteArray dr = cls.callStaticJniMethodObject(emulator,
                            "decode([B[BI)[B", b(vm, wrapped), b(vm, TENANT), VERSION);
                    byte[] dec = dr == null ? null : dr.getValue();
                    System.out.println("  [6] decode 返回 " + (dec == null
                            ? "null" : new String(dec, StandardCharsets.UTF_8)));
                } catch (Throwable t) {
                    System.out.println("  [6] decode 异常: " + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }
            }

        } catch (Throwable t) {
            System.out.println("  !!! " + t.getClass().getName() + ": " + t.getMessage());
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 10); i++) {
                System.out.println("      at " + st[i]);
            }
            Throwable c = t.getCause();
            while (c != null) {
                System.out.println("    caused by " + c.getClass().getName() + ": " + c.getMessage());
                c = c.getCause();
            }
        } finally {
            try { emulator.close(); } catch (Throwable ignore) { }
        }
    }

    // ---------------- HTTP ----------------

    private static String http(String method, String path, String body, boolean json)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Heytea-Secure-Transmission-Tenant", TENANT);
        c.setRequestProperty("Heytea-Secure-Transmission-Version", String.valueOf(VERSION));
        c.setRequestProperty("X-client", CLIENT);
        c.setRequestProperty("User-Agent", "okhttp/4.9.0");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (is != null) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
        }
        String s = new String(bo.toByteArray(), StandardCharsets.UTF_8);
        if (code >= 400) throw new RuntimeException("HTTP " + code + " " + s);
        return s;
    }

    /** 取顶层字符串字段，够用即可（route_rules 是数组，原样截出来） */
    private static String pick(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + k.length());
        if (colon < 0) return null;
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length()) return null;
        if (json.charAt(p) == '"') {
            int e = p + 1;
            StringBuilder sb = new StringBuilder();
            while (e < json.length() && json.charAt(e) != '"') {
                if (json.charAt(e) == '\\' && e + 1 < json.length()) e++;
                sb.append(json.charAt(e));
                e++;
            }
            return sb.toString();
        }
        // 数组或对象：配平括号
        char open = json.charAt(p);
        char close = open == '[' ? ']' : '}';
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int e = p; e < json.length(); e++) {
            char ch = json.charAt(e);
            if (inStr) {
                if (esc) esc = false;
                else if (ch == '\\') esc = true;
                else if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') { inStr = true; continue; }
            if (ch == open) depth++;
            else if (ch == close) {
                depth--;
                if (depth == 0) return json.substring(p, e + 1);
            }
        }
        return json.substring(p);
    }

    // ---------------- 工具 ----------------

    /** 造一个 byte[] 形参 */
    private static ByteArray b(VM vm, String s) {
        return new ByteArray(vm, s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] b64url(String s) {        String t = s.replace('-', '+').replace('_', '/');
        while (t.length() % 4 != 0) t += '=';
        return Base64.getDecoder().decode(t);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
