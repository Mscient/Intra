param(
    [string]$JarPath,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build\data-permission-coverage-checks'),
    [switch]$RunRelevantChecks
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
if ($RunRelevantChecks -and -not [string]::IsNullOrWhiteSpace($JarPath)) { throw 'Run relevant checks without -JarPath to compile the full project.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 21(?:\.|$)') { throw 'JDK 21 is required.' }
$runRoot = Join-Path ([System.IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $runRoot 'classes'
$testClasses = Join-Path $runRoot 'test-classes'
New-Item -ItemType Directory -Force -Path $classes, $testClasses | Out-Null
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    # javac argument files avoid Windows command-line limits on long isolated worktree paths.
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java') -Recurse -File -Filter '*.java' |
        ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
    $sourceList = Join-Path $runRoot 'main-sources.args'
    [System.IO.File]::WriteAllLines($sourceList, $sources, [System.Text.UTF8Encoding]::new($false))
    & $javac --release 21 -encoding UTF-8 -d $classes ("@" + $sourceList)
    if ($LASTEXITCODE -ne 0) { throw 'Main compilation failed.' }
    $classpath = $classes
} else {
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'JAR missing.' }
    $classpath = [System.IO.Path]::GetFullPath($JarPath)
}
$test = Join-Path $PSScriptRoot 'src\test\java\com\mscient\intrapaper\permission\coverage\PermissionCoverageTest.java'
& $javac --release 21 -encoding UTF-8 -cp $classpath -d $testClasses $test
if ($LASTEXITCODE -ne 0) { throw 'Focused test compilation failed.' }
& $java -ea -cp ($classpath + [System.IO.Path]::PathSeparator + $testClasses) `
    com.mscient.intrapaper.permission.coverage.PermissionCoverageTest `
    (Join-Path $PSScriptRoot 'examples\data-permission-coverage') `
    (Join-Path $PSScriptRoot 'schemas\data-permission-coverage') (Join-Path $runRoot 'cases') 2>&1 |
    Tee-Object -FilePath (Join-Path $runRoot 'focused-checks.log')
if ($LASTEXITCODE -ne 0) { throw 'Permission coverage checks failed.' }
Write-Output "Focused checks and synthetic reports retained at $runRoot"
if ($RunRelevantChecks) {
    $testSources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\test\java') -Recurse -File -Filter '*.java' |
        ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
    $testList = Join-Path $runRoot 'test-sources.args'
    [System.IO.File]::WriteAllLines($testList, $testSources, [System.Text.UTF8Encoding]::new($false))
    & $javac --release 21 -encoding UTF-8 -cp $classes -d $testClasses ("@" + $testList)
    if ($LASTEXITCODE -ne 0) { throw 'Existing regression compilation failed.' }
    $package = Join-Path $runRoot 'intra-paper-system.jar'
    & (Join-Path $env:JAVA_HOME 'bin\jar.exe') --create --file $package --main-class com.mscient.intrapaper.cli.IntraPaperCli -C $classes .
    if ($LASTEXITCODE -ne 0) { throw 'Full project packaging failed.' }
    $tempRoot = Join-Path $runRoot 'regression-temp'
    New-Item -ItemType Directory -Path $tempRoot | Out-Null
    $testNames = @(
        'com.mscient.intrapaper.data.SynchronizedBarFrameBuilderTest',
        'com.mscient.intrapaper.simulation.portfolio.PortfolioReplayArtifactVerifierTest',
        'com.mscient.intrapaper.audit.portfolio.PortfolioRiskAuditTest',
        'com.mscient.intrapaper.audit.portfolio.PortfolioSettlementAuditTest'
    )
    foreach ($testName in $testNames) {
        & $java "-Djava.io.tmpdir=$tempRoot" -ea -cp ($classes + [System.IO.Path]::PathSeparator + $testClasses) $testName $PSScriptRoot 2>&1 |
            Tee-Object -FilePath (Join-Path $runRoot ($testName.Split('.')[-1] + '.log'))
        if ($LASTEXITCODE -ne 0) { throw "Existing regression failed: $testName" }
    }
    & $java "-Djava.io.tmpdir=$tempRoot" -ea -cp ($classes + [System.IO.Path]::PathSeparator + $testClasses) `
        com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceCheckerTest `
        (Join-Path $PSScriptRoot 'examples\daily-ops-evidence') (Join-Path $runRoot 'daily-ops') 2>&1 |
        Tee-Object -FilePath (Join-Path $runRoot 'DailyOpsEvidenceCheckerTest.log')
    if ($LASTEXITCODE -ne 0) { throw 'Existing daily-ops schema/artifact regression failed.' }
    Write-Output "Full project JAR and existing artifact/schema regressions passed: $package"
}
