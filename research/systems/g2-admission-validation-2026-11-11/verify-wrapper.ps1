param(
 [Parameter(Mandatory=$true)][string]$RepoRoot,
 [Parameter(Mandatory=$true)][string]$JarPath,
 [Parameter(Mandatory=$true)][string]$FixtureRoot,
 [Parameter(Mandatory=$true)][string]$OutputRoot,
 [Parameter(Mandatory=$true)][string]$ExpectedCommit
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
if(Test-Path -LiteralPath $OutputRoot){throw 'Use a new output directory'}
New-Item -ItemType Directory -Path $OutputRoot | Out-Null
$wrapper=Join-Path $RepoRoot 'Intra-Paper-System/g2-admission.ps1'
$pwsh=Join-Path $PSHOME 'pwsh.exe'
$summary=Join-Path $OutputRoot 'summary.log'
@("TESTED_COMMIT=$ExpectedCommit","JAR_SHA256=$((Get-FileHash $JarPath).Hash.ToLowerInvariant())","POWERSHELL=$($PSVersionTable.PSVersion)") | Set-Content $summary
function Check([string]$Name,[int]$Expected,[string[]]$CliArgs){
 $log=Join-Path $OutputRoot ($Name+'.log')
 & $pwsh -NoProfile -File $wrapper -JarPath $JarPath @CliArgs *> $log
 $actual=$LASTEXITCODE
 "$Name EXPECTED=$Expected ACTUAL=$actual" | Add-Content $summary
 if($actual -ne $Expected){throw "$Name returned $actual instead of $Expected"}
}
$valid=Join-Path $FixtureRoot 'valid-input/admission.json'
$bad=Join-Path $FixtureRoot 'tampered-data/admission.json'
$incomplete=Join-Path $FixtureRoot 'caller-declared/admission.json'
$original=Join-Path $FixtureRoot 'valid-artifact'
Check 'verify-pass' 0 @('verify',$valid)
Check 'verify-fail' 2 @('verify',$bad)
Check 'verify-incomplete' 3 @('verify',$incomplete)
Check 'usage' 64 @('unknown',$valid)
Check 'malformed' 65 @('verify',(Join-Path $FixtureRoot 'invalid-utf8.json'))
Check 'verify-artifact' 0 @('verify-artifact',$valid,$original)
$published=Join-Path $OutputRoot 'artifact with spaces'
Check 'run-pass' 0 @('run',$valid,$published)
Check 'overwrite-rejected' 65 @('run',$valid,$published)
foreach($name in @('report.json','artifact.json')){
 if((Get-FileHash (Join-Path $published $name)).Hash -cne (Get-FileHash (Join-Path $original $name)).Hash){throw 'Wrapper output differs'}
}
'WRAPPER_BYTE_COMPARISON=PASS' | Add-Content $summary
$failedOutput=Join-Path $OutputRoot 'failed-report'
Check 'run-fail' 2 @('run',$bad,$failedOutput)
Check 'verify-stored-fail' 0 @('verify-artifact',$bad,$failedOutput)
$incompleteOutput=Join-Path $OutputRoot 'incomplete-report'
Check 'run-incomplete' 3 @('run',$incomplete,$incompleteOutput)
Check 'verify-stored-incomplete' 0 @('verify-artifact',$incomplete,$incompleteOutput)
$tampered=Join-Path $OutputRoot 'tampered'
Copy-Item -LiteralPath $original -Destination $tampered -Recurse
[IO.File]::AppendAllText((Join-Path $tampered 'report.json'),' ')
Check 'tampered-report' 65 @('verify-artifact',$valid,$tampered)
$rehashed=Join-Path $OutputRoot 'rehashed'
Copy-Item -LiteralPath $original -Destination $rehashed -Recurse
$reportPath=Join-Path $rehashed 'report.json'
$report=[IO.File]::ReadAllText($reportPath)
$changed=$report.Replace('"capture_id":"CAPTURE-1"','"capture_id":"FORGED-CAPTURE"')
if($report -ceq $changed){throw 'Rehashed test did not change report'}
[IO.File]::WriteAllText($reportPath,$changed,[Text.UTF8Encoding]::new($false))
$artifactPath=Join-Path $rehashed 'artifact.json'
$artifact=[IO.File]::ReadAllText($artifactPath)
$oldHash=($artifact | ConvertFrom-Json).report_sha256
$newHash=(Get-FileHash $reportPath).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($artifactPath,$artifact.Replace($oldHash,$newHash),[Text.UTF8Encoding]::new($false))
Check 'rehashed-report' 65 @('verify-artifact',$valid,$rehashed)
if((Get-Content -Raw (Join-Path $OutputRoot 'rehashed-report.log')) -notmatch 'does not match deterministic recomputation'){throw 'Rehashed artifact did not reach semantic rejection'}
'REHASHED_ARTIFACT_DIGEST_MATCH=PASS; DETERMINISTIC_RECOMPUTATION_REJECTION=PASS' | Add-Content $summary

$fully=Join-Path $OutputRoot 'fully-rehashed'
Copy-Item -LiteralPath $rehashed -Destination $fully -Recurse
$reportPath=Join-Path $fully 'report.json'
$changed=[IO.File]::ReadAllText($reportPath)
$oldId=($changed | ConvertFrom-Json).report_id
$withoutId=[regex]::Replace($changed, ',"report_id":"[0-9a-f]{64}"','')
if($withoutId -ceq $changed){throw 'Report ID removal failed'}
$newId=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($withoutId))).ToLowerInvariant()
[IO.File]::WriteAllText($reportPath,$changed.Replace($oldId,$newId),[Text.UTF8Encoding]::new($false))
$artifactPath=Join-Path $fully 'artifact.json'
$artifact=[IO.File]::ReadAllText($artifactPath)
$oldHash=($artifact | ConvertFrom-Json).report_sha256
$newHash=(Get-FileHash $reportPath).Hash.ToLowerInvariant()
[IO.File]::WriteAllText($artifactPath,$artifact.Replace($oldHash,$newHash).Replace($oldId,$newId),[Text.UTF8Encoding]::new($false))
Check 'fully-rehashed-report' 65 @('verify-artifact',$valid,$fully)
if((Get-Content -Raw (Join-Path $OutputRoot 'fully-rehashed-report.log')) -notmatch 'does not match deterministic recomputation'){throw 'Fully rehashed artifact did not reach recomputation rejection'}
'FULLY_REHASHED_REPORT_ID_AND_OUTER_DIGEST=PASS; RECOMPUTATION_REJECTION=PASS' | Add-Content $summary

$mismatch=Join-Path $OutputRoot 'wrong-commit'
& $pwsh -NoProfile -File (Join-Path $RepoRoot 'Intra-Paper-System/test-g2-admission.ps1') -JarPath $JarPath -ExpectedCommit ('0'*40) -OutputRoot $mismatch *> (Join-Path $OutputRoot 'wrong-commit.log')
if($LASTEXITCODE -eq 0 -or (Get-Content -Raw (Join-Path $OutputRoot 'wrong-commit.log')) -notmatch 'Build-Commit does not match exact expected commit'){throw 'ExpectedCommit mismatch did not reject'}
'EXPECTED_COMMIT_MISMATCH_REJECTION=PASS' | Add-Content $summary
'RESULT=PASS; WRAPPER_CASES=15; BUILD_IDENTITY_NEGATIVE=1; SYNTHETIC_OFFLINE_ONLY; G2_INCOMPLETE' | Add-Content $summary
Get-Content $summary
