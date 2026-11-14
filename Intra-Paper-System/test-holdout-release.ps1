$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$taskJdkHome = $env:JAVA_HOME
$javac = if ([string]::IsNullOrWhiteSpace($taskJdkHome)) { $null } else { Join-Path $taskJdkHome 'bin\javac.exe' }
$java = if ([string]::IsNullOrWhiteSpace($taskJdkHome)) { $null } else { Join-Path $taskJdkHome 'bin\java.exe' }
if (-not $javac -or -not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) {
    throw 'Set JAVA_HOME to a JDK 21 installation before running holdout release checks.'
}
$compilerVersion = (& $javac -version 2>&1 | Out-String).Trim()
if ($compilerVersion -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $compilerVersion" }
$testRoot = [System.IO.Path]::GetFullPath((Join-Path $projectRoot 'build\holdout-release-tests'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$testRootItem = Get-Item -LiteralPath $testRoot -Force
if (($testRootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Refusing a holdout test directory that is a reparse point.'
}
$rootPrefix = $testRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
$runRoot = [System.IO.Path]::GetFullPath((Join-Path $testRoot ([guid]::NewGuid().ToString('N'))))
if (-not $runRoot.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Holdout test output must remain within the test directory.'
}
$mainClasses = Join-Path $runRoot 'classes'
$testClasses = Join-Path $runRoot 'test-classes'
$fixtures = Join-Path $runRoot 'fixtures'
New-Item -ItemType Directory -Force -Path $mainClasses, $testClasses, $fixtures | Out-Null
try {
    $sourceRoot = Join-Path $projectRoot 'src\main\java'
    $entrySource = Join-Path $sourceRoot 'com\mscient\intrapaper\evaluation\portfolio\PortfolioHoldoutReleaseController.java'
    $testSource = Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\evaluation\portfolio\PortfolioHoldoutReleaseTest.java'
    # Compile the controller and its production dependency closure; the regular evaluator suite compiles all sources.
    & $javac --release 21 -encoding UTF-8 -sourcepath $sourceRoot -d $mainClasses $entrySource
    if ($LASTEXITCODE -ne 0) { throw "Holdout production dependency compilation failed with exit code $LASTEXITCODE" }
    & $javac --release 21 -encoding UTF-8 -cp $mainClasses -d $testClasses $testSource
    if ($LASTEXITCODE -ne 0) { throw "Holdout test compilation failed with exit code $LASTEXITCODE" }
    & $java -cp "$mainClasses;$testClasses" com.mscient.intrapaper.evaluation.portfolio.PortfolioHoldoutReleaseTest $fixtures
    if ($LASTEXITCODE -ne 0) { throw "Holdout release checks failed with exit code $LASTEXITCODE" }
} finally {
    $resolvedRunRoot = [System.IO.Path]::GetFullPath($runRoot)
    if (-not $resolvedRunRoot.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to remove test output outside the holdout test directory.'
    }
    $item = Get-Item -LiteralPath $resolvedRunRoot -Force -ErrorAction SilentlyContinue
    if ($item -and ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Refusing to remove test output that is a reparse point.'
    }
    if ($item) { Remove-Item -LiteralPath $resolvedRunRoot -Recurse -Force }
}
