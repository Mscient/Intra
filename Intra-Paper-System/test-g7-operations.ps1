param(
    [string]$JarPath,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build/g7-validation/focused'),
    [string]$ExpectedCommit,
    [switch]$RunRegressionSuites
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to the pinned Microsoft JDK 21.' }
if ($OutputRoot -match '^(\\\\|//)' -or $OutputRoot.Contains('://') -or $OutputRoot -match '(^|[\\/])\.\.([\\/]|$)') { throw 'Use an ordinary local output path.' }
$g7OutputBase = [IO.Path]::GetFullPath($OutputRoot)
$g7Ancestor = $g7OutputBase
while ($g7Ancestor) {
    $g7Item = Get-Item -LiteralPath $g7Ancestor -Force -ErrorAction SilentlyContinue
    if ($g7Item -and (($g7Item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) { throw 'Linked test outputs are forbidden.' }
    $g7Ancestor = [IO.Path]::GetDirectoryName($g7Ancestor)
}
$g7Release = Get-Content -LiteralPath (Join-Path $env:JAVA_HOME 'release')
if ($g7Release -cnotcontains 'IMPLEMENTOR="Microsoft"' -or $g7Release -cnotcontains 'JAVA_VERSION="21.0.13.0.2"') { throw 'Use Microsoft JDK 21.0.13.0.2.' }
$g7Java = Join-Path $env:JAVA_HOME 'bin/java.exe'
$g7Javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
$g7Run = Join-Path $g7OutputBase ([guid]::NewGuid().ToString('N'))
$g7MainClasses = Join-Path $g7Run 'main-classes'
$g7TestClasses = Join-Path $g7Run 'test-classes'
New-Item -ItemType Directory -Path $g7MainClasses,$g7TestClasses | Out-Null
$g7Utf8 = [Text.UTF8Encoding]::new($false)
if ([string]::IsNullOrWhiteSpace($JarPath)) {
    $g7Sources = [string[]]@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java') -Recurse -File -Filter '*.java' | ForEach-Object FullName)
    [Array]::Sort($g7Sources, [StringComparer]::Ordinal)
    $g7Args = Join-Path $g7Run 'main-sources.args'
    [IO.File]::WriteAllLines($g7Args, [string[]]@($g7Sources | ForEach-Object { '"' + $_.Replace('\','/') + '"' }), $g7Utf8)
    & $g7Javac --release 21 -encoding UTF-8 -proc:none -classpath $g7MainClasses -d $g7MainClasses ('@' + $g7Args)
    if ($LASTEXITCODE -ne 0) { throw 'G7 source compilation failed.' }
    # One source-compiled test JAR keeps cross-JVM code identity stable; it is explicitly dirty/unqualified.
    $g7TestManifest = Join-Path $g7Run 'test-manifest.mf'
    $g7CRLF = [string][char]13 + [char]10
    [IO.File]::WriteAllText($g7TestManifest, ('Manifest-Version: 1.0' + $g7CRLF + 'Build-Commit: unknown-dirty' + $g7CRLF + $g7CRLF), $g7Utf8)
    $JarPath = Join-Path $g7Run 'source-test-code.jar'
    & (Join-Path $env:JAVA_HOME 'bin/jar.exe') --create --file $JarPath --manifest $g7TestManifest -C $g7MainClasses .
    if ($LASTEXITCODE -ne 0) { throw 'G7 source-test packaging failed.' }
} else {
    if ($JarPath -match '^(\\\\|//)' -or $JarPath.Contains('://') -or -not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'Local test JAR required.' }
    $JarPath = [IO.Path]::GetFullPath($JarPath)
}
$g7Tests = [string[]]@(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/test/java/com/mscient/intrapaper/ops/governance') -File -Filter '*.java' | ForEach-Object FullName)
[Array]::Sort($g7Tests, [StringComparer]::Ordinal)
$g7TestArgs = Join-Path $g7Run 'test-sources.args'
[IO.File]::WriteAllLines($g7TestArgs, [string[]]@($g7Tests | ForEach-Object { '"' + $_.Replace('\','/') + '"' }), $g7Utf8)
& $g7Javac --release 21 -encoding UTF-8 -proc:none -cp $JarPath -d $g7TestClasses ('@' + $g7TestArgs)
if ($LASTEXITCODE -ne 0) { throw 'G7 focused test compilation failed.' }
$g7Classpath = $JarPath + [IO.Path]::PathSeparator + $g7TestClasses
$g7Arguments = @('-ea','-cp',$g7Classpath,'com.mscient.intrapaper.ops.governance.G7OperationsTest',$PSScriptRoot,$g7Run)
if ($ExpectedCommit) { $g7Arguments += $ExpectedCommit }
& $g7Java @g7Arguments 2>&1 | Tee-Object -FilePath (Join-Path $g7Run 'focused-checks.log')
if ($LASTEXITCODE -ne 0) { throw 'G7 synthetic/adversarial checks failed.' }
& $g7Java -version 2>&1 | Set-Content -LiteralPath (Join-Path $g7Run 'java-version.log') -Encoding utf8
if ($LASTEXITCODE -ne 0) { throw 'Java metadata check failed.' }
# PowerShell's independent JSON Schema validator checks the published contracts offline.
$g7SchemaRoot = Join-Path $PSScriptRoot 'schemas/g7/v1'
$g7Dossiers = Join-Path $PSScriptRoot 'examples/g7-operations/dossiers'
$g7SchemaChecks = 0
foreach ($g7Dossier in Get-ChildItem -LiteralPath $g7Dossiers -Directory) {
    foreach ($g7Contract in @('manifest','bundle','history')) {
        $g7Document = Get-Content -LiteralPath (Join-Path $g7Dossier.FullName ($g7Contract + '.json')) -Raw
        if (-not (Test-Json -Json $g7Document -SchemaFile (Join-Path $g7SchemaRoot ($g7Contract + '.schema.json')))) { throw 'Published contract schema validation failed.' }
        $g7SchemaChecks++
    }
    foreach ($g7Decision in Get-ChildItem -LiteralPath (Join-Path $g7Dossier.FullName 'gates') -Filter '*-decision.json' -File) {
        if (-not (Test-Json -Json (Get-Content -LiteralPath $g7Decision.FullName -Raw) -SchemaFile (Join-Path $g7SchemaRoot 'gate-decision.schema.json'))) { throw 'Published gate declaration schema validation failed.' }
        $g7SchemaChecks++
    }
}
$g7CleanManifest = Get-Content -LiteralPath (Join-Path $g7Dossiers 'clean-looking/manifest.json') -Raw
foreach ($g7Mutation in @('unknown-field','trailing-newline','unsupported-version','missing-null')) {
    $g7Invalid = $g7CleanManifest | ConvertFrom-Json -AsHashtable
    switch ($g7Mutation) {
        'unknown-field' { $g7Invalid['hidden_override'] = $true }
        'trailing-newline' { $g7Invalid['review_id'] = 'safe' + [char]10 }
        'unsupported-version' { $g7Invalid['schema'] = 'g7-manifest/v999' }
        'missing-null' { $g7Invalid['owner'].Remove('identity_evidence') }
    }
    $g7InvalidJson = $g7Invalid | ConvertTo-Json -Depth 100 -Compress
    if (Test-Json -Json $g7InvalidJson -SchemaFile (Join-Path $g7SchemaRoot 'manifest.schema.json') -ErrorAction SilentlyContinue) { throw 'Adversarial schema mutation was accepted.' }
    $g7SchemaChecks++
}
"Independent PowerShell JSON Schema checks passed: $g7SchemaChecks" | Tee-Object -FilePath (Join-Path $g7Run 'schema-checks.log')
if ($RunRegressionSuites) {
    $env:LOCALAPPDATA = Join-Path $g7Run 'regression-app-data'
    $env:TEMP = Join-Path $g7Run 'regression-temp'
    $env:TMP = $env:TEMP
    $env:PATH = (Join-Path $env:JAVA_HOME 'bin') + [IO.Path]::PathSeparator + $env:PATH
    New-Item -ItemType Directory -Path $env:LOCALAPPDATA,$env:TEMP | Out-Null
    $g7Pwsh = Join-Path $PSHOME 'pwsh.exe'
    $g7Suites = @('daily-ops-evidence','feed-completeness-audit','portfolio-replay-engine','portfolio-accounting',
        'portfolio-risk-audit','portfolio-settlement-audit','risk-gateway','risk-gateway-process','portfolio-risk-replay',
        'incident-drill','g2-contracts','portfolio-trial-evaluation')
    $g7Logs = Join-Path $g7Run 'regressions'
    New-Item -ItemType Directory -Path $g7Logs | Out-Null
    foreach ($g7Suite in $g7Suites) {
        $g7Log = Join-Path $g7Logs ($g7Suite + '.log')
        & $g7Pwsh -NoProfile -NonInteractive -File (Join-Path $PSScriptRoot ('test-' + $g7Suite + '.ps1')) *>> $g7Log
        $g7Exit = $LASTEXITCODE
        Get-Content -LiteralPath $g7Log -Tail 5
        if ($g7Exit -ne 0) { throw "Existing regression failed: $g7Suite; exit=$g7Exit" }
    }
}
Write-Output "G7 synthetic validation retained: $g7Run"
