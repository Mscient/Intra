param(
    [Parameter(Mandatory = $true)] [string]$OutputRoot,
    [string]$ExpectedCommit = $env:GITHUB_SHA
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to Microsoft JDK 21.0.13.0.2.' }
$release = Get-Content -LiteralPath (Join-Path $env:JAVA_HOME 'release')
if ($release -cnotcontains 'IMPLEMENTOR="Microsoft"' -or $release -cnotcontains 'JAVA_VERSION="21.0.13.0.2"') {
    throw 'CI requires the pinned Microsoft JDK 21.0.13.0.2.'
}
if ([string]::IsNullOrWhiteSpace($ExpectedCommit)) {
    $ExpectedCommit = (& git -C $repoRoot rev-parse --verify HEAD | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify checked-out HEAD.' }
}
if ($ExpectedCommit -cnotmatch '^[0-9a-f]{40}$') { throw 'ExpectedCommit must be an exact source commit.' }
# The shared runner refuses pre-existing output and publishes only after all suites pass.
& node (Join-Path $repoRoot 'Intra-Paper-System\test-offline.mjs') --output-root ([System.IO.Path]::GetFullPath($OutputRoot)) --java-home $env:JAVA_HOME --expected-commit $ExpectedCommit
$exitCode = $LASTEXITCODE
if ($env:GITHUB_STEP_SUMMARY) {
    "Offline Java validation exit: $exitCode. Exact suite results and source/JAR hashes are in logs/summary.json." | Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Encoding utf8
}
if ($exitCode -ne 0) { throw "Offline Java validation failed with exit code $exitCode." }
