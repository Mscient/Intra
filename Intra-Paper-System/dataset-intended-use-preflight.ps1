param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'Preflight JAR is missing.' }
& $java -cp $JarPath com.mscient.intrapaper.permission.preflight.PermissionPreflightCli @Arguments
exit $LASTEXITCODE
