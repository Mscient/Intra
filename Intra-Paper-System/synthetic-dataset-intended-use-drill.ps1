param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build\dataset-intended-use-drill')
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$fixture = Join-Path $PSScriptRoot 'examples\dataset-permission-preflight'
$runRoot = Join-Path ([System.IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
$cases = @(
    @{ Name = 'valid'; Record = 'permission-evidence.json'; Date = '2026-11-07'; Expected = 0 },
    @{ Name = 'missing-reviewer'; Record = 'missing-reviewer.permission.json'; Date = '2026-11-07'; Expected = 3 },
    @{ Name = 'contradictory'; Record = 'contradictory.permission.json'; Date = '2026-11-07'; Expected = 2 },
    @{ Name = 'expired'; Record = 'permission-evidence.json'; Date = '2026-11-09'; Expected = 2 }
)
foreach ($case in $cases) {
    $report = Join-Path $runRoot ($case.Name + '.json')
    & (Join-Path $PSScriptRoot 'dataset-intended-use-preflight.ps1') -JarPath $JarPath check `
        (Join-Path $fixture 'manifest.json') (Join-Path $fixture 'intended-use.json') `
        (Join-Path $fixture $case.Record) $case.Date $report
    if ($LASTEXITCODE -ne $case.Expected) { throw "Unexpected result for $($case.Name): $LASTEXITCODE" }
    & (Join-Path $PSScriptRoot 'dataset-intended-use-preflight.ps1') -JarPath $JarPath verify `
        (Join-Path $fixture 'manifest.json') (Join-Path $fixture 'intended-use.json') `
        (Join-Path $fixture $case.Record) $case.Date $report
    if ($LASTEXITCODE -ne $case.Expected) { throw "Reproduction failed for $($case.Name): $LASTEXITCODE" }
}
foreach ($mode in @('check-dataset', 'verify-dataset')) {
    & (Join-Path $PSScriptRoot 'dataset-intended-use-preflight.ps1') -JarPath $JarPath $mode `
        (Join-Path $fixture 'manifest.json') (Join-Path $fixture 'intended-use.json') `
        (Join-Path $fixture 'permission-evidence.json') '2026-11-07' (Join-Path $runRoot 'dataset.json') `
        (Join-Path $fixture 'synthetic.csv')
    if ($LASTEXITCODE -ne 0) { throw "Synthetic dataset verification failed: $LASTEXITCODE" }
}
Write-Output "PASS 10 synthetic CLI checks; reports retained at $runRoot; G2 remains open"
