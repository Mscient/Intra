param(
    [Parameter(Mandatory=$true)][string]$JarPath,
    [Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 21.' }
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (-not (Test-Path -LiteralPath $java)) { throw "Java not found: $java" }
$version = (& $java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version "21(?:\.|\")') { throw 'Expected Java 21 runtime.' }
& $java -cp (Resolve-Path -LiteralPath $JarPath).Path com.mscient.intrapaper.admission.G2AdmissionCli @Arguments
exit $LASTEXITCODE
