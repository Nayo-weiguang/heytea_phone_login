package com.heytea.probe;

import com.github.unidbg.Emulator;
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.linux.android.dvm.array.ByteArray;
import com.github.unidbg.linux.android.dvm.array.IntArray;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.arm.backend.Unicorn2Factory;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.DvmClass;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.linux.android.AndroidResolver;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 桌面 JVM 探针：验证 libsdk_core.so 能不能在 unidbg 里跑通 handshakePrepare。
 *
 * 目的：在写 Kotlin 之前先确认这条路可行，桌面迭代比真机快几十倍。
 * 不用 Unicorn 手写模拟（Python 版那 520 行），unidbg 自带 JNIEnv 和 PLT。
 */
public final class StProbe {

    private static final String CLS = "com/securesdk/nativebridge/SdkNative";
    private static final String SO =
            "C:\\heytea-android\\app\\src\\main\\assets\\libsdk_core.so";
    private static final String FIXTURE =
            "C:\\heytea-android\\tools\\fixtures\\tenant-config.json";

    public static void main(String[] args) throws Exception {
        String configJson =
                new String(Files.readAllBytes(Paths.get(FIXTURE)), StandardCharsets.UTF_8);
        // .so 里读到的字节要和线上发的一致：紧凑 JSON，无空格
        configJson = compact(configJson);
        System.out.println("  tenant-config  " + configJson.length() + " 字节");

        // 必须手工注册 Unicorn2Factory：
        // AndroidEmulatorBuilder 不注册任何后端，BackendFactory 会回退到
        // ncj 版 UnicornBackend，而 native-lib-loader 在 Android 上不可用。
        AndroidEmulator emulator =
                AndroidEmulatorBuilder.for64Bit()
                        .setProcessName("com.heyteago")
                        .addBackendFactory(new Unicorn2Factory(true))
                        .build();
        try {
            // 传 File 会被当成 APK 解析（需要真实 APK），这里只有裸 .so
            VM vm = emulator.createDalvikVM();

            // ★ 关键两步，缺一个就加载不了 libc，malloc/memcpy 全变空指针：
            //
            // 1) loader 的 libraryResolver 字段默认是 null，加载依赖时
            //    AndroidElfLoader.loadInternal 会先试 libraryFile.resolveLibrary()
            //    再退到 libraryResolver，两者都 null 就打 "load dependency xxx failed"。
            //    unidbg 没有公开的 getLoader()，但 AndroidElfLoader 同时是 Memory，
            //    所以 emulator.getMemory() 拿到的就是它。
            // 2) resolver 必须是 API 23：unidbg 的 sdk19 只有 lib/ 没有 lib64/，
            //    64 位 libc 会解析失败。
            AndroidResolver resolver = new AndroidResolver(23);
            ((com.github.unidbg.spi.Loader) emulator.getMemory()).setLibraryResolver(resolver);
            emulator.getSyscallHandler().addIOResolver(resolver);
            System.out.println("  libraryResolver 已设置: "
                    + (emulator.getMemory() instanceof com.github.unidbg.spi.Loader));

            vm.setVerbose(true);

            DalvikModule dm = vm.loadLibrary(new File(SO), false);
            dm.callJNI_OnLoad(emulator);

            System.out.println("  模块基址      0x" + Long.toHexString(dm.getModule().base));
            System.out.println("  handshakePrepare @ 0x" + Long.toHexString(dm.getModule().base + 0xba10));

            DvmClass cls = vm.resolveClass(CLS);

            // handshakePrepare(byte[] out, int[] outLen, byte[] config)
            // 第 2 个参数必须是 IntArray：.so 内部走的是 SetIntArrayRegion，
            // 传 ByteArray 会抛 ClassCastException（unidbg DalvikVM64 强转）。
            ByteArray out = new ByteArray(vm, new byte[1024]);
            IntArray outLen = new IntArray(vm, new int[1]);
            ByteArray config =
                    new ByteArray(vm, configJson.getBytes(StandardCharsets.UTF_8));

            System.out.println("\n--- 调 handshakePrepare([BI[B)V ---");
            long t0 = System.currentTimeMillis();
            cls.callStaticJniMethod(emulator, "handshakePrepare([BI[B)V", out, outLen, config);
            System.out.println("--- 返回，耗时 " + (System.currentTimeMillis() - t0) + " ms ---\n");

            int len = outLen.getValue()[0];
            System.out.println("  outLen = " + len);

            byte[] buf = out.getValue();
            String s = new String(buf, 0, Math.min(buf.length, Math.max(len, 512)), StandardCharsets.UTF_8);
            int nz = s.indexOf("client_public_key");
            int start = nz >= 0 ? s.lastIndexOf('{', nz) : s.indexOf('{');
            int end = s.lastIndexOf('}');
            System.out.println("  抽出的 JSON (" + (end - start + 1) + " 字符):");
            System.out.println("  " + s.substring(start, end + 1));

        } catch (Throwable t) {
            System.out.println("\n!!! " + t.getClass().getName() + ": " + t.getMessage());
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 12); i++) {
                System.out.println("    at " + st[i]);
            }
            Throwable c = t.getCause();
            while (c != null) {
                System.out.println("  caused by " + c.getClass().getName() + ": " + c.getMessage());
                c = c.getCause();
            }
        } finally {
            try { emulator.close(); } catch (Throwable ignore) { }
        }
    }

    /** 紧凑 JSON，和 Python json.dumps(separators=(',',':')) 一致 */
    private static String compact(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean inStr = false, esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                sb.append(c);
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; sb.append(c); continue; }
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') continue;
            sb.append(c);
        }
        return sb.toString();
    }
}
