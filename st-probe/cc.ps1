# 桌面 Java 编译辅助：干净的中文/英文报错，不受 PowerShell 编码和参数拆分影响

. (Join-Path $PSScriptRoot "probe_env.ps1")
# 用法: powershell -File cc.ps1 <源文件...> [-cp 额外jar,逗号分隔]
$ErrorActionPreference = "Stop"
$jdk = Get-ProbeJdk
$root = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
$tmp = Get-ProbeTmp
$here = "C:\heytea-android\tools\st-probe"
$out = "$here\out"

$src = @()
$extra = ""
foreach ($a in $args) {
  if ($a -like "-cp*") { $extra = $a.Substring(3) }
  elseif (Test-Path -LiteralPath $a) { $src += $a }
}

$want = '^(unidbg-api|unidbg-android|unidbg-unicorn2|unicorn|capstone|keystone|demumble|commons-io|commons-codec|slf4j-api|jna|jna-platform|kotlinx-coroutines-core-jvm|annotations|auto-value-annotations|error_prone|j2objc)-[0-9]'
$jars = Get-ChildItem -LiteralPath $root -Recurse -File -Filter "*.jar" -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -match $want -and $_.Name -notmatch 'sources|javadoc' }
$cp = (($jars | Group-Object Name | ForEach-Object { $_.Group[0].FullName }) -join ';')
if ($extra) { $cp = "$extra;$cp" }

New-Item -ItemType Directory -Force -Path $out | Out-Null
$log = "$tmp\javac.txt"
Remove-Item -LiteralPath $log -ErrorAction SilentlyContinue

$quoted = ($src | ForEach-Object { '"' + $_ + '"' }) -join ' '
cmd /c "`"$jdk\bin\javac.exe`" -J-Duser.language=en -nowarn -encoding UTF-8 -cp `"$cp`" -d `"$out`" $quoted > `"$log`" 2>&1"
$code = $LASTEXITCODE

if ($code -eq 0) {
  "  编译 OK  ($($src.Count) 个文件)"
  exit 0
}
"  编译失败 exit=$code`n"
$env:PYTHONIOENCODING = 'utf-8'
& python -c "import io,sys; sys.stdout.write(io.open(r'$log',encoding='utf-8',errors='replace').read()[:3000])"
exit $code
