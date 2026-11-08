param([string]$JarPath,[Parameter(Mandatory=$true)][string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$release = Get-Content -LiteralPath (Join-Path $env:JAVA_HOME 'release')
if ($release -cnotcontains 'IMPLEMENTOR="Microsoft"' -or $release -cnotcontains 'JAVA_VERSION="21.0.13.0.2"') { throw 'Use pinned Microsoft JDK 21.0.13.0.2.' }
$run = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$tests = Join-Path $run 'tests'
New-Item -ItemType Directory -Force -Path $tests | Out-Null
if (-not $JarPath) { $JarPath = & (Join-Path $PSScriptRoot 'build-g5-readiness.ps1') -OutputRoot (Join-Path $run 'package') }
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
& $javac --release 21 -encoding UTF-8 -cp $JarPath -d $tests (Join-Path $PSScriptRoot 'src/test/java/com/mscient/intrapaper/g5/G5AcceptanceTest.java') (Join-Path $PSScriptRoot 'src/test/java/com/mscient/intrapaper/g0/G0CheckerTest.java')
if ($LASTEXITCODE -ne 0) { throw 'G5 test compilation failed.' }
$evidence = Join-Path $run 'evidence'
& $java -ea -cp ($JarPath + [IO.Path]::PathSeparator + $tests) com.mscient.intrapaper.g5.G5AcceptanceTest $evidence 2>&1 |
    Tee-Object -FilePath (Join-Path $run 'acceptance.log')
if ($LASTEXITCODE -ne 0) { throw 'G5 acceptance failed.' }
$head = (Get-Content -LiteralPath (Join-Path $evidence 'jvm-head.txt') -Raw).Trim()
$hashes = @()
foreach ($mode in @(@('en','US','UTC'),@('tr','TR','Pacific/Apia'),@('ja','JP','Asia/Kolkata'))) {
    $report = Join-Path $run ('report-' + $mode[0] + '.json')
    & $java ('-Duser.language=' + $mode[0]) ('-Duser.country=' + $mode[1]) ('-Duser.timezone=' + $mode[2]) -cp $JarPath com.mscient.intrapaper.g5.G5Cli check `
        (Join-Path $evidence 'jvm-repeat') (Join-Path $evidence 'jvm-private.json') '2026-11-08T12:00:00Z' $head report $report
    if ($LASTEXITCODE -ne 2) { throw 'Synthetic dossier must remain BLOCKED.' }
    $hash = (Get-FileHash -LiteralPath $report -Algorithm SHA256).Hash.ToLowerInvariant()
    $hashes += $hash
    & $java -cp $JarPath com.mscient.intrapaper.g5.G5Cli verify (Join-Path $evidence 'jvm-repeat') (Join-Path $evidence 'jvm-private.json') '2026-11-08T12:00:00Z' $head $report $hash
    if ($LASTEXITCODE -ne 0) { throw 'G5 exact verification failed.' }
}
if (@($hashes | Select-Object -Unique).Count -ne 1) { throw 'G5 cross-JVM report bytes differ.' }
$hashes | Set-Content -LiteralPath (Join-Path $run 'jvm-hashes.txt')
# NTFS junctions require no symlink privilege and must also fail the Java ancestry check.
$junction = Join-Path $run 'escape-junction'
New-Item -ItemType Junction -Path $junction -Target $evidence | Out-Null
& $java -cp $JarPath com.mscient.intrapaper.g5.G5Cli explain (Join-Path $junction 'jvm-repeat') (Join-Path $evidence 'jvm-private.json') '2026-11-08T12:00:00Z' $head
if ($LASTEXITCODE -ne 3) { throw 'G5 junction escape was accepted.' }
# Inspect every production class, including the reused verifier closure.
& (Join-Path $env:JAVA_HOME 'bin/jdeps.exe') -verbose:class $JarPath |
    Set-Content -LiteralPath (Join-Path $run 'dependencies.txt')
if ($LASTEXITCODE -ne 0) { throw 'G5 dependency analysis failed.' }
$dependencies = Get-Content -LiteralPath (Join-Path $run 'dependencies.txt') -Raw
if ($dependencies -match 'java.net.http|java.net.Socket|java.net.ServerSocket|java.net.Datagram|java.net.URL|java.net.HttpURLConnection|java.net.InetAddress|javax.net|java.nio.channels.SocketChannel|java.nio.channels.ServerSocketChannel|java.nio.channels.DatagramChannel|java.lang.ProcessBuilder') {
    throw 'Production JAR contains a forbidden network/process dependency.'
}
"JAR_SHA256=$((Get-FileHash -LiteralPath $JarPath -Algorithm SHA256).Hash.ToLowerInvariant())" |
    Set-Content -LiteralPath (Join-Path $run 'package-sha256.txt')
Write-Output "G5_CROSS_JVM=3_RUNS_3_VERIFICATIONS_IDENTICAL G5_JUNCTION_REJECTED=true G5_PRODUCTION_NETWORK_DEPENDENCIES=NONE EVIDENCE=$run"
