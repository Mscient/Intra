param([string]$RepoRoot = (Join-Path $PSScriptRoot '../../..'))
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$RepoRoot = [IO.Path]::GetFullPath($RepoRoot)
$expectedCommit = '3c774d2f2971c1ebe39d361e250057e9e872b971'
$evidenceRoot = Join-Path $RepoRoot 'research/systems/g2-admission-validation-2026-11-11'
$parentRoot = Split-Path $RepoRoot -Parent
$originalRoot = Join-Path $parentRoot 'g2-admission-ci-3c774d2'
$resumedRoot = Join-Path $parentRoot 'g2-admission-resumed-3c774d2-20261111'
$sourceRoot = Join-Path $originalRoot 'g2-admission/336a38be2938467698e3913f61c9a5c8'
$packagedRoot = Join-Path $resumedRoot 'packaged/557f7325ec3d46a3b1f273f9eca069b9'
$records = [Collections.Generic.List[string]]::new()
function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function EqualBytes([string]$Left, [string]$Right) { Require ((Hash $Left) -ceq (Hash $Right)) "Bytes differ: $Left / $Right" }
$records.Add('REVIEW_DATE=2026-11-13')
$records.Add("TESTED_BUILD_COMMIT=$expectedCommit")
$records.Add("REVIEW_POWERSHELL=$($PSVersionTable.PSVersion); RETAINED_TEST_POWERSHELL=7.6.9")
$count = 0
foreach ($line in [IO.File]::ReadAllLines((Join-Path $evidenceRoot 'SHA256SUMS'))) {
    Require ($line -cmatch '^([0-9a-f]{64})  (.+)$') 'Malformed checksum row'
    $expectedHash = $Matches[1]; $relativePath = $Matches[2]
    Require ((Hash (Join-Path $evidenceRoot $relativePath)) -ceq $expectedHash) "Checksum mismatch: $relativePath"
    $count++
}
Require ($count -eq 85) 'Unexpected retained evidence inventory'
$records.Add("ORIGINAL_EVIDENCE_SHA256SUMS=PASS FILES=$count")
$crlf = 0; $lf = 0
foreach ($file in Get-ChildItem -LiteralPath $evidenceRoot -Recurse -File) {
    $content = [IO.File]::ReadAllText($file.FullName)
    Require ($content -notmatch '[ \t]+(?:\r?\n|$)') "Actual trailing space/tab: $($file.FullName)"
    $crlf += [regex]::Matches($content, '\r\n').Count
    $lf += [regex]::Matches($content, '(?<!\r)\n').Count
}
$records.Add("WHITESPACE_DIAGNOSIS=CRLF_ATTRIBUTE_HANDLING CRLF_LINES=$crlf LF_LINES=$lf ACTUAL_TRAILING_SPACES_OR_TABS=0")
$stages = Get-Content -Raw (Join-Path $evidenceRoot 'stages.json') | ConvertFrom-Json
Require ($stages.Count -eq 38) 'Expected 38 stages'
Require (@($stages.stage | Select-Object -Unique).Count -eq 38) 'Duplicate stages'
foreach ($stage in $stages) {
    Require ($stage.result -ceq 'PASS') "Non-PASS stage: $($stage.stage)"
    $retainedPath = Join-Path $evidenceRoot $stage.evidence
    $content = [IO.File]::ReadAllText($retainedPath)
    if ($stage.stage -eq 'manifest-check') {
        Require ($content.Contains("BUILD_COMMIT=$expectedCommit") -and $content.Contains('RESULT=PASS')) 'Manifest stage lacks exact build/pass'
    } else {
        Require ($content.Contains("SOURCE_COMMIT=$expectedCommit") -and $content.Contains('RESULT=PASS EXIT_CODE=0')) "Missing exact-build/pass: $($stage.stage)"
    }
    $originalPath = if ($stage.mode -ceq 'RETAINED_COMPLETED') { Join-Path $originalRoot ('logs/' + $stage.stage + '.log') } else { Join-Path $resumedRoot ($stage.stage + '.log') }
    EqualBytes $retainedPath $originalPath
}
Require (@($stages | Where-Object mode -CEQ 'RETAINED_COMPLETED').Count -eq 36) 'Expected 36 retained stages'
Require (@($stages | Where-Object mode -CEQ 'RESUMED').Count -eq 2) 'Expected two resumed stages'
$environment = [IO.File]::ReadAllText((Join-Path $evidenceRoot 'original-logs/environment.log'))
$planned = [regex]::Match($environment, '(?m)^PLANNED_SUITES=([^\r\n]+)').Groups[1].Value.Split(',')
Require ($planned.Count -eq 21) 'Expected 21 source suites'
foreach ($suite in $planned) { Require ($stages.stage -ccontains $suite) "Missing source suite: $suite" }
$records.Add('MATRIX=PASS 38_STAGES; SOURCE_SUITES=21; OTHER_STAGES=17; RETAINED=36; RESUMED=2; UNINTERRUPTED_FULL_RERUN=false')
Require (-not (Test-Path -LiteralPath (Join-Path $originalRoot 'logs/summary.log'))) 'Original summary unexpectedly present'
Require (-not (Test-Path -LiteralPath (Join-Path $originalRoot 'logs/failure.log'))) 'Original failure record unexpectedly present'
EqualBytes (Join-Path $evidenceRoot 'original-logs/g2-admission-packaged.log') (Join-Path $originalRoot 'logs/g2-admission-packaged.log')
Require ([IO.File]::ReadAllLines((Join-Path $originalRoot 'logs/g2-admission-packaged.log')).Count -eq 1) 'Original partial packaged log changed'
$records.Add('INTERRUPTED_ORIGINAL=RUN_ONLY; NO_SUMMARY; NO_RETAINED_EXIT; PRESERVED')
foreach ($mode in @(@('source-runner', $sourceRoot), @('packaged-runner', $packagedRoot))) {
    foreach ($file in Get-ChildItem -LiteralPath (Join-Path $evidenceRoot $mode[0]) -File) { EqualBytes $file.FullName (Join-Path $mode[1] $file.Name) }
    Require ([IO.File]::ReadAllText((Join-Path $mode[1] 'checks.log')).Contains('66 checks PASS')) 'Admission assertions absent'
    Require ([IO.File]::ReadAllLines((Join-Path $mode[1] 'schema-checks.log')).Count -eq 6) 'Expected six schema cases per mode'
    Require ([IO.File]::ReadAllText((Join-Path $mode[1] 'runner-summary.log')).Contains('INDEPENDENT_JVM_BYTE_COMPARISON=PASS; JUNCTION_REJECTION=PASS')) 'Runner completion absent'
}
foreach ($name in @('report.json','artifact.json')) {
    $source = Join-Path $sourceRoot ('fixtures/valid-artifact/' + $name)
    EqualBytes $source (Join-Path $packagedRoot ('fixtures/valid-artifact/' + $name))
    EqualBytes $source (Join-Path $evidenceRoot ('positive-artifact/' + $name))
    $records.Add("SOURCE_PACKAGED_BYTES=$name PASS SHA256=$(Hash $source)")
}
$jar = Join-Path $originalRoot 'package/intra-paper-system.jar'
$jarHash = Hash $jar
Require ($jarHash -ceq '5d094cb8d787d4b3fbaa8b055c98ddeb3b1e9abe806e53a6af7bae1b197672c7') 'JAR hash changed'
$zip = [IO.Compression.ZipFile]::OpenRead($jar)
try {
    $reader = [IO.StreamReader]::new($zip.GetEntry('META-INF/MANIFEST.MF').Open())
    try { $manifest = $reader.ReadToEnd() -replace '\r?\n ', '' } finally { $reader.Dispose() }
} finally { $zip.Dispose() }
$fields = [regex]::Matches($manifest, '(?m)^Build-Commit: ([^\r\n]+)\r?$')
Require ($fields.Count -eq 1 -and $fields[0].Groups[1].Value -ceq $expectedCommit) 'JAR Build-Commit mismatch'
Require ($manifest.Contains('Created-By: 21.0.13.0.2 (Microsoft)')) 'JAR compiler identity mismatch'
$records.Add("ACTUAL_JAR_BUILD_COMMIT=$expectedCommit; CREATED_BY=21.0.13.0.2_Microsoft; SHA256=$jarHash")
foreach ($line in [IO.File]::ReadAllLines((Join-Path $evidenceRoot 'build-and-output-hashes.log'))) {
    if ($line -cmatch '^([0-9a-f]{64})  (.+)$') {
        $expectedHash = $Matches[1]; $path = $Matches[2]
        Require ((Hash $path) -ceq $expectedHash) "Recorded JAR/JDK bytes changed: $path"
        $records.Add("RECORDED_BUILD_FILE=PASS SHA256=$expectedHash PATH=$path")
    }
}
$release = [IO.File]::ReadAllText('C:/Program Files/Microsoft/jdk-21.0.13.21-hotspot/release')
Require ($release.Contains('JAVA_RUNTIME_VERSION="21.0.13.0.2+1-LTS"') -and $release.Contains('IMPLEMENTOR_VERSION="Microsoft-15780113"')) 'Installed JDK identity mismatch'
$records.Add('ACTUAL_JDK=Microsoft-15780113; RUNTIME=21.0.13.0.2+1-LTS; x86_64')
$records.Add('SOURCE_AND_PACKAGED_ADMISSION=66_ASSERTIONS_EACH; SCHEMAS=6_CASES_EACH; RETAINED_COMPLETED_RUNS_VERIFIED')
$records.Add('HOSTED_CI=NO_RECORDED_EXACT_COMMIT_RUN; HISTORICAL_ZERO_RUN_QUERY_ONLY; NO_NEW_QUERY_OR_DISPATCH')
$records.Add('ORIGIN_MAIN=6c398f1e8d090a527f413d713dc7d294e411c548; FRESHNESS_UNVERIFIED; NO_FETCH')
$records.Add('G2=INCOMPLETE; NO_REAL_DATA_ACCESSED; NO_NEW_TEST_RUN_OR_PACKAGE_BUILD')
$records.Add('RESULT=PASS_RETAINED_EVIDENCE_VERIFIED')
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'verification.log'), ($records -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
$records
