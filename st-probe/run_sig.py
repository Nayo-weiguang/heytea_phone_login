"""桌面 unidbg 探针的编译 + 运行器（Python，避免 PowerShell 的引号/编码/参数坑）。

用法:
  python run_sig.py encode          逐个候选试 encode 的 Java 签名
  python run_sig.py decode          同上，试 decode
  python run_sig.py session         跑完整会话（tenant-config -> prepare -> handshake -> finish -> encode）
  python run_sig.py prepare         只跑 handshakePrepare
"""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe_env

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "out")
JAVAC = probe_env.tool("javac")
JAVA = probe_env.tool("java")
SRC = os.path.join(HERE, "src", "com", "heytea", "probe")


def classpath():
    with open(os.path.join(HERE, "cp.txt"), encoding="utf-8") as f:
        entries = [e for e in f.read().strip().split(";") if e]
    jars = [e for e in entries if e.endswith(".jar")]
    jars.append(os.path.join(HERE, "lib", "slf4j-simple-2.0.16.jar"))
    # sdkres 里放着从原 APK 取出的真实 libc++_shared.so，
    # unidbg 的 AndroidResolver 用 Class.getResource 找它
    jars.append(os.path.join(HERE, "sdkres"))
    return ";".join(jars)


def compile_java(names, cp):
    os.makedirs(OUT, exist_ok=True)
    srcs = [os.path.join(SRC, n + ".java") for n in names]
    cmd = [JAVAC, "-J-Duser.language=en", "-nowarn", "-encoding", "UTF-8",
           "-cp", cp, "-d", OUT] + srcs
    p = subprocess.run(cmd, capture_output=True)
    err = (p.stdout + p.stderr).decode("utf-8", "replace")
    if p.returncode != 0:
        print("编译失败:\n" + err[:3000])
        sys.exit(1)
    print("  编译 OK (%s)" % ", ".join(names))


def run(main, cp, arg=None, timeout=300):
    cmd = [JAVA, "-XX:-CreateCoredumpOnCrash", "-Dfile.encoding=UTF-8",
           "-cp", OUT + ";" + cp, main]
    if arg:
        cmd.append(arg)
    try:
        p = subprocess.run(cmd, capture_output=True, timeout=timeout)
        return (p.stdout + p.stderr).decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        return "(超时)"


def interesting(text):
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith("at ") or s.startswith("Caused by"):
            continue
        if "[main] INFO" in s and "symbol" in s:
            continue
        if s.startswith(">>>") or s.startswith("q1") or s.startswith("q16"):
            continue
        if "SLF4J" in s:
            continue
        out.append(s)
    return out


CANDIDATES = [
    "([B[B[B[BI)Ljava/lang/String;",
    "([B[B[B[BI)[B",
    "([B[B[B[BI)I",
    "([B[B[B[B)Ljava/lang/String;",
    "([BLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;",
    "([BLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)[B",
    "([BLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)I",
    "([B[B[B[B I)Ljava/lang/String;",
    "([B[B[B[BI;J)Ljava/lang/String;",
]


def sig(method):
    cp = classpath()
    compile_java(["SigProbe"], cp)
    print("\n===== %s 签名探测 =====" % method)
    for desc in CANDIDATES:
        text = run("com.heytea.probe.SigProbe", cp, method + desc)
        lines = interesting(text)
        verdict = next((l for l in lines if l.startswith("=>")), "(无输出/JVM 崩溃)")
        args = next((l for l in lines if l.startswith("实参")), "")
        print("  %-62s" % desc)
        print("      %s %s" % (args, verdict))


def main():
    what = sys.argv[1] if len(sys.argv) > 1 else "encode"
    cp = classpath()
    if what in ("encode", "decode"):
        sig(what)
    elif what == "session":
        compile_java(["StSession"], cp)
        text = run("com.heytea.probe.StSession", cp)
        print("\n".join(interesting(text)))
    elif what == "prepare":
        compile_java(["StProbe"], cp)
        text = run("com.heytea.probe.StProbe", cp)
        print("\n".join(interesting(text)))
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
