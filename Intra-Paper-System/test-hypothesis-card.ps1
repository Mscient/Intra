param(
    [string]$JarPath,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build\hypothesis-card-checks')
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21: $version" }
$runRoot = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $runRoot 'classes'
$tests = Join-Path $runRoot 'tests'
New-Item -ItemType Directory -Force -Path $classes, $tests | Out-Null
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java') -Recurse -File -Filter '*.java' | Sort-Object FullName | ForEach-Object FullName)
    $argFile = Join-Path $runRoot 'main-sources.txt'
    [IO.File]::WriteAllLines($argFile, [string[]]@($sources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), [Text.UTF8Encoding]::new($false))
    & $javac --release 21 -encoding UTF-8 -d $classes ('@' + $argFile)
    if ($LASTEXITCODE -ne 0) { throw 'Main compilation failed.' }
    $classpath = $classes
} else { $classpath = (Resolve-Path -LiteralPath $JarPath).Path }
$testSource = Join-Path $PSScriptRoot 'src\test\java\com\mscient\intrapaper\evaluation\hypothesis\HypothesisCardTest.java'
& $javac --release 21 -encoding UTF-8 -cp $classpath -d $tests $testSource
if ($LASTEXITCODE -ne 0) { throw 'Hypothesis test compilation failed.' }
$fixtures = Join-Path $PSScriptRoot '..\research\systems\g1-foundations-2026-11-08'
$schema = Join-Path $PSScriptRoot 'schemas\hypothesis-card-v1.schema.json'
& $java -ea -cp ($classpath + [IO.Path]::PathSeparator + $tests) com.mscient.intrapaper.evaluation.hypothesis.HypothesisCardTest $fixtures $schema (Join-Path $runRoot 'synthetic') 2>&1 |
    Tee-Object -FilePath (Join-Path $runRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw "Hypothesis checks failed: $runRoot" }
Write-Output "SYNTHETIC SOFTWARE evidence retained: $runRoot"
