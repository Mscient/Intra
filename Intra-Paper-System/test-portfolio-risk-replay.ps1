param([string]$JarPath)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$javac = (Get-Command javac -ErrorAction Stop).Source
$java = (Get-Command java -ErrorAction Stop).Source
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($version -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $version" }
$runRoot = Join-Path $env:LOCALAPPDATA ('Intra-Paper-System\risk-replay-checks-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $runRoot 'classes'
$testClasses = Join-Path $runRoot 'test-classes'
New-Item -ItemType Directory -Force -Path $classes, $testClasses | Out-Null
$tests = @(
    'simulation\portfolio\risk\PortfolioRiskReplayTest.java',
    'simulation\portfolio\PortfolioReplayEngineTest.java',
    'simulation\portfolio\PortfolioExecutionCostScheduleTest.java',
    'simulation\portfolio\PortfolioReplayArtifactVerifierTest.java',
    'simulation\portfolio\PortfolioSettlementFixtures.java',
    'simulation\portfolio\PortfolioSettlementTest.java'
) | ForEach-Object { Join-Path $projectRoot ('src\test\java\com\mscient\intrapaper\' + $_) }
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $sources = @(Get-ChildItem (Join-Path $projectRoot 'src\main\java') -Filter '*.java' -Recurse | ForEach-Object FullName)
    $sourceArguments = Join-Path $runRoot 'javac-sources.args'
    [IO.File]::WriteAllLines($sourceArguments, [string[]]@(($sources + $tests) | Sort-Object | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), [Text.UTF8Encoding]::new($false))
    & $javac --release 21 -encoding UTF-8 -d $classes ('@' + $sourceArguments)
} else {
    $classes = (Resolve-Path -LiteralPath $JarPath).Path
    & $javac --release 21 -encoding UTF-8 -cp $classes -d $testClasses @tests
}
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
$testNames = @('risk.PortfolioRiskReplayTest', 'PortfolioReplayEngineTest', 'PortfolioExecutionCostScheduleTest', 'PortfolioReplayArtifactVerifierTest', 'PortfolioSettlementTest')
foreach ($testName in $testNames) {
    & $java -cp "$classes;$testClasses" "com.mscient.intrapaper.simulation.portfolio.$testName" $projectRoot
    if ($LASTEXITCODE -ne 0) { throw "Check failed: $testName" }
}
Write-Output "Checks passed; compiled evidence retained at $runRoot"
