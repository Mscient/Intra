param(
    [Parameter(Mandatory=$true)][string]$JarPath,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build\feed-audit-drill')
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSHOME 'pwsh.exe') -NoProfile -NonInteractive -File (Join-Path $PSScriptRoot 'test-feed-completeness-audit.ps1') `
    -JarPath $JarPath -OutputRoot $OutputRoot -Drill
if ($LASTEXITCODE -ne 0) { throw 'Synthetic feed audit drill failed.' }
