param(
    [Parameter(Mandatory = $true)]
    [string]$OutputRoot,
    [string]$ExpectedCommit = $env:GITHUB_SHA
)
$ErrorActionPreference = 'Stop'
# Child PowerShell failures are checked explicitly, including native Java exits.
$PSNativeCommandUseErrorActionPreference = $false
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$projectRoot = Join-Path $repoRoot 'Intra-Paper-System'
$OutputRoot = [System.IO.Path]::GetFullPath($OutputRoot)
$repoPrefix = $repoRoot.TrimEnd('\', '/') + '\'
if ($OutputRoot -eq [System.IO.Path]::GetPathRoot($OutputRoot) -or
    $OutputRoot -eq $repoRoot -or
    $OutputRoot.StartsWith($repoPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputRoot must be a separate directory outside the source checkout, not a filesystem root.'
}
$ancestor = $OutputRoot
while ($ancestor) {
    $item = Get-Item -LiteralPath $ancestor -Force -ErrorAction SilentlyContinue
    if ($item -and ((-not $item.PSIsContainer) -or
        ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0)) {
        throw 'OutputRoot and its existing ancestors must be ordinary directories, not reparse points.'
    }
    $ancestor = [System.IO.Path]::GetDirectoryName($ancestor)
}
if ((Test-Path -LiteralPath $OutputRoot) -and
    @(Get-ChildItem -LiteralPath $OutputRoot -Force).Count -ne 0) {
    throw 'OutputRoot must be absent or empty so retained logs and packages belong only to this run.'
}
$logRoot = Join-Path $OutputRoot 'logs'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
$pwsh = Join-Path $PSHOME 'pwsh.exe'
$suites = @(
    'risk-gateway',
    'risk-gateway-process',
    'portfolio-replay-engine',
    'portfolio-risk-replay',
    'portfolio-accounting',
    'portfolio-risk-audit',
    'portfolio-settlement-audit',
    'portfolio-trial-evaluation',
    'daily-ops-evidence',
    'dataset-intended-use-preflight'
)
$results = [ordered]@{}
foreach ($suite in $suites) { $results[$suite] = 'NOT_RUN' }
$results['production-build'] = 'NOT_RUN'
$results['daily-ops-packaged'] = 'NOT_RUN'
$results['daily-ops-drill'] = 'NOT_RUN'
$results['dataset-intended-use-packaged'] = 'NOT_RUN'
$results['dataset-intended-use-drill'] = 'NOT_RUN'
$results['manifest-check'] = 'NOT_RUN'
$sourceCommit = 'unknown'
$jdkVersion = 'unknown'
$status = 'FAIL'

function Invoke-LoggedScript([string]$Name, [string]$ScriptPath, [string[]]$Arguments = @()) {
    $logPath = Join-Path $logRoot "$Name.log"
    "RUN=$Name SCRIPT=$ScriptPath SOURCE_COMMIT=$sourceCommit" |
        Set-Content -LiteralPath $logPath -Encoding utf8
    Write-Host "RUN $Name ($ScriptPath)"
    & $pwsh -NoProfile -NonInteractive -File $ScriptPath @Arguments *>> $logPath
    $exitCode = $LASTEXITCODE
    $results[$Name] = if ($exitCode -eq 0) { 'PASS' } else { "FAIL (exit $exitCode)" }
    "RESULT=$($results[$Name]) EXIT_CODE=$exitCode" | Add-Content -LiteralPath $logPath
    Get-Content -LiteralPath $logPath | ForEach-Object { Write-Host $_ }
}

try {
    $sourceCommit = (& git -C $repoRoot rev-parse --verify HEAD | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $sourceCommit -notmatch '^[0-9a-f]{40}$') {
        throw 'Cannot identify the checked-out source commit.'
    }
    if ([string]::IsNullOrWhiteSpace($ExpectedCommit)) { $ExpectedCommit = $sourceCommit }
    $environmentLog = Join-Path $logRoot 'environment.log'
    @("SOURCE_COMMIT=$sourceCommit", "EXPECTED_COMMIT=$ExpectedCommit", "JAVA_HOME=$env:JAVA_HOME",
        "POWERSHELL=$($PSVersionTable.PSVersion)", "PLANNED_SUITES=$($suites -join ',')") |
        Set-Content -LiteralPath $environmentLog -Encoding utf8
    if ($sourceCommit -cne $ExpectedCommit) { throw 'Checked-out HEAD differs from the expected workflow revision.' }
    $checkoutStatus = @(& git -C $repoRoot status --porcelain --untracked-files=normal)
    if ($LASTEXITCODE -ne 0 -or $checkoutStatus.Count -ne 0) {
        throw 'CI requires a clean source checkout, including no untracked source files.'
    }
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to Microsoft JDK 21.0.13.0.2.' }
    $release = Get-Content -LiteralPath (Join-Path $env:JAVA_HOME 'release')
    $release | Where-Object { $_ -match '^(IMPLEMENTOR|IMPLEMENTOR_VERSION|JAVA_VERSION|JAVA_RUNTIME_VERSION|OS_ARCH)=' } |
        Add-Content -LiteralPath $environmentLog
    $jdkVersion = ($release | Where-Object { $_ -match '^JAVA_VERSION=' }) -replace '^JAVA_VERSION="|"$', ''
    if ($release -cnotcontains 'IMPLEMENTOR="Microsoft"' -or $jdkVersion -cne '21.0.13.0.2') {
        throw "Expected Microsoft JDK 21.0.13.0.2, found: $jdkVersion"
    }
    $javaVersion = @(& (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1)
    $javaExit = $LASTEXITCODE
    $javaVersion | Add-Content -LiteralPath $environmentLog
    if ($javaExit -ne 0 -or
        -not ($javaVersion -match '^openjdk version "21\.0\.13\.0\.2"') -or
        -not ($javaVersion -match 'OpenJDK Runtime Environment Microsoft-')) {
        throw 'java executable must report Microsoft JDK 21.0.13.0.2.'
    }
    $compilerVersion = (& (Join-Path $env:JAVA_HOME 'bin\javac.exe') -version 2>&1 | Out-String).Trim()
    $compilerExit = $LASTEXITCODE
    $compilerVersion | Add-Content -LiteralPath $environmentLog
    if ($compilerExit -ne 0 -or $compilerVersion -cne 'javac 21.0.13.0.2') {
        throw 'javac executable must report version 21.0.13.0.2.'
    }
    Get-Content -LiteralPath $environmentLog | ForEach-Object { Write-Host $_ }

    # Keep generated fixtures/output local; no market downloads or credentials.
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [System.IO.Path]::PathSeparator + $env:PATH
    $env:LOCALAPPDATA = Join-Path $OutputRoot 'local-app-data'
    $env:TEMP = Join-Path $OutputRoot 'temp'
    $env:TMP = $env:TEMP
    $env:INTRA_PAPER_BUILD_DIR = Join-Path $OutputRoot 'package'
    New-Item -ItemType Directory -Force -Path $env:LOCALAPPDATA, $env:TEMP | Out-Null
    foreach ($suite in $suites) {
        $scriptPath = Join-Path $projectRoot "test-$suite.ps1"
        $runnerArguments = @()
        if ($suite -eq 'risk-gateway') { $runnerArguments = @('-OutputRoot', (Join-Path $OutputRoot 'risk-gateway')) }
        if ($suite -eq 'risk-gateway-process') { $runnerArguments = @('-OutputRoot', (Join-Path $OutputRoot 'risk-gateway-process')) }
        if ($suite -eq 'daily-ops-evidence') { $runnerArguments = @('-OutputRoot', (Join-Path $OutputRoot 'daily-ops-evidence')) }
        if ($suite -eq 'dataset-intended-use-preflight') { $runnerArguments = @('-OutputRoot', (Join-Path $OutputRoot 'dataset-intended-use-preflight')) }
        Invoke-LoggedScript $suite $scriptPath $runnerArguments
    }
    $failedSuites = @($suites | Where-Object { $results[$_] -ne 'PASS' })
    if ($failedSuites.Count -gt 0) { throw "Focused suites failed: $($failedSuites -join ', '). Production build skipped." }
    $checkoutStatus = @(& git -C $repoRoot status --porcelain --untracked-files=normal)
    if ($LASTEXITCODE -ne 0 -or $checkoutStatus.Count -ne 0) {
        throw 'Focused runners changed the source checkout. Production build skipped to avoid unknown-dirty identity.'
    }

    Invoke-LoggedScript 'production-build' (Join-Path $projectRoot 'build.ps1')
    if ($results['production-build'] -ne 'PASS') { throw 'Production JAR build failed.' }
    $jarPath = Join-Path $env:INTRA_PAPER_BUILD_DIR 'intra-paper-system.jar'
    Invoke-LoggedScript 'daily-ops-packaged' (Join-Path $projectRoot 'test-daily-ops-evidence.ps1') @(
        '-JarPath', $jarPath, '-OutputRoot', (Join-Path $OutputRoot 'daily-ops-packaged'))
    if ($results['daily-ops-packaged'] -ne 'PASS') { throw 'Packaged daily operations evidence checks failed.' }
    Invoke-LoggedScript 'daily-ops-drill' (Join-Path $projectRoot 'synthetic-daily-ops-drill.ps1') @(
        '-JarPath', $jarPath, '-OutputRoot', (Join-Path $OutputRoot 'daily-ops-drill'))
    if ($results['daily-ops-drill'] -ne 'PASS') { throw 'Packaged daily operations synthetic drill failed.' }
    Invoke-LoggedScript 'dataset-intended-use-packaged' (Join-Path $projectRoot 'test-dataset-intended-use-preflight.ps1') @(
        '-JarPath', $jarPath, '-OutputRoot', (Join-Path $OutputRoot 'dataset-intended-use-packaged'))
    if ($results['dataset-intended-use-packaged'] -ne 'PASS') { throw 'Packaged dataset intended-use checks failed.' }
    Invoke-LoggedScript 'dataset-intended-use-drill' (Join-Path $projectRoot 'synthetic-dataset-intended-use-drill.ps1') @(
        '-JarPath', $jarPath, '-OutputRoot', (Join-Path $OutputRoot 'dataset-intended-use-drill'))
    if ($results['dataset-intended-use-drill'] -ne 'PASS') { throw 'Packaged dataset intended-use drill failed.' }
    $manifestLog = Join-Path $logRoot 'manifest-check.log'
    $results['manifest-check'] = 'FAIL'
    "EXPECTED_COMMIT=$ExpectedCommit" | Set-Content -LiteralPath $manifestLog -Encoding utf8
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $entry = $zip.GetEntry('META-INF/MANIFEST.MF')
        if ($null -eq $entry) { throw 'Production JAR manifest is missing.' }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try { $manifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
    # Unfold continuation lines according to the JAR manifest format.
    $manifest = $manifest -replace '\r?\n ', ''
    $commitFields = [regex]::Matches($manifest, '(?m)^Build-Commit: ([^\r\n]+)\r?$')
    if ($commitFields.Count -ne 1) { throw 'Expected exactly one Build-Commit in the production JAR manifest.' }
    $buildCommit = $commitFields[0].Groups[1].Value
    "BUILD_COMMIT=$buildCommit" | Add-Content -LiteralPath $manifestLog
    if ($buildCommit -cne $sourceCommit -or $buildCommit -cne $ExpectedCommit) {
        throw "JAR Build-Commit '$buildCommit' differs from checked-out revision '$ExpectedCommit'."
    }
    "JAR_SHA256=$((Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant())" |
        Add-Content -LiteralPath $manifestLog
    $results['manifest-check'] = 'PASS'
    'RESULT=PASS' | Add-Content -LiteralPath $manifestLog
    Get-Content -LiteralPath $manifestLog | ForEach-Object { Write-Host $_ }
    $status = 'PASS'
} catch {
    "ERROR=$_" | Set-Content -LiteralPath (Join-Path $logRoot 'failure.log') -Encoding utf8
    throw
} finally {
    $summary = @("VALIDATION_STATUS=$status", "SOURCE_COMMIT=$sourceCommit", "JDK_VERSION=$jdkVersion")
    $summary += @($results.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" })
    $summary | Set-Content -LiteralPath (Join-Path $logRoot 'summary.log') -Encoding utf8
    $summary | ForEach-Object { Write-Host $_ }
    if ($env:GITHUB_STEP_SUMMARY) {
        $markdown = @("Windows offline validation: **$status**", '', "Source commit: $sourceCommit",
            "Microsoft JDK: $jdkVersion", '', '| Suite / check | Result |', '| --- | --- |')
        $markdown += @($results.GetEnumerator() | ForEach-Object { "| $($_.Key) | $($_.Value) |" })
        $markdown | Add-Content -LiteralPath $env:GITHUB_STEP_SUMMARY -Encoding utf8
    }
}
