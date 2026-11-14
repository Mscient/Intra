param(
    [string]$JarPath,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build\dataset-permission-checks')
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
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    # The checker compiles independently of the research simulator and other audit tools.
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java\com\mscient\intrapaper\permission\preflight') -Filter '*.java' -File | ForEach-Object FullName)
    $sources += Join-Path $PSScriptRoot 'src\main\java\com\mscient\intrapaper\data\StrictJsonParser.java'
    & $javac --release 21 -encoding UTF-8 -d $classes @sources
    if ($LASTEXITCODE -ne 0) { throw "Checker compilation failed: $LASTEXITCODE" }
    $classpath = $classes
} else {
    if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'JAR is missing.' }
    $classpath = [System.IO.Path]::GetFullPath($JarPath)
}
$test = Join-Path $PSScriptRoot 'src\test\java\com\mscient\intrapaper\permission\preflight\PermissionPreflightTest.java'
& $javac --release 21 -encoding UTF-8 -cp $classpath -d $testClasses $test
if ($LASTEXITCODE -ne 0) { throw "Acceptance compilation failed: $LASTEXITCODE" }
& $java -ea -cp ($classpath + [System.IO.Path]::PathSeparator + $testClasses) `
    com.mscient.intrapaper.permission.preflight.PermissionPreflightTest $PSScriptRoot (Join-Path $runRoot 'synthetic-evidence') 2>&1 |
    Tee-Object -FilePath (Join-Path $runRoot 'acceptance.log')
if ($LASTEXITCODE -ne 0) { throw "Dataset permission acceptance failed: $LASTEXITCODE" }
Write-Output "Synthetic acceptance evidence retained at $runRoot"
