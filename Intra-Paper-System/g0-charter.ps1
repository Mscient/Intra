param([Parameter(Mandatory=$true)][string]$JarPath,[Parameter(ValueFromRemainingArguments=$true)][string[]]$Arguments)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$release = Get-Content -LiteralPath (Join-Path $env:JAVA_HOME 'release')
if ($release -cnotcontains 'IMPLEMENTOR="Microsoft"' -or $release -cnotcontains 'JAVA_VERSION="21.0.13.0.2"') { throw 'Use pinned Microsoft JDK 21.0.13.0.2.' }
& (Join-Path $env:JAVA_HOME 'bin/java.exe') -cp $JarPath com.mscient.intrapaper.g0.G0Checker @Arguments
exit $LASTEXITCODE
