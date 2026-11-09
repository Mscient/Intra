param(
    [string]$JarPath,
    [string]$ExpectedCommit,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build/g2-admission')
)
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
$testSource = Join-Path $PSScriptRoot 'src/test/java/com/mscient/intrapaper/admission/G2AdmissionTest.java'
$environmentLog = Join-Path $runRoot 'environment.log'
@("SOURCE_COMMIT=$((& git -C $PSScriptRoot rev-parse HEAD | Out-String).Trim())", "JAVA_HOME=$env:JAVA_HOME", "COMPILER=$version") | Set-Content $environmentLog
Get-Content (Join-Path $env:JAVA_HOME 'release') | Add-Content $environmentLog
& $java -version 2>&1 | Add-Content $environmentLog
if (-not $JarPath) {
    $sources = @(Get-ChildItem (Join-Path $PSScriptRoot 'src/main/java') -Recurse -File -Filter '*.java' | ForEach-Object FullName)
    & (Join-Path $PSScriptRoot 'compile-java21.ps1') -Javac $javac -Destination $classes -Sources $sources
    if ($LASTEXITCODE -ne 0) { throw 'G2 admission main compilation failed.' }
} else {
    $classes = (Resolve-Path -LiteralPath $JarPath).Path
    if (-not $ExpectedCommit) { $ExpectedCommit = (& git -C $PSScriptRoot rev-parse HEAD | Out-String).Trim() }
    $zip = [IO.Compression.ZipFile]::OpenRead($classes)
    try {
        $entry = $zip.GetEntry('META-INF/MANIFEST.MF')
        if (-not $entry) { throw 'Missing JAR manifest' }
        $reader = [IO.StreamReader]::new($entry.Open())
        try { $manifest = $reader.ReadToEnd() -replace '\r?\n ', '' } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
    $matches = [regex]::Matches($manifest, '(?m)^Build-Commit: ([^\r\n]+)\r?$')
    if ($ExpectedCommit -notmatch '^[0-9a-f]{40}$' -or $matches.Count -ne 1 -or $matches[0].Groups[1].Value -cne $ExpectedCommit) {
        throw 'Production JAR Build-Commit does not match exact expected commit.'
    }
    @("BUILD_COMMIT=$ExpectedCommit", "JAR_SHA256=$((Get-FileHash -LiteralPath $classes -Algorithm SHA256).Hash.ToLowerInvariant())") | Add-Content $environmentLog
}
& $javac --release 21 -encoding UTF-8 -cp $classes -d $tests $testSource
if ($LASTEXITCODE -ne 0) { throw 'G2 admission test compilation failed.' }
$feedExample = Join-Path $PSScriptRoot 'examples/feed-audit/inputs/complete'
& $java -ea -Xmx768m -cp ($classes + [IO.Path]::PathSeparator + $tests) com.mscient.intrapaper.admission.G2AdmissionTest `
    (Join-Path $runRoot 'fixtures') $feedExample 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'checks.log')
if ($LASTEXITCODE -ne 0) { throw "G2 admission checks failed: $runRoot" }
$fixture = Join-Path $runRoot 'fixtures/valid-input/admission.json'
$localeOutput = Join-Path $runRoot 'locale-artifact'
& $java '-Duser.timezone=Pacific/Apia' '-Duser.language=tr' '-Duser.country=TR' -cp $classes com.mscient.intrapaper.admission.G2AdmissionCli run $fixture $localeOutput 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'locale.log')
if ($LASTEXITCODE -ne 0) { throw 'Independent JVM admission failed.' }
foreach ($name in @('report.json', 'artifact.json')) {
    if ((Get-FileHash (Join-Path $localeOutput $name)).Hash -cne (Get-FileHash (Join-Path $runRoot "fixtures/valid-artifact/$name")).Hash) { throw 'Cross-JVM artifact bytes differ.' }
}
$link = Join-Path $runRoot 'linked-input'
New-Item -ItemType Junction -Path $link -Target (Join-Path $runRoot 'fixtures/valid-input') | Out-Null
& $java -cp $classes com.mscient.intrapaper.admission.G2AdmissionCli verify (Join-Path $link 'admission.json') 2>&1 | Tee-Object -FilePath (Join-Path $runRoot 'junction.log')
if ($LASTEXITCODE -ne 65) { throw 'Junction input did not fail closed.' }
'INDEPENDENT_JVM_BYTE_COMPARISON=PASS; JUNCTION_REJECTION=PASS' | Tee-Object -FilePath (Join-Path $runRoot 'runner-summary.log')
Write-Output "G2 admission evidence retained: $runRoot"
