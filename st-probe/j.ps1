param(
  [string]$Main = "com.heytea.probe.StProbe",
  [string[]]$Src = @("C:\heytea-android\tools\st-probe\src\com\heytea\probe\StProbe.java"),
  [switch]$NoCompile,
  [int]$Tail = 30
)

. (Join-Path $PSScriptRoot "probe_env.ps1")
# 桌面 Java 编译 + 运行一体化。
# classpath 从 cp.txt 读（由 Gradle :app:dumpCp 生成，权威的运行时依赖），不重扫缓存。
# 注意：param() 必须在第一行。前面放注释会导致 PowerShell 报错行号偏移，排查时极具误导性。

$ErrorActionPreference = "Stop"
$jdk = Get-ProbeJdk
$tmp = Get-ProbeTmp
$here = "C:\heytea-android\tools\st-probe"
$out = Join-Path $here "out"

# .aar 是 Android 库，桌面 classpath 上没用，只要 jar。
$raw = [System.IO.File]::ReadAllText((Join-Path $here "cp.txt"))
$all = $raw.Trim() -split ';' | Where-Object { $_ }
$jars = $all | Where-Object { $_ -like '*.jar' }
$cp = $jars -join ';'

New-Item -ItemType Directory -Force -Path $out | Out-Null
"  classpath $($jars.Count) 个 jar (共 $($all.Count) 条，已过滤 .aar)"

if (-not $NoCompile) {
  $log = Join-Path $tmp "javac.txt"
  Remove-Item -LiteralPath $log -ErrorAction SilentlyContinue
  $quoted = ($Src | ForEach-Object { '"' + $_ + '"' }) -join ' '
  cmd /c "`"$jdk\bin\javac.exe`" -J-Duser.language=en -nowarn -encoding UTF-8 -cp `"$cp`" -d `"$out`" $quoted > `"$log`" 2>&1"
  if ($LASTEXITCODE -ne 0) {
    "  编译失败`n"
    $env:PYTHONIOENCODING = 'utf-8'
    & python -c "import io,sys; sys.stdout.write(io.open(r'$log',encoding='utf-8',errors='replace').read()[:3000])"
    exit 1
  }
  "  编译 OK"
}

$rlog = Join-Path $tmp "jrun.txt"
Remove-Item -LiteralPath $rlog -ErrorAction SilentlyContinue
Push-Location $out
cmd /c "`"$jdk\bin\java.exe`" -Dfile.encoding=UTF-8 -cp `"$out;$cp`" $Main > `"$rlog`" 2>&1"
$rc = $LASTEXITCODE
Pop-Location
"  运行 exit=$rc`n"
$env:PYTHONIOENCODING = 'utf-8'
& python -c @"
import io
L = io.open(r'$rlog', encoding='utf-8', errors='replace').read().splitlines()
n = $Tail
for l in L[:6]:      print(' ', l[:150])
if len(L) > 6 + n:   print('  ... (省略 %d 行)' % (len(L) - 6 - n))
for l in L[-n:]:     print(' ', l[:150])
"@
exit $rc
