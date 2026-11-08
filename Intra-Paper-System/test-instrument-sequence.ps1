$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdkHome = $env:JAVA_HOME
$javac = if ([string]::IsNullOrWhiteSpace($jdkHome)) { $null } else { Join-Path $jdkHome 'bin\javac.exe' }
$java = if ([string]::IsNullOrWhiteSpace($jdkHome)) { $null } else { Join-Path $jdkHome 'bin\java.exe' }
if (-not $javac -or -not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) {
    throw 'Set JAVA_HOME to a JDK 21 installation before running instrument-sequence checks.'
}
$compilerVersion = (& $javac -version 2>&1 | Out-String).Trim()
if ($compilerVersion -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $compilerVersion" }

$testRoot = Join-Path $env:LOCALAPPDATA 'Intra-Paper-System\instrument-sequence-tests'
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$testRootItem = Get-Item -LiteralPath $testRoot -Force
if (($testRootItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Refusing to use an instrument-sequence test directory that is a reparse point.'
}
$testRootAbsolute = [System.IO.Path]::GetFullPath((Resolve-Path -LiteralPath $testRoot).Path)
$runRootAbsolute = [System.IO.Path]::GetFullPath((Join-Path $testRootAbsolute ([guid]::NewGuid().ToString('N'))))
$rootPrefix = $testRootAbsolute.TrimEnd([System.IO.Path]::DirectorySeparatorChar,
    [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if (-not $runRootAbsolute.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Temporary focused-check output must stay below the instrument-sequence test directory.'
}
$mainClasses = Join-Path $runRootAbsolute 'classes'
$testClasses = Join-Path $runRootAbsolute 'test-classes'
New-Item -ItemType Directory -Force -Path $mainClasses, $testClasses | Out-Null
try {
    $mainSources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Recurse -File -Filter '*.java' |
        ForEach-Object { $_.FullName })
    $testSources = @(
        (Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\marketdata\InstrumentSequenceAnalyzerTest.java'),
        (Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\marketdata\InstrumentFeedCoverageAnalyzerTest.java')
    )
    if ($mainSources.Count -eq 0 -or @($testSources | Where-Object { -not (Test-Path -LiteralPath $_) }).Count -gt 0) {
        throw 'Main sources and focused instrument sequence/coverage tests are required.'
    }
    & (Join-Path $PSScriptRoot 'compile-java21.ps1') -Javac $javac -Destination $mainClasses -Sources $mainSources
    if ($LASTEXITCODE -ne 0) { throw "Main compilation failed with exit code $LASTEXITCODE" }
    & $javac --release 21 -encoding UTF-8 -cp $mainClasses -d $testClasses $testSources
    if ($LASTEXITCODE -ne 0) { throw "Focused test compilation failed with exit code $LASTEXITCODE" }
    & $java -cp "$mainClasses;$testClasses" com.mscient.intrapaper.marketdata.InstrumentSequenceAnalyzerTest
    if ($LASTEXITCODE -ne 0) { throw "Instrument-sequence checks failed with exit code $LASTEXITCODE" }
    & $java -cp "$mainClasses;$testClasses" com.mscient.intrapaper.marketdata.InstrumentFeedCoverageAnalyzerTest
    if ($LASTEXITCODE -ne 0) { throw "Instrument-feed-coverage checks failed with exit code $LASTEXITCODE" }
} finally {
    if (-not $runRootAbsolute.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Refusing to remove focused-check output outside the instrument-sequence test directory.'
    }
    $item = Get-Item -LiteralPath $runRootAbsolute -Force -ErrorAction SilentlyContinue
    if ($item -and ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Refusing to remove a focused-check output directory that is a reparse point.'
    }
    if ($item) { Remove-Item -LiteralPath $runRootAbsolute -Recurse -Force }
}
