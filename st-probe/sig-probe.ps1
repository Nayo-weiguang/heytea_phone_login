# 逐个候选试 encode / decode 的 Java 签名，一次 JVM 一个（错误签名会把 libunicorn 搞崩）

. (Join-Path $PSScriptRoot "probe_env.ps1")
# 用法: & sig-probe.ps1 encode
# 注意: 不用 param()，改用 $args —— 避免 param 块被并到注释行导致静默失效。
$Method = if ($args.Count -ge 1 -and $args[0]) { $args[0] } else { "encode" }
$env:HTTP_PROXY = ""
$env:HTTPS_PROXY = ""
$env:PYTHONIOENCODING = 'utf-8'
$jdk = Get-ProbeJdk
$here = "C:\heytea-android\tools\st-probe"
$out = "$here\out"
$tmp = Get-ProbeTmp

$raw = [System.IO.File]::ReadAllText("$here\cp.txt")
$all = $raw.Trim() -split ';' | Where-Object { $_ }
$cp = ((($all | Where-Object { $_ -like '*.jar' }) +
        @("$here\lib\slf4j-simple-2.0.16.jar", "$here\sdkres")) -join ';')

$P = $Method   # 必须用拼接：PowerShell 会把 "$P(...)" 当成函数调用
$cands = @(
  ($P + '([B[B[B[BI)Ljava/lang/String;'),
  ($P + '([B[B[B[BI)[B'),
  ($P + '([B[B[B[BI)I'),
  ($P + '([B[B[B[B)Ljava/lang/String;'),
  ($P + '([BLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)Ljava/lang/String;'),
  ($P + '([BLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)[B'),
  ($P + '([BLjava/lang/String;Ljava/lang/String;Ljava/lang/String;I)I'),
  ($P + '([B[B[B[BI;J)Ljava/lang/String;'),
  ($P + '([B[B[B[BIJ)Ljava/lang/String;')
)

$report = "$tmp\sig-report.txt"
Remove-Item -LiteralPath $report -ErrorAction SilentlyContinue
Push-Location $out
foreach ($c in $cands) {
  $rlog = "$tmp\sig.txt"
  cmd /c "`"$jdk\bin\java.exe`" -XX:-CreateCoredumpOnCrash -Dfile.encoding=UTF-8 -cp `"$out;$cp`" com.heytea.probe.SigProbe `"$c`" > `"$rlog`" 2>&1"
  $verdict = (& python -c @"
import io
L=[l.strip() for l in io.open(r'$rlog',encoding='utf-8',errors='replace').read().splitlines()]
key=[l for l in L if l.startswith('=>')]
args=[l for l in L if l.startswith('实参')]
print((args[0] if args else '(没编组)') + '   ' + (key[0] if key else '(JVM 崩溃/无输出)'))
"@)
  Add-Content -LiteralPath $report -Value ($c + "`n    " + $verdict) -Encoding UTF8
}
Pop-Location

& python -c @"
import io
print('===== $Method 签名探测 =====')
print(io.open(r'$report',encoding='utf-8-sig',errors='replace').read())
"@
