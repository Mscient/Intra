param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$version = (& $java -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version "21(?:\.|\")') { throw "Expected Java 21, found: $version" }
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'Audit JAR is missing.' }
& $java -cp $JarPath com.mscient.intrapaper.audit.reconciliation.ExternalLedgerAuditCli @Arguments
exit $LASTEXITCODE
