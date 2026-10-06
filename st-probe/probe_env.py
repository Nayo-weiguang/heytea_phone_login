"""定位 JDK 与临时目录 —— 不写死任何人的本机路径。

优先级：
  1. 环境变量 JAVA_HOME / HEYTEA_JDK
  2. PATH 里的 javac
  3. 常见的 Adoptium / Zulu / Microsoft JDK 安装目录

Windows 之外（Linux/mac）直接用 `javac` / `java`，不依赖目录结构。
"""
import glob
import os
import shutil
import subprocess
import sys
import tempfile

# 允许用环境变量覆盖：JDK 路径和临时目录都不再写死在脚本里
JDK = os.environ.get("HEYTEA_JDK") or os.environ.get("JAVA_HOME") or ""


def _probe_jdk():
    """返回一个可用的 JDK 根目录，找不到就返回 None。"""
    if JDK:
        if os.path.isfile(os.path.join(JDK, "bin", "javac.exe")):
            return JDK
        if os.path.isfile(os.path.join(JDK, "bin", "javac")):
            return JDK

    which = shutil.which("javac")
    if which:
        # <jdk>/bin/javac -> <jdk>
        return os.path.dirname(os.path.dirname(os.path.abspath(which)))

    if os.name == "nt":
        pats = [
            r"C:\Program Files\Eclipse Adoptium\jdk-*",
            r"C:\Program Files\Zulu\zulu-*",
            r"C:\Program Files\Microsoft\jdk-*",
            r"C:\Program Files\Java\jdk-*",
        ]
        for pat in pats:
            hits = sorted(glob.glob(pat))
            if hits:
                return hits[-1]
    return None


def jdk_root():
    r = _probe_jdk()
    if not r:
        raise RuntimeError(
            "找不到 JDK。请设置环境变量 JAVA_HOME 或 HEYTEA_JDK 指向 JDK 根目录。")
    return r


def tool(name):
    """返回 javac / java 的可执行文件路径。"""
    exe = name + (".exe" if os.name == "nt" else "")
    p = os.path.join(jdk_root(), "bin", exe)
    if os.path.isfile(p):
        return p
    found = shutil.which(name)
    if found:
        return found
    raise RuntimeError("JDK 里找不到 %s（根目录=%s）" % (name, jdk_root()))


def tmp_dir():
    """临时目录：优先 HEYTEA_TMP，否则用系统临时目录。"""
    return os.environ.get("HEYTEA_TMP") or tempfile.gettempdir()


def java_version():
    """打印 java -version 到 stderr，便于排查。"""
    try:
        subprocess.run([tool("java"), "-version"], stderr=subprocess.STDOUT)
    except Exception as e:
        sys.stderr.write("java -version 失败: %s\n" % e)