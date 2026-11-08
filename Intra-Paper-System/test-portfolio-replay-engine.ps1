$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdkHome = $env:JAVA_HOME
$javac = if ([string]::IsNullOrWhiteSpace($jdkHome)) { $null } else { Join-Path $jdkHome 'bin\javac.exe' }
$java = if ([string]::IsNullOrWhiteSpace($jdkHome)) { $null } else { Join-Path $jdkHome 'bin\java.exe' }
if (-not $javac -or -not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) {
    throw 'Set JAVA_HOME to a JDK 21 installation before running portfolio-replay checks.'
}
$compilerVersion = (& $javac -version 2>&1 | Out-String).Trim()
if ($compilerVersion -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $compilerVersion" }

$testRoot = Join-Path $env:LOCALAPPDATA 'Intra-Paper-System\portfolio-replay-tests'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$testRootAbsolute = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $testRoot).Path)
$testRootItem = Get-Item -LiteralPath $testRootAbsolute -Force
if (($testRootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Refusing to use a portfolio-replay test directory that is a reparse point.'
}
$runRoot = Join-Path $testRootAbsolute ([guid]::NewGuid().ToString('N'))
$runRootAbsolute = [System.IO.Path]::GetFullPath($runRoot)
$rootPrefix = $testRootAbsolute.TrimEnd([System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if (-not $runRootAbsolute.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Temporary focused-check output must stay below the portfolio-replay test directory.'
}
$mainClasses = Join-Path $runRootAbsolute 'classes'
$testClasses = Join-Path $runRootAbsolute 'test-classes'
New-Item -ItemType Directory -Force -Path $mainClasses, $testClasses | Out-Null
try {
    $mainSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Recurse -File -Filter '*.java' |
        ForEach-Object { $_.FullName })
    $testSources = @(
        (Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\simulation\portfolio\PortfolioReplayEngineTest.java'),
        (Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\simulation\portfolio\PortfolioExecutionCostScheduleTest.java'),
        (Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\simulation\portfolio\PortfolioReplayArtifactVerifierTest.java'),
        (Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\simulation\portfolio\PortfolioSettlementFixtures.java'),
        (Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\simulation\portfolio\PortfolioSettlementTest.java')
    )
    if ($mainSources.Count -eq 0 -or @($testSources | Where-Object { -not (Test-Path -LiteralPath $_) }).Count -ne 0) {
        throw 'Main sources and portfolio-replay/cost test sources are required.'
    }
    $sourceArguments = Join-Path $runRootAbsolute 'javac-sources.args'
    [IO.File]::WriteAllLines($sourceArguments, [string[]]@($mainSources | Sort-Object | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), [Text.UTF8Encoding]::new($false))
    & $javac --release 21 -encoding UTF-8 -d $mainClasses ('@' + $sourceArguments)
    if ($LASTEXITCODE -ne 0) { throw "Main compilation failed with exit code $LASTEXITCODE" }
    & $javac --release 21 -encoding UTF-8 -cp $mainClasses -d $testClasses @testSources
    if ($LASTEXITCODE -ne 0) { throw "Focused-check compilation failed with exit code $LASTEXITCODE" }
    & $java -cp "$mainClasses;$testClasses" com.mscient.intrapaper.simulation.portfolio.PortfolioReplayEngineTest
    if ($LASTEXITCODE -ne 0) { throw "Portfolio-replay checks failed with exit code $LASTEXITCODE" }
    & $java -cp "$mainClasses;$testClasses" com.mscient.intrapaper.simulation.portfolio.PortfolioExecutionCostScheduleTest
    if ($LASTEXITCODE -ne 0) { throw "Portfolio execution-cost checks failed with exit code $LASTEXITCODE" }
    & $java -cp "$mainClasses;$testClasses" com.mscient.intrapaper.simulation.portfolio.PortfolioReplayArtifactVerifierTest
    if ($LASTEXITCODE -ne 0) { throw "Portfolio artifact-verifier checks failed with exit code $LASTEXITCODE" }
    & $java -cp "$mainClasses;$testClasses" com.mscient.intrapaper.simulation.portfolio.PortfolioSettlementTest
    if ($LASTEXITCODE -ne 0) { throw "Portfolio settlement checks failed with exit code $LASTEXITCODE" }
} finally {
    $resolvedRunRoot = [System.IO.Path]::GetFullPath($runRootAbsolute)
    if (-not $resolvedRunRoot.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to remove test output outside the portfolio-replay test directory.'
    }
    $item = Get-Item -LiteralPath $resolvedRunRoot -Force -ErrorAction SilentlyContinue
    if ($item -and ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Refusing to remove a test output directory that is a reparse point.'
    }
    if ($item) { Remove-Item -LiteralPath $resolvedRunRoot -Recurse -Force }
}
