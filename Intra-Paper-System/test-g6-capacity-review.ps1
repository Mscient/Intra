param(
    [string]$JarPath,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build\g6-checks')
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
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java') -Recurse -File -Filter '*.java' | ForEach-Object FullName)
    & (Join-Path $PSScriptRoot 'compile-java21.ps1') -Javac $javac -Destination $classes -Sources $sources
    if ($LASTEXITCODE -ne 0) { throw 'Main compilation failed.' }
    $classpath = $classes
} else { $classpath = (Resolve-Path -LiteralPath $JarPath).Path }
$testSource = Join-Path $PSScriptRoot 'src\test\java\com\mscient\intrapaper\review\g6\G6ReviewTest.java'
& $javac --release 21 -encoding UTF-8 -cp $classpath -d $tests $testSource
if ($LASTEXITCODE -ne 0) { throw 'G6 test compilation failed.' }
& $java -ea -cp ($classpath + [IO.Path]::PathSeparator + $tests) com.mscient.intrapaper.review.g6.G6ReviewTest (Join-Path $PSScriptRoot 'examples\daily-ops-evidence\clean-completion') (Join-Path $runRoot 'synthetic') 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw "G6 checks failed: $runRoot" }
& (Join-Path $PSScriptRoot 'test-g6-schemas.ps1') -Dossier (Join-Path $runRoot 'synthetic/six') -ReportDirectories @((Join-Path $runRoot 'synthetic/published'))
Write-Output "SYNTHETIC G6 SOFTWARE checks retained: $runRoot"
