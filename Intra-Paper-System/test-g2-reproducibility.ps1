param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build/g2-reproducibility')
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$JarPath = (Resolve-Path -LiteralPath $JarPath).Path
$runRoot = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$tests = Join-Path $runRoot 'tests'
New-Item -ItemType Directory -Force -Path $tests | Out-Null
& $javac --release 21 -encoding UTF-8 -cp $JarPath -d $tests (Join-Path $PSScriptRoot 'src/test/java/com/mscient/intrapaper/simulation/G2FrozenFixture.java')
if ($LASTEXITCODE -ne 0) { throw 'Frozen fixture compilation failed.' }
$classPath = $JarPath + [IO.Path]::PathSeparator + $tests
$log = Join-Path $runRoot 'checks.log'
$utf8 = [Text.UTF8Encoding]::new($false)
function Invoke-CheckedJava([string[]]$Arguments) {
    & $java @Arguments *>> $log
    if ($LASTEXITCODE -ne 0) { throw "G2 child JVM failed (exit $LASTEXITCODE): $($Arguments -join ' '); $log" }
}
Invoke-CheckedJava @('-cp', $classPath, 'com.mscient.intrapaper.simulation.G2FrozenFixture', 'freeze',
    (Join-Path $PSScriptRoot 'examples'), (Join-Path $runRoot 'frozen'))
