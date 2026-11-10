param([Parameter(Mandatory=$true)][string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$release = Get-Content -LiteralPath (Join-Path $env:JAVA_HOME 'release')
if ($release -cnotcontains 'IMPLEMENTOR="Microsoft"' -or $release -cnotcontains 'JAVA_VERSION="21.0.13.0.2"') { throw 'Use pinned Microsoft JDK 21.0.13.0.2.' }
$run = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $run -Force | Out-Null
$prior = $env:INTRA_PAPER_BUILD_DIR
try {
    $env:INTRA_PAPER_BUILD_DIR = $run
    & (Join-Path $PSScriptRoot 'build.ps1') | Out-Null
    $jar = Join-Path $run 'g5-readiness.jar'
    Copy-Item -LiteralPath (Join-Path $run 'intra-paper-system.jar') -Destination $jar
    # Reuse the exact production compilation; only the standalone entrypoint differs.
    & (Join-Path $env:JAVA_HOME 'bin/jar.exe') --update --file $jar --main-class com.mscient.intrapaper.g5.G5Cli
    if ($LASTEXITCODE -ne 0) { throw 'G5 packaging failed.' }
    Write-Output $jar
} finally { $env:INTRA_PAPER_BUILD_DIR = $prior }
