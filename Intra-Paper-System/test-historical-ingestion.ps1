param([string]$JarPath, [string]$OutputRoot = (Join-Path $PSScriptRoot 'build/historical-ingestion'))
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 21.' }
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 21(?:\.|$)') { throw 'Expected JDK 21.' }
$run = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $run 'classes'
$tests = Join-Path $run 'tests'
New-Item -ItemType Directory -Force -Path $classes,$tests | Out-Null
if (-not $JarPath) {
    $sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src/main/java') -Recurse -Filter '*.java' -File | ForEach-Object FullName)
    & (Join-Path $PSScriptRoot 'compile-java21.ps1') -Javac $javac -Destination $classes -Sources $sources
    if ($LASTEXITCODE -ne 0) { throw 'Main compilation failed.' }
} else { $classes = (Resolve-Path -LiteralPath $JarPath).Path }
& $javac --release 21 -encoding UTF-8 -cp $classes -d $tests (Join-Path $PSScriptRoot 'src/test/java/com/mscient/intrapaper/ingestion/HistoricalIngestionTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed.' }
& $java -ea -Xmx768m -cp ($classes + [IO.Path]::PathSeparator + $tests) com.mscient.intrapaper.ingestion.HistoricalIngestionTest (Join-Path $PSScriptRoot 'examples/historical-ingestion') (Join-Path $run 'fixtures') $classes 2>&1 | Tee-Object -FilePath (Join-Path $run 'checks.log')
if ($LASTEXITCODE -ne 0) { throw "Historical ingestion checks failed: $run" }
Write-Output "Historical ingestion evidence: $run"
