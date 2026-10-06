# Locate the JDK and a temp dir -- no machine-specific paths in this repo.
#
# JDK resolution order:
#   1. $env:HEYTEA_JDK, then $env:JAVA_HOME
#   2. javac on PATH
#   3. common install dirs (Adoptium / Zulu / Microsoft / Oracle)
#
# Temp dir: $env:HEYTEA_TMP, else $env:TEMP.
#
# NOTE: keep this file ASCII-only. PowerShell 5.1 reads BOM-less .ps1 as ANSI,
# so non-ASCII comments get mangled and can break quote pairing.
#
#   . (Join-Path $PSScriptRoot "probe_env.ps1")
#   $javac = Get-ProbeJavac

function Get-ProbeJdk {
    $cands = @()
    if ($env:HEYTEA_JDK) { $cands += $env:HEYTEA_JDK }
    if ($env:JAVA_HOME)  { $cands += $env:JAVA_HOME }

    foreach ($c in $cands) {
        # JAVA_HOME often points at a directory that no longer exists, so
        if (-not $c) { continue }
        if (-not (Test-Path -LiteralPath $c -PathType Container)) { continue }
        if (Test-Path (Join-Path $c "bin\javac.exe")) { return $c }
    }

    $onPath = Get-Command javac -ErrorAction SilentlyContinue
    if ($onPath) {
        # <jdk>\bin\javac.exe -> <jdk>
        return (Split-Path (Split-Path $onPath.Source -Parent) -Parent)
    }

    foreach ($pat in @(
            "C:\Program Files\Eclipse Adoptium\jdk-*",
            "C:\Program Files\Zulu\zulu-*",
            "C:\Program Files\Microsoft\jdk-*",
            "C:\Program Files\Java\jdk-*")) {
        $hits = Get-ChildItem -Path $pat -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name
        if ($hits) { return $hits[-1].FullName }
    }

    throw "JDK not found. Set HEYTEA_JDK or JAVA_HOME to a JDK root."
}

function Get-ProbeTmp {
    if ($env:HEYTEA_TMP) { return $env:HEYTEA_TMP }
    if ($env:TEMP)       { return $env:TEMP }
    return [System.IO.Path]::GetTempPath()
}

function Get-ProbeJavac {
    $p = Join-Path (Get-ProbeJdk) "bin\javac.exe"
    if (Test-Path $p) { return $p }
    $c = Get-Command javac -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    throw "javac not found under $(Get-ProbeJdk)"
}

function Get-ProbeJava {
    $p = Join-Path (Get-ProbeJdk) "bin\java.exe"
    if (Test-Path $p) { return $p }
    $c = Get-Command java -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    throw "java not found under $(Get-ProbeJdk)"
}