param(
    [string]$JarPath,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build\daily-ops-evidence-checks'),
    [switch]$RunRelevantChecks,
    [switch]$BuildPackage
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $version" }
$runRoot = Join-Path ([System.IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $runRoot 'classes'
$testClasses = Join-Path $runRoot 'test-classes'
New-Item -ItemType Directory -Force -Path $classes, $testClasses | Out-Null
$test = Join-Path $PSScriptRoot 'src\test\java\com\mscient\intrapaper\ops\evidence\DailyOpsEvidenceCheckerTest.java'
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java') -Recurse -File -Filter '*.java' | ForEach-Object FullName)
    & (Join-Path $PSScriptRoot 'compile-java21.ps1') -Javac $javac -Destination $classes -Sources $sources
    if ($LASTEXITCODE -ne 0) { throw "Main compilation failed: $LASTEXITCODE" }
    $classpath = $classes
} else {
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'JAR missing.' }
    $classpath = [System.IO.Path]::GetFullPath($JarPath)
}
& $javac --release 21 -encoding UTF-8 -cp $classpath -d $testClasses $test
if ($LASTEXITCODE -ne 0) { throw "Evidence test compilation failed: $LASTEXITCODE" }
& $java -ea -cp ($classpath + [System.IO.Path]::PathSeparator + $testClasses) `
    com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceCheckerTest `
    (Join-Path $PSScriptRoot 'examples\daily-ops-evidence') (Join-Path $runRoot 'synthetic-evidence') 2>&1 |
    Tee-Object -FilePath (Join-Path $runRoot 'focused-checks.log')
if ($LASTEXITCODE -ne 0) { throw "Evidence checks failed: $LASTEXITCODE" }
Write-Output "Synthetic checks and byte-identical reports retained at $runRoot"

if ($RunRelevantChecks -or $BuildPackage) {
    $priorLocal = $env:LOCALAPPDATA
    $priorTemp = $env:TEMP
    $priorTmp = $env:TMP
    $priorBuild = $env:INTRA_PAPER_BUILD_DIR
    try {
        $env:LOCALAPPDATA = Join-Path $runRoot 'local-app-data'
        $env:TEMP = Join-Path $runRoot 'temp'
        $env:TMP = $env:TEMP
        $env:INTRA_PAPER_BUILD_DIR = Join-Path $runRoot 'package'
        New-Item -ItemType Directory -Force -Path $env:LOCALAPPDATA, $env:TEMP | Out-Null
        $suites = @('risk-gateway', 'risk-gateway-process', 'operational-kill-controller', 'instrument-sequence',
            'portfolio-risk-replay', 'portfolio-accounting', 'portfolio-risk-audit', 'portfolio-settlement-audit', 'portfolio-trial-evaluation')
        $logRoot = Join-Path $runRoot 'relevant-check-logs'
        New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
        $pwsh = Join-Path $PSHOME 'pwsh.exe'
        if ($RunRelevantChecks) { foreach ($suite in $suites) {
            Write-Output "Running existing Java 21 checks: $suite"
            $logPath = Join-Path $logRoot ($suite + '.log')
            & $pwsh -NoProfile -NonInteractive -File (Join-Path $PSScriptRoot ('test-' + $suite + '.ps1')) *>> $logPath
            $suiteExit = $LASTEXITCODE
            Get-Content -LiteralPath $logPath -Tail 10
            if ($suiteExit -ne 0) { throw "Existing $suite checks failed: $suiteExit; log=$logPath" }
        } }
        & $pwsh -NoProfile -NonInteractive -File (Join-Path $PSScriptRoot 'build.ps1')
        if ($LASTEXITCODE -ne 0) { throw 'Production build failed.' }
        & $pwsh -NoProfile -NonInteractive -File $PSCommandPath -JarPath (Join-Path $env:INTRA_PAPER_BUILD_DIR 'intra-paper-system.jar') -OutputRoot (Join-Path $runRoot 'packaged-evidence')
        if ($LASTEXITCODE -ne 0) { throw 'Packaged evidence checks failed.' }
        & $pwsh -NoProfile -NonInteractive -File (Join-Path $PSScriptRoot 'synthetic-daily-ops-drill.ps1') `
            -JarPath (Join-Path $env:INTRA_PAPER_BUILD_DIR 'intra-paper-system.jar') -OutputRoot (Join-Path $runRoot 'drill-reports')
        if ($LASTEXITCODE -ne 0) { throw 'Synthetic drill matrix failed.' }
        Write-Output "Requested checks, packaged evidence checks, and drill passed; logs retained at $runRoot"
    } finally {
        $env:LOCALAPPDATA = $priorLocal
        $env:TEMP = $priorTemp
        $env:TMP = $priorTmp
        $env:INTRA_PAPER_BUILD_DIR = $priorBuild
    }
}
