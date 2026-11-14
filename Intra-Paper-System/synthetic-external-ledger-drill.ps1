param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build\synthetic-external-ledger-drills')
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $version" }
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'JAR is missing.' }
$ledgerDrillRoot = Join-Path ([System.IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$replayInputs = Join-Path $ledgerDrillRoot 'replay-inputs'
$externalInputs = Join-Path $ledgerDrillRoot 'external'
New-Item -ItemType Directory -Force -Path $replayInputs, $externalInputs | Out-Null
$fixture = Join-Path $PSScriptRoot 'examples\portfolio-settlement'
Get-ChildItem -LiteralPath $fixture -File | Copy-Item -Destination $replayInputs
$externalFixture = Join-Path $PSScriptRoot 'examples\external-ledger-reconciliation\itemized'
foreach ($name in @('ledger.csv', 'coverage.csv', 'manifest-template.properties')) {
    Copy-Item -LiteralPath (Join-Path $externalFixture $name) -Destination (Join-Path $externalInputs $name)
}
$inputs = Join-Path $replayInputs 'inputs.csv'
$settings = Join-Path $replayInputs 'portfolio.properties'
$replayRoot = Join-Path $ledgerDrillRoot 'replays'
& $java -cp $JarPath com.mscient.intrapaper.cli.IntraPaperCli portfolio-run $inputs $settings $replayRoot
if ($LASTEXITCODE -ne 0) { throw "Synthetic replay failed: $LASTEXITCODE" }
$runs = @(Get-ChildItem -LiteralPath $replayRoot -Directory | Where-Object { $_.Name -match '^[0-9a-f]{64}$' })
if ($runs.Count -ne 1) { throw 'Expected exactly one completed synthetic replay.' }
$run = $runs[0].FullName
$template = [System.IO.File]::ReadAllText((Join-Path $externalInputs 'manifest-template.properties'))
$ledgerHash = (Get-FileHash -LiteralPath (Join-Path $externalInputs 'ledger.csv') -Algorithm SHA256).Hash.ToLowerInvariant()
$coverageHash = (Get-FileHash -LiteralPath (Join-Path $externalInputs 'coverage.csv') -Algorithm SHA256).Hash.ToLowerInvariant()
$manifest = Join-Path $externalInputs 'external.properties'
$manifestText = $template.Replace('@REPLAY_RUN_ID@', $runs[0].Name).Replace('@LEDGER_SHA256@', $ledgerHash).Replace('@COVERAGE_SHA256@', $coverageHash)
[System.IO.File]::WriteAllText($manifest, $manifestText, [System.Text.UTF8Encoding]::new($false))
$reports = Join-Path $ledgerDrillRoot 'audits'
$auditCli = 'com.mscient.intrapaper.audit.reconciliation.ExternalLedgerAuditCli'
& $java -cp $JarPath $auditCli audit PORTFOLIO $run $inputs $settings $manifest $reports
if ($LASTEXITCODE -ne 0) { throw "Synthetic external-ledger audit did not PASS: $LASTEXITCODE" }
$audits = @(Get-ChildItem -LiteralPath $reports -Directory | Where-Object { $_.Name -match '^[0-9a-f]{64}$' })
if ($audits.Count -ne 1) { throw 'Expected exactly one hashed audit.' }
$audit = $audits[0].FullName
& $java -cp $JarPath $auditCli verify $audit PORTFOLIO $run $inputs $settings $manifest
if ($LASTEXITCODE -ne 0) { throw "Synthetic semantic verification failed: $LASTEXITCODE" }
& $java -cp $JarPath $auditCli verify-artifact $audit
if ($LASTEXITCODE -ne 0) { throw "Synthetic artifact verification failed: $LASTEXITCODE" }
Write-Output "Synthetic external-ledger drill PASS; report SHA-256=$($audits[0].Name); evidence=$ledgerDrillRoot"
