param(
    [string]$JarPath,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build\external-ledger-checks'),
    [switch]$ReplayRegressions
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $version" }
$runRoot = Join-Path ([System.IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $runRoot 'classes'
$testClasses = Join-Path $runRoot 'test-classes'
New-Item -ItemType Directory -Force -Path $classes, $testClasses | Out-Null
$test = Join-Path $PSScriptRoot 'src\test\java\com\mscient\intrapaper\audit\reconciliation\ExternalLedgerAuditTest.java'
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java') -Recurse -File -Filter '*.java' | ForEach-Object FullName)
    $sourceArgs = Join-Path $runRoot 'main-sources.args'
    $sourceLines = @($sources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' })
    [System.IO.File]::WriteAllLines($sourceArgs, $sourceLines, [System.Text.UTF8Encoding]::new($false))
    & $javac --release 21 -encoding UTF-8 -d $classes ('@' + $sourceArgs)
    if ($LASTEXITCODE -ne 0) { throw "Production compilation failed: $LASTEXITCODE" }
    $classpath = $classes
} else {
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'JAR is missing.' }
    $classpath = [System.IO.Path]::GetFullPath($JarPath)
}
& $javac --release 21 -encoding UTF-8 -cp $classpath -d $testClasses $test
if ($LASTEXITCODE -ne 0) { throw "Acceptance test compilation failed: $LASTEXITCODE" }
& $java -ea -cp ($classpath + [System.IO.Path]::PathSeparator + $testClasses) `
    com.mscient.intrapaper.audit.reconciliation.ExternalLedgerAuditTest $PSScriptRoot (Join-Path $runRoot 'synthetic-evidence') 2>&1 |
    Tee-Object -FilePath (Join-Path $runRoot 'acceptance.log')
if ($LASTEXITCODE -ne 0) { throw "External-ledger acceptance checks failed: $LASTEXITCODE" }
if ($ReplayRegressions) {
    $regressionSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\test\java\com\mscient\intrapaper\simulation\portfolio') -File -Filter '*.java' | ForEach-Object FullName)
    $regressionSources += Join-Path $PSScriptRoot 'src\test\java\com\mscient\intrapaper\audit\portfolio\PortfolioSettlementAuditTest.java'
    $regressionArgs = Join-Path $runRoot 'regression-sources.args'
    [System.IO.File]::WriteAllLines($regressionArgs, @($regressionSources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), [System.Text.UTF8Encoding]::new($false))
    & $javac --release 21 -encoding UTF-8 -cp $classpath -d $testClasses ('@' + $regressionArgs)
    if ($LASTEXITCODE -ne 0) { throw "Replay regression compilation failed: $LASTEXITCODE" }
    $suites = @('simulation.portfolio.PortfolioAccountingLedgerTest', 'simulation.portfolio.PortfolioReplayEngineTest',
        'simulation.portfolio.PortfolioExecutionCostScheduleTest', 'simulation.portfolio.PortfolioReplayArtifactVerifierTest',
        'simulation.portfolio.PortfolioSettlementTest', 'audit.portfolio.PortfolioSettlementAuditTest')
    foreach ($suite in $suites) {
        & $java -ea -cp ($classpath + [System.IO.Path]::PathSeparator + $testClasses) ('com.mscient.intrapaper.' + $suite) $PSScriptRoot 2>&1 |
            Tee-Object -FilePath (Join-Path $runRoot ($suite + '.log'))
        if ($LASTEXITCODE -ne 0) { throw "Replay regression failed: $suite (exit $LASTEXITCODE)" }
    }
}
Write-Output "Synthetic acceptance evidence retained at $runRoot"
