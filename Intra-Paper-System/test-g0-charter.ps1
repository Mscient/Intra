param([string]$JarPath,[Parameter(Mandatory=$true)][string]$OutputRoot,[string]$ExampleOutput)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$release = Get-Content -LiteralPath (Join-Path $env:JAVA_HOME 'release')
if ($release -cnotcontains 'IMPLEMENTOR="Microsoft"' -or $release -cnotcontains 'JAVA_VERSION="21.0.13.0.2"') { throw 'Use pinned Microsoft JDK 21.0.13.0.2.' }
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
if ((& $javac -version 2>&1 | Out-String).Trim() -cne 'javac 21.0.13.0.2') { throw 'Compiler identity mismatch.' }
$run = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$tests = Join-Path $run 'tests'
New-Item -ItemType Directory -Force -Path $tests | Out-Null
$release | Set-Content -LiteralPath (Join-Path $run 'jdk-identity.txt')
if (-not $JarPath) { $JarPath = & (Join-Path $PSScriptRoot 'build-g0-charter.ps1') -OutputRoot (Join-Path $run 'package') }
$JarPath = (Resolve-Path -LiteralPath $JarPath).Path
& $javac --release 21 -encoding UTF-8 -cp $JarPath -d $tests (Join-Path $PSScriptRoot 'src/test/java/com/mscient/intrapaper/g0/G0CheckerTest.java')
if ($LASTEXITCODE -ne 0) { throw 'G0 test compilation failed.' }
$classpath = $JarPath + [IO.Path]::PathSeparator + $tests
if ($ExampleOutput) {
    & $java -ea -cp $classpath com.mscient.intrapaper.g0.G0CheckerTest --example $ExampleOutput
    if ($LASTEXITCODE -ne 0) { throw 'Example failed.' }
    exit 0
}
$evidence = Join-Path $run 'evidence'
& $java -ea -cp $classpath com.mscient.intrapaper.g0.G0CheckerTest $evidence 2>&1 | Tee-Object -FilePath (Join-Path $run 'checks.log')
if ($LASTEXITCODE -ne 0) { throw 'G0 focused checks failed.' }
$fixture = Join-Path $evidence 'jvm-repeat'
$hashes = @()
foreach ($mode in @(@('en','US','UTC'), @('tr','TR','Pacific/Apia'), @('ja','JP','Asia/Kolkata'))) {
    $out = Join-Path $run ('jvm-' + $mode[0])
    & $java ('-Duser.language=' + $mode[0]) ('-Duser.country=' + $mode[1]) ('-Duser.timezone=' + $mode[2]) -cp $JarPath com.mscient.intrapaper.g0.G0Checker run $fixture $out
    if ($LASTEXITCODE -ne 0) { throw 'G0 cross-JVM run failed.' }
    $report = Join-Path $out 'report.tsv'
    $hash = (Get-FileHash -LiteralPath $report -Algorithm SHA256).Hash.ToLowerInvariant()
    $hashes += $hash
    & $java -cp $JarPath com.mscient.intrapaper.g0.G0Checker verify $fixture $report $hash
    if ($LASTEXITCODE -ne 0) { throw 'G0 cross-JVM verify failed.' }
}
if (@($hashes | Select-Object -Unique).Count -ne 1) { throw 'Cross-JVM report bytes differ.' }
$hashes | Set-Content -LiteralPath (Join-Path $run 'jvm-hashes.txt')
$packageHash = (Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash.ToLowerInvariant()
"JAR_SHA256=$packageHash" | Set-Content -LiteralPath (Join-Path $run 'package-sha256.txt')
& (Join-Path $env:JAVA_HOME 'bin/jdeps.exe') -verbose:class -include 'com.mscient.intrapaper.g0.*' $JarPath | Set-Content -LiteralPath (Join-Path $run 'dependencies.txt')
if ($LASTEXITCODE -ne 0) { throw 'G0 dependency inspection failed.' }
$dependencyText = Get-Content -LiteralPath (Join-Path $run 'dependencies.txt')
if ($dependencyText -match 'java.net.http|java.net.Socket|java.net.URLConnection|com.mscient.intrapaper.(simulation|strategy|riskgateway|cli|permission)') { throw 'Unexpected G0 dependency.' }
Write-Output "G0_CROSS_JVM=3_RUNS_3_VERIFICATIONS_IDENTICAL G0_EVIDENCE=$run"
