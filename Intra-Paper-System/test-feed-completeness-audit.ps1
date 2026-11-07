param(
    [string]$JarPath,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build\feed-audit-checks'),
    [switch]$Drill,
    [string]$ExamplesOutput
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
$utf8 = [Text.UTF8Encoding]::new($false)
Get-Content -LiteralPath (Join-Path $env:JAVA_HOME 'release') | Set-Content -LiteralPath (Join-Path $runRoot 'jdk-identity.txt')
$sourceRoot = Join-Path $PSScriptRoot 'src\main\java'
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $sourceFiles = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter '*.java' | Sort-Object FullName | ForEach-Object FullName)
    $argFile = Join-Path $runRoot 'main-sources.txt'
    [IO.File]::WriteAllLines($argFile, [string[]]@($sourceFiles | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), $utf8)
    & $javac --release 21 -encoding UTF-8 -d $classes ('@' + $argFile)
    if ($LASTEXITCODE -ne 0) { throw 'Main compilation failed.' }
    $classpath = $classes
} else { $classpath = (Resolve-Path -LiteralPath $JarPath).Path }
$testSource = Join-Path $PSScriptRoot 'src\test\java\com\mscient\intrapaper\audit\feed\FeedAuditTest.java'
& $javac --release 21 -encoding UTF-8 -cp $classpath -d $tests $testSource
if ($LASTEXITCODE -ne 0) { throw 'Audit test compilation failed.' }
$testArgs = @((Join-Path $runRoot 'evidence'))
if ($Drill) { $testArgs += '--drill' }
if ($ExamplesOutput) { $testArgs = @([IO.Path]::GetFullPath($ExamplesOutput), '--examples') }
& $java -ea -cp ($classpath + [IO.Path]::PathSeparator + $tests) com.mscient.intrapaper.audit.feed.FeedAuditTest @testArgs 2>&1 |
    Tee-Object -FilePath (Join-Path $runRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw "Audit checks failed; evidence: $runRoot" }
Write-Output "Feed audit evidence retained: $runRoot"
