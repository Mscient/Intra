param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(Mandatory = $true)][string]$OutputRoot
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$version = (& $java -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version "21(?:\.|\")') { throw 'Expected Java 21.' }
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'Build the project first; JAR missing.' }
$fixturesRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot 'examples\daily-ops-evidence'))
$reportsRoot = [System.IO.Path]::GetFullPath($OutputRoot)
$fixturePrefix = $fixturesRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
if ($reportsRoot -eq $fixturesRoot -or $reportsRoot.StartsWith($fixturePrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Drill reports must be outside the synthetic evidence fixtures.'
}
New-Item -ItemType Directory -Force -Path $reportsRoot | Out-Null
$cases = Get-Content -LiteralPath (Join-Path $fixturesRoot 'expected-results.json') -Raw | ConvertFrom-Json
$failed = $false
foreach ($case in $cases) {
    $bundlePath = Join-Path $fixturesRoot ($case.fixture + '\session-evidence.json')
    $reportPath = Join-Path $reportsRoot ($case.fixture + '.json')
    & $java -cp $JarPath com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceCli check $bundlePath $reportPath
    $commandExit = $LASTEXITCODE
    if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) { throw "Missing drill report: $($case.fixture)" }
    $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    $expectedExit = switch ($case.status) { 'PASS' { 0 } 'FAIL' { 1 } 'INCOMPLETE' { 2 } default { throw 'Invalid fixture expectation.' } }
    if ($report.status -cne $case.status -or $commandExit -ne $expectedExit) {
        Write-Output "UNEXPECTED $($case.fixture): expected=$($case.status) actual=$($report.status) exit=$commandExit"
        $failed = $true
    }
}
Write-Output 'Synthetic evidence rehearsal only: caller-supplied diagnostic evidence; G4 remains open.'
if ($failed) { exit 1 }
exit 0
