# 编译 + 运行桌面 unidbg 探针（验证 libsdk_core.so 能否在 unidbg 里跑通）

. (Join-Path $PSScriptRoot "probe_env.ps1")
$ErrorActionPreference = "Stop"
$jdk = Get-ProbeJdk
$root = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
$here = "C:\heytea-android\tools\st-probe"

$want = '^(unidbg-api|unidbg-android|unidbg-unicorn2|unicorn|capstone|keystone|demumble|commons-io|commons-codec|slf4j-api|jna|jna-platform|annotations|auto-value-annotations|error_prone|j2objc|kotlinx-coroutines-core-jvm)'
$jars = Get-ChildItem -LiteralPath $root -Recurse -File -Filter "*.jar" -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -match $want -and $_.Name -notmatch 'sources|javadoc|gradle' }

$cp = ($jars | Group-Object Name | ForEach-Object { $_.Group[0].FullName }) -join ';'
"  classpath: $(($jars | Group-Object Name).Count) 个 jar"

$out = "$here\out"
New-Item -ItemType Directory -Force -Path $out | Out-Null

& (Get-ProbeJavac) -nowarn -encoding UTF-8 -cp $cp -d $out `
  "$here\src\com\heytea\probe\StProbe.java" 2>&1 | Select-Object -First 25
if (-not $?) { "`n  编译失败"; exit 1 }
"  编译 OK"

"`n========== 运行 =========="
Push-Location $out
& (Get-ProbeJava) -cp "$out;$cp" com.heytea.probe.StProbe 2>&1
Pop-Location
