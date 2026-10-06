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
import com.github.unidbg.spi.Loader;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 猜 encode / decode 的 Java 签名。一次 JVM 只试一个候选：
 * 错误签名可能把 libunicorn 搞崩，污染同进程里的后续尝试。
 *
 * 用法: java SigProbe "encode([B[B[B[BI)Ljava/lang/String;"
 * 不需要建会话 —— 参数编组（ClassCastException）发生在任何加密之前。
 */
public final class SigProbe {

    private static final String SO =
            "C:\\heytea-android\\app\\src\\main\\assets\\libsdk_core.so";
    private static final String CLS = "com/securesdk/nativebridge/SdkNative";
    private static final String PAYLOAD = "{\"mobile\":\"13800000000\"}";
    private static final String DOMAIN = "app-go.heytea.com";

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("  用法: SigProbe \"encode(...)\"");
            return;
        }
        String call = args[0];                 // 形如 encode([B[B[B[BI)Ljava/lang/String;
        String method = call.substring(0, call.indexOf('('));
        String desc = call.substring(call.indexOf('('));
        System.out.println("  候选: " + call);

        AndroidEmulator emulator = null;
        try {
            emulator = AndroidEmulatorBuilder.for64Bit()
                    .setProcessName("com.heyteago")
                    .addBackendFactory(new Unicorn2Factory(true))
                    .build();
            VM vm = emulator.createDalvikVM();
            AndroidResolver r = new AndroidResolver(23);
            ((Loader) emulator.getMemory()).setLibraryResolver(r);
            emulator.getSyscallHandler().addIOResolver(r);

            DalvikModule dm = vm.loadLibrary(new File(SO), false);
            dm.callJNI_OnLoad(emulator);
            DvmClass cls = vm.resolveClass(CLS);

            Object[] a = build(vm, call);
            StringBuilder sb = new StringBuilder("  实参(" + a.length + "): ");
            for (Object o : a) {
                sb.append(o instanceof ByteArray ? "byte[" + ((ByteArray) o).length() + "]"
                        : String.valueOf(o)).append(' ');
            }
            System.out.println(sb);

            DvmObject<?> ret = cls.callStaticJniMethodObject(emulator, call, a);
            System.out.println("  => 签名可编组，返回 = "
                    + (ret == null ? "null" : ret.getValue()));

        } catch (IllegalArgumentException e) {
            System.out.println("  => 方法不存在: " + e.getMessage());
        } catch (ClassCastException e) {
            System.out.println("  => 编组失败(签名不对): " + e.getMessage());
        } catch (Throwable t) {
            String m = String.valueOf(t.getMessage());
            String kind = (m.contains("Fetch memory") || m.contains("UC_ERR")
                    || m.contains("SIGSEGV") || m.contains("session"))
                    ? "签名对！只是没建会话" : "其它错误";
            System.out.println("  => " + kind + ": " + t.getClass().getSimpleName()
                    + ": " + (m.length() > 140 ? m.substring(0, 140) : m));
        } finally {
            try { if (emulator != null) emulator.close(); } catch (Throwable ignore) { }
        }
    }

    /** 按描述符的「参数部分」依次塞值：[B -> byte[]，Ljava/lang/String; -> String，I -> int */
    private static Object[] build(VM vm, String call) {
        int lp = call.indexOf('(');
        int rp = call.lastIndexOf(')');
        String params = call.substring(lp + 1, rp);      // 只取括号内，返回类型不算参数
        java.util.ArrayList<Object> out = new java.util.ArrayList<>();
        int i = 0;
        while (i < params.length()) {
            char c = params.charAt(i);
            if (c == '[') {
                out.add(new ByteArray(vm, PAYLOAD.getBytes(StandardCharsets.UTF_8)));
                i += 2;                                   // 跳过 [ 或 [B
            } else if (c == 'L') {
                out.add(DOMAIN);
                i = params.indexOf(';', i) + 1;
            } else if (c == 'I') {
                out.add(2);
                i++;
            } else {
                i++;
            }
        }
        return out.toArray();
    }
}
