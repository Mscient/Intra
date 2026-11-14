param([string]$JarPath, [string]$JdkHome, [string]$OutputRoot = (Join-Path $PSScriptRoot 'build\incident-drill-checks'))
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($JdkHome)) { $JdkHome = Split-Path (Split-Path (Get-Command javac -ErrorAction Stop).Source) }
$java = Join-Path $JdkHome 'bin\java.exe'
$javac = Join-Path $JdkHome 'bin\javac.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $version" }
$runRoot = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$tests = Join-Path $runRoot 'test-classes'
New-Item -ItemType Directory -Force -Path $tests | Out-Null
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    & (Join-Path $PSScriptRoot 'build-incident-drill.ps1') -JdkHome $JdkHome -OutputRoot (Join-Path $runRoot 'package')
    $packages = @(Get-ChildItem -LiteralPath (Join-Path $runRoot 'package') -Recurse -Filter 'incident-drill.jar')
    if ($packages.Count -ne 1) { throw 'Expected exactly one built drill package' }
    $JarPath = $packages[0].FullName
}
$JarPath = [IO.Path]::GetFullPath($JarPath)
$source = Join-Path $PSScriptRoot 'src\test\java\com\mscient\intrapaper\ops\drill\IncidentDrillTest.java'
& $javac --release 21 -encoding UTF-8 -cp $JarPath -d $tests $source
if ($LASTEXITCODE -ne 0) { throw "Acceptance compilation failed: $LASTEXITCODE" }
$fixtures = Join-Path $PSScriptRoot 'examples\incident-drills'
& $java -ea -Xmx512m -cp ($JarPath + [IO.Path]::PathSeparator + $tests) com.mscient.intrapaper.ops.drill.IncidentDrillTest $fixtures (Join-Path $runRoot 'evidence') 2>&1 |
    Tee-Object -FilePath (Join-Path $runRoot 'acceptance.log')
if ($LASTEXITCODE -ne 0) { throw "Acceptance failed: $LASTEXITCODE" }
# Validate the transitive class dependencies of the drill entrypoint, independently of source claims.
$allDependencies = @(& (Join-Path $JdkHome 'bin\jdeps.exe') -verbose:class -filter:none $JarPath 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'Dependency inspection failed' }
# jdeps -include filters away dependencies outside that package even with --recursive.
# Build the actual reachable closure from all packaged class edges instead.
$edges = @{}
foreach ($line in $allDependencies) {
    if ($line -match '^\s+(\S+)\s+->\s+(\S+)\s+') {
        $from = $Matches[1]
        $to = $Matches[2]
        if (-not $edges.ContainsKey($from)) { $edges[$from] = @() }
        $edges[$from] += [pscustomobject]@{ Target=$to; Line=[string]$line }
    }
}
$reachable = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$pending = [Collections.Generic.Queue[string]]::new()
foreach ($name in $edges.Keys) { if ($name.StartsWith('com.mscient.intrapaper.ops.drill.')) { $pending.Enqueue($name) } }
while ($pending.Count -gt 0) {
    $name = $pending.Dequeue()
    if (-not $reachable.Add($name)) { continue }
    foreach ($edge in $edges[$name]) { $pending.Enqueue($edge.Target) }
}
$dependencies = @($edges.Keys | Where-Object { $reachable.Contains($_) } | Sort-Object | ForEach-Object { $edges[$_].Line })
if ($dependencies.Count -eq 0 -or -not $reachable.Contains('com.mscient.intrapaper.riskgateway.RiskGateway')) { throw 'Incomplete dependency graph' }
$dependencies | Set-Content -LiteralPath (Join-Path $runRoot 'dependencies.txt')
$forbidden = @($dependencies | Select-String -Pattern ' -> (java\.net\.(?!URI\b)|java\.net\.http\.|javax\.net\.|com\.mscient\.intrapaper\.cli\.|.*SmartApi|.*Broker)')
if ($forbidden.Count -ne 0) { throw ('Forbidden broker/network dependency: ' + ($forbidden -join "`n")) }
# Separate JVM runs must emit identical content-addressed bundles and preserve non-PASS exit codes.
$matrix = @{'feed-loss'=0; 'stale-marks'=0; 'uncertain-order'=0; 'limit-breach'=0; 'corrupted-checkpoint'=0; 'restart'=0;
    'missed-ack-incomplete'=3; 'lagging-checkpoint-incomplete'=3; 'expectation-fail'=2}
$matrixLog = Join-Path $runRoot 'cli-matrix.txt'
foreach ($name in ($matrix.Keys | Sort-Object)) {
    $ids = @()
    foreach ($repeat in 1..2) {
        $root = Join-Path $runRoot ("cli-$name-$repeat")
        $transcript = @(& $java -cp $JarPath com.mscient.intrapaper.ops.drill.IncidentDrillCli run (Join-Path $fixtures ($name + '.drill')) $root 2>&1)
        if ($LASTEXITCODE -ne $matrix[$name]) { throw "Unexpected CLI outcome for $name : $transcript" }
        $bundle = @(Get-ChildItem -LiteralPath $root -Directory | Where-Object Name -Match '^[0-9a-f]{64}$')
        if ($bundle.Count -ne 1) { throw "Expected one published bundle for $name" }
        $ids += $bundle[0].Name
        & $java -cp $JarPath com.mscient.intrapaper.ops.drill.IncidentDrillCli verify $bundle[0].FullName $bundle[0].Name >> $matrixLog
        if ($LASTEXITCODE -ne $matrix[$name]) { throw "Verification outcome changed for $name" }
    }
    if ($ids[0] -ne $ids[1]) { throw "Cross-process nondeterminism: $name" }
    "$name exit=$($matrix[$name]) manifest_sha256=$($ids[0])" | Tee-Object -FilePath $matrixLog -Append
}
Get-FileHash -Algorithm SHA256 -LiteralPath $JarPath, $source, $PSCommandPath | Format-List | Out-File (Join-Path $runRoot 'tested-sha256.txt')
Write-Output "Offline incident drill checks and evidence retained at $runRoot"
