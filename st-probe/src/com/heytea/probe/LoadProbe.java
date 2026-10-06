package com.heytea.probe;

import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.EmulatorBuilder;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.VM;
import com.github.unidbg.spi.Loader;

import java.io.File;

/**
 * 只做一件事：把 libheyteago.so 装进 unidbg，看后端能不能扛住。
 *
 * <p>用来定位 Android 上那个 SIGABRT（"attempting to detach while still running"）。
 * libheyteago.so 的 init_array 里有 6 项、而且引用了 pthread_create，也就是初始化
 * 阶段会起线程 —— 默认的 ncj UnicornBackend（unicorn 1.x）没有多线程支持，
 * 只有 Unicorn2Factory（unicorn2）才支持。所以：
 *
 *   default  -> 期望失败/挂
 *   u2       -> 期望成功
 *
 * 用法:
 *   python run_sig.py load default
 *   python run_sig.py load u2
 */
public class LoadProbe {

    private static final File SO =
            new File("C:\\heytea-android\\app\\src\\main\\assets\\libheyteago.so");

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "default";
        boolean force = args.length > 1 && "force".equals(args[1]);
        System.out.println("=== LoadProbe mode=" + mode + " force=" + force + " so=" + SO.getName() + " ===");
        System.out.println("  unicorn ncj: " + ncjWhere());

EmulatorBuilder<AndroidEmulator> builder = AndroidEmulatorBuilder.for64Bit();
        builder.setProcessName("com.heyteago");
        System.out.println("  后端: 默认 ncj UnicornBackend");

        AndroidEmulator emulator = (AndroidEmulator) builder.build();
        long t0 = System.currentTimeMillis();
        try {
            VM vm = emulator.createDalvikVM();

            AndroidResolver resolver = new AndroidResolver(23);
            ((Loader) emulator.getMemory()).setLibraryResolver(resolver);
            emulator.getSyscallHandler().addIOResolver(resolver);
            System.out.println("  libraryResolver 已设置");

            vm.setVerbose(true);

DalvikModule dm = vm.loadLibrary(SO, force);
            System.out.println("  >>> loadLibrary(force=" + force + ") OK  base=0x"
                    + Long.toHexString(dm.getModule().base)
                    + "  用时 " + (System.currentTimeMillis() - t0) + "ms");

            // 与 Android 侧 HeyTeaSigner.create() 保持同样的顺序
            com.github.unidbg.linux.android.dvm.DvmClass cls =
                    vm.resolveClass("com/donut/wx1a13d6849c0100f0/jni/HeyteagoJNI");
            System.out.println("  >>> resolveClass OK");

            // JNI_OnLoad 里会回调 getCurrentAppSignature / getPackageName / Build.*
            // 不给 JNI 的话第一个 FindClass 就炸，所以这里给个最小实现。
            vm.setJni(new com.github.unidbg.linux.android.dvm.AbstractJni() {
                @Override
                public com.github.unidbg.linux.android.dvm.DvmObject<?> callStaticObjectMethod(
                        com.github.unidbg.linux.android.dvm.BaseVM vm,
                        com.github.unidbg.linux.android.dvm.DvmClass dvmClass,
                        String method,
                        com.github.unidbg.linux.android.dvm.VarArg arg) {
                    System.out.println("      [jni] " + dvmClass + "." + method);
                    String v = dvmClass.toString().contains("Build")
                            ? "33"
                            : method.contains("Signature") ? "deadbeef" : "com.heytea.sticker";
                    return new com.github.unidbg.linux.android.dvm.StringObject(vm, v);
                }
            });

            try {
                dm.callJNI_OnLoad(emulator);
                System.out.println("  >>> JNI_OnLoad 正常返回");
            } catch (Throwable t2) {
                System.out.println("  >>> JNI_OnLoad 抛异常: " + t2);
            }

            try {
                cls.callStaticJniMethodObject(emulator, "setEnv(Ljava/lang/String;)V",
                        new com.github.unidbg.linux.android.dvm.StringObject(vm, "prod"));
                System.out.println("  >>> setEnv 调用成功");
            } catch (Throwable t3) {
                System.out.println("  >>> setEnv 失败: " + t3);
            }
            System.out.println("=== OK ===");
        } catch (Throwable t) {
            System.out.println("  >>> 失败: " + t);
            t.printStackTrace(System.out);
            System.out.println("=== FAIL ===");
        } finally {
            try {
                emulator.close();
            } catch (Throwable ignored) {
                // 关闭时的次生异常不重要
            }
        }
    }

    private static String ncjWhere() {
        try {
            return unicorn.Unicorn.class.getProtectionDomain().getCodeSource().getLocation().toString();
        } catch (Throwable t) {
            return "(取不到: " + t + ")";
        }
    }
}