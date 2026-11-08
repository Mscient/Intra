param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$CommandArguments
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 21.' }
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'
& $java -cp (Resolve-Path -LiteralPath $JarPath).Path com.mscient.intrapaper.ingestion.HistoricalIngestionCli @CommandArguments
exit $LASTEXITCODE
