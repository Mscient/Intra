$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$taskJdkHome = $env:JAVA_HOME
$javac = if ([string]::IsNullOrWhiteSpace($taskJdkHome)) { $null } else { Join-Path $taskJdkHome 'bin\javac.exe' }
$java = if ([string]::IsNullOrWhiteSpace($taskJdkHome)) { $null } else { Join-Path $taskJdkHome 'bin\java.exe' }
if (-not $javac -or -not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) {
    throw 'Set JAVA_HOME to a JDK 21 installation before running portfolio trial evaluation checks.'
}
$compilerVersion = (& $javac -version 2>&1 | Out-String).Trim()
if ($compilerVersion -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $compilerVersion" }

$testRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'build\portfolio-trial-evaluation-tests'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$testRootItem = Get-Item -LiteralPath $testRoot -Force
if (($testRootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Refusing to use a portfolio evaluation test directory that is a reparse point.'
}
$rootPrefix = $testRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
$runRoot = [System.IO.Path]::GetFullPath((Join-Path $testRoot ([guid]::NewGuid().ToString('N'))))
if (-not $runRoot.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Focused check output must stay within the evaluation test directory.'
}
$mainClasses = Join-Path $runRoot 'classes'
$testClasses = Join-Path $runRoot 'test-classes'
New-Item -ItemType Directory -Force -Path $mainClasses, $testClasses | Out-Null
try {
    $mainSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Recurse -File -Filter '*.java' |
        ForEach-Object { $_.FullName })
    $testSources = @(
        (Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\evaluation\portfolio\PortfolioTrialEvaluationTest.java'),
        (Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\simulation\portfolio\PortfolioReplayEngineTest.java'),
        (Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\simulation\portfolio\PortfolioExecutionCostScheduleTest.java'),
        (Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\simulation\portfolio\PortfolioReplayArtifactVerifierTest.java'),
        (Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\simulation\portfolio\PortfolioSettlementFixtures.java')
    )
    & $javac --release 21 -encoding UTF-8 -d $mainClasses @mainSources
    if ($LASTEXITCODE -ne 0) { throw "Production compilation failed with exit code $LASTEXITCODE" }
    & $javac --release 21 -encoding UTF-8 -cp $mainClasses -d $testClasses @testSources
    if ($LASTEXITCODE -ne 0) { throw "Focused check compilation failed with exit code $LASTEXITCODE" }
    $testNames = @(
        'com.mscient.intrapaper.evaluation.portfolio.PortfolioTrialEvaluationTest',
        'com.mscient.intrapaper.simulation.portfolio.PortfolioReplayEngineTest',
        'com.mscient.intrapaper.simulation.portfolio.PortfolioExecutionCostScheduleTest',
        'com.mscient.intrapaper.simulation.portfolio.PortfolioReplayArtifactVerifierTest'
    )
    foreach ($testName in $testNames) {
        & $java -cp "$mainClasses;$testClasses" $testName $projectRoot
        if ($LASTEXITCODE -ne 0) { throw "$testName failed with exit code $LASTEXITCODE" }
    }
} finally {
    $resolvedRunRoot = [System.IO.Path]::GetFullPath($runRoot)
    if (-not $resolvedRunRoot.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to remove focused-check output outside the evaluation test directory.'
    }
    $item = Get-Item -LiteralPath $resolvedRunRoot -Force -ErrorAction SilentlyContinue
    if ($item -and ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Refusing to remove a focused-check output directory that is a reparse point.'
    }
    if ($item) { Remove-Item -LiteralPath $resolvedRunRoot -Recurse -Force }
}
