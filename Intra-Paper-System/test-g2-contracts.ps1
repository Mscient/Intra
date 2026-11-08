param([string]$JarPath, [string]$OutputRoot = (Join-Path $PSScriptRoot 'build/g2-contracts'))
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 21.' }
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21: $version" }
$runRoot = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $runRoot 'classes'
$tests = Join-Path $runRoot 'tests'
New-Item -ItemType Directory -Force -Path $classes, $tests | Out-Null
if (-not $JarPath) {
    $sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src/main/java') -Recurse -File -Filter '*.java' | ForEach-Object FullName)
    & (Join-Path $PSScriptRoot 'compile-java21.ps1') -Javac $javac -Destination $classes -Sources $sources
    if ($LASTEXITCODE -ne 0) { throw 'G2 main compilation failed.' }
} else { $classes = (Resolve-Path -LiteralPath $JarPath).Path }
& $javac --release 21 -encoding UTF-8 -cp $classes -d $tests (Join-Path $PSScriptRoot 'src/test/java/com/mscient/intrapaper/simulation/G2ContractTest.java')
if ($LASTEXITCODE -ne 0) { throw 'G2 test compilation failed.' }
& $java -ea -Xmx768m -cp ($classes + [IO.Path]::PathSeparator + $tests) com.mscient.intrapaper.simulation.G2ContractTest `
    (Join-Path $runRoot 'fixtures') (Join-Path $PSScriptRoot 'examples/g2-offline/golden.raw') 2>&1 |
    Tee-Object -FilePath (Join-Path $runRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw "G2 contracts failed: $runRoot" }
Write-Output "G2 evidence retained: $runRoot"