$inputs = @(Get-ChildItem (Join-Path $runRoot 'frozen') -File | Sort-Object Name | ForEach-Object {
    "$($_.Name),$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())"
})
[IO.File]::WriteAllLines((Join-Path $runRoot 'input-sha256.csv'), @('file,sha256') + $inputs, $utf8)
$oldRuns = $env:INTRA_PAPER_RUNS_DIR
$oldTemp = $env:TEMP
$oldTmp = $env:TMP
try {
    foreach ($environment in @('utc-en', 'apia-tr')) {
        $envRoot = Join-Path $runRoot $environment
        Copy-Item -LiteralPath (Join-Path $runRoot 'frozen') -Destination $envRoot -Recurse
        $env:TEMP = Join-Path $envRoot 'temp'; $env:TMP = $env:TEMP
        New-Item -ItemType Directory -Path $env:TEMP | Out-Null
        $jvm = if ($environment -eq 'utc-en') { @('-Duser.timezone=UTC', '-Duser.language=en', '-Duser.country=US') }
               else { @('-Duser.timezone=Pacific/Apia', '-Duser.language=tr', '-Duser.country=TR') }
        Push-Location $envRoot
        try {
            Invoke-CheckedJava ($jvm + @('-jar', $JarPath, 'raw-capture-verify', 'capture.raw'))
            Invoke-CheckedJava ($jvm + @('-jar', $JarPath, 'feed-lifecycle-report', 'capture.flj'))
            Invoke-CheckedJava ($jvm + @('-cp', $classPath, 'com.mscient.intrapaper.simulation.G2FrozenFixture', 'decode', 'capture.raw', 'quotes.qjr'))
            Invoke-CheckedJava ($jvm + @('-jar', $JarPath, 'journal-verify', 'quotes.qjr'))
            foreach ($model in @('bar', 'L1_FULL_SIZE_IOC', 'L1_PARTIAL_IOC')) {
                $env:INTRA_PAPER_RUNS_DIR = Join-Path $envRoot ('runs-' + $model)
                Invoke-CheckedJava ($jvm + @('-jar', $JarPath, 'run', 'synthetic-futures-bars.csv', ($model + '.properties')))
                $run = @(Get-ChildItem -LiteralPath $env:INTRA_PAPER_RUNS_DIR -Directory | Where-Object Name -Match '^[0-9a-f]{24}$')
                if ($run.Count -ne 1) { throw 'Expected exactly one complete run per model/environment.' }
                Invoke-CheckedJava ($jvm + @('-jar', $JarPath, 'replay-verify', 'synthetic-futures-bars.csv', ($model + '.properties'), $run[0].FullName))
                Invoke-CheckedJava ($jvm + @('-jar', $JarPath, 'oms-recover', $run[0].FullName))
                $bars = @(Import-Csv -LiteralPath 'synthetic-futures-bars.csv')
                $calendar = @(Import-Csv -LiteralPath 'synthetic-session-calendar.csv')[0]
                $expected = [int](([DateTimeOffset]::Parse($calendar.last_bar_timestamp) -
                    [DateTimeOffset]::Parse($calendar.first_bar_timestamp)).TotalMinutes + 1)
                $unique = @($bars.timestamp | Sort-Object -Unique)
                if ($bars.Count -ne $expected -or $unique.Count -ne $expected) { throw 'Synthetic fixture coverage differs.' }
                $quality = [ordered]@{
                    schema_version = 'g2-synthetic-quality/v1'; classification = 'SYNTHETIC_OFFLINE_ONLY'
                    g2_status = 'INCOMPLETE'; quality_grade = 'SYNTHETIC_CONFIGURED_CHECKS_PASS'
                    authorization_status = 'SYNTHETIC_AUTHORING_ONLY'; source_authenticity = 'NOT_APPLICABLE_SYNTHETIC'
                    input_sha256 = $inputs; source = 'SYNTHETIC_FIXTURE'; instrument_id = $bars[0].instrument_id
                    session_date = $calendar.session_date; calendar_file = 'synthetic-session-calendar.csv'
                    rule_regime_id = $calendar.market_rule_regime_id; timezone = 'Asia/Kolkata'; interval = 'ONE_MINUTE'
                    timestamp_roles = @('BAR_START', 'SYNTHETIC_EXCHANGE_UTC', 'SYNTHETIC_RECEIVE_UTC', 'SYNTHETIC_MONOTONIC_SAME_EPOCH')
                    expected_first_bar = $calendar.first_bar_timestamp; expected_last_bar = $calendar.last_bar_timestamp
                    expected_bars = $expected; observed_bars = $bars.Count; missing_intervals = 0; duplicate_intervals = 0
                    out_of_order_intervals = 0; malformed_events = 0; stale_events = 0
                    exceptions = @(); unknowns = @('real source rights/authenticity/coverage', 'vendor clock/sequence semantics',
                        'real tariffs/margin', 'queue/impact/latency calibration', 'independent reconciliation/release')
                    execution_model = $model; run_id = $run[0].Name
                    run_manifest_sha256 = (Get-FileHash -LiteralPath (Join-Path $run[0].FullName 'run-manifest.json') -Algorithm SHA256).Hash.ToLowerInvariant()
                    normalized_journal_sha256 = (Get-FileHash -LiteralPath 'quotes.qjr' -Algorithm SHA256).Hash.ToLowerInvariant()
                }
                [IO.File]::WriteAllText((Join-Path $envRoot ('quality-' + $model + '.json')),
                    (($quality | ConvertTo-Json -Depth 6).Replace("`r`n", "`n") + "`n"), $utf8)
            }
        } finally { Pop-Location }
        $after = @(Get-ChildItem (Join-Path $runRoot 'frozen') -File | Sort-Object Name | ForEach-Object {
            "$($_.Name),$((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant())"
        })
        if (($after -join "`n") -cne ($inputs -join "`n")) { throw 'Frozen source bytes changed.' }
    }
    $rows = @('model,file,sha256,byte_compare')
    $compared = 0
    foreach ($model in @('bar', 'L1_FULL_SIZE_IOC', 'L1_PARTIAL_IOC')) {
        $a = @(Get-ChildItem (Join-Path $runRoot ('utc-en/runs-' + $model)) -Directory | Where-Object Name -Match '^[0-9a-f]{24}$')[0]
        $b = @(Get-ChildItem (Join-Path $runRoot ('apia-tr/runs-' + $model)) -Directory | Where-Object Name -Match '^[0-9a-f]{24}$')[0]
        if ($a.Name -cne $b.Name) { throw 'Run identities differ across child environments.' }
        $qualityA = Join-Path $runRoot ('utc-en/quality-' + $model + '.json')
        $qualityB = Join-Path $runRoot ('apia-tr/quality-' + $model + '.json')
        if ([IO.File]::ReadAllText($qualityA) -cne [IO.File]::ReadAllText($qualityB)) { throw 'Synthetic quality records differ.' }
        $rows += "$model,quality.json,$((Get-FileHash -LiteralPath $qualityA -Algorithm SHA256).Hash.ToLowerInvariant()),true"
        foreach ($file in Get-ChildItem -LiteralPath $a.FullName -File | Sort-Object Name) {
            $other = Join-Path $b.FullName $file.Name
            if ([Convert]::ToBase64String([IO.File]::ReadAllBytes($file.FullName)) -cne
                [Convert]::ToBase64String([IO.File]::ReadAllBytes($other))) { throw "Byte mismatch: $model/$($file.Name)" }
            $rows += "$model,$($file.Name),$((Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()),true"
            $compared++
        }
    }
    if ([Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $runRoot 'utc-en/quotes.qjr'))) -cne
        [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $runRoot 'apia-tr/quotes.qjr')))) { throw 'Normalized journals differ.' }
    [IO.File]::WriteAllLines((Join-Path $runRoot 'artifact-sha256.csv'), $rows, $utf8)
    "PASS: $compared complete artifact files plus normalized journal byte-identical; separate JVMs, UTC/en-US and Pacific/Apia/tr-TR; SYNTHETIC_OFFLINE_ONLY" |
        Tee-Object -FilePath (Join-Path $runRoot 'result.txt')
} finally {
    $env:INTRA_PAPER_RUNS_DIR = $oldRuns; $env:TEMP = $oldTemp; $env:TMP = $oldTmp
}
Write-Output "G2 process evidence retained: $runRoot"
