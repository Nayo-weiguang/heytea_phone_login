"""Compile + run the desktop unidbg LoadProbe (avoids PowerShell quoting pain)."""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import probe_env

HERE = os.path.dirname(os.path.abspath(__file__))
JDK = probe_env.jdk_root()
TMP = probe_env.tmp_dir()
SRC = os.path.join(HERE, "src", "com", "heytea", "probe", "LoadProbe.java")
OUT = os.path.join(HERE, "out")

raw = open(os.path.join(HERE, "cp.txt"), encoding="utf-8").read()
jars = [e for e in raw.strip().split(";") if e.endswith(".jar")]

# Android 侧把 ncj 的 NativeLoader 排除了，桌面要补回来
ncl = os.path.join(os.environ["USERPROFILE"], ".gradle", "caches", "modules-2",
                   "files-2.1", "org.scijava", "native-lib-loader")
for root, _, names in os.walk(ncl):
    for n in names:
        if n.endswith(".jar"):
            jars.append(os.path.join(root, n))
            break
    else:
        continue
    break

jars.append(os.path.join(HERE, "sdkres"))
jars.append(OUT)
cp = ";".join(jars)

r = subprocess.run([probe_env.tool("javac"), "-nowarn",
                    "-encoding", "UTF-8", "-cp", cp, "-d", OUT, SRC],
                   capture_output=True, text=True, errors="replace")
if r.returncode != 0:
    print("  javac 失败:")
    print((r.stdout + r.stderr)[:2500])
    sys.exit(1)
print("  javac OK (%d 个 jar)" % len(jars))

mode = sys.argv[1] if len(sys.argv) > 1 else "default"
force = sys.argv[2] if len(sys.argv) > 2 else ""
tail = int(sys.argv[3]) if len(sys.argv) > 3 else 30
log = os.path.join(TMP, "lp-%s-%s.txt" % (mode, force or "noforce"))

args = [probe_env.tool("java"), "-cp", cp,
        "com.heytea.probe.LoadProbe", mode]
if force:
    args.append(force)
with open(log, "wb") as f:
    p = subprocess.Popen(args, stdout=f, stderr=subprocess.STDOUT)
    try:
        p.wait(timeout=240)
    except subprocess.TimeoutExpired:
        p.kill()
        print("  !! 240s 没结束，杀了")

t = open(log, encoding="utf-8", errors="replace").read()
lines = t.splitlines()
print("  输出 %d 行，末尾 %d 行:" % (len(lines), tail))
for l in lines[-tail:]:
    print("    " + l.strip()[:175])