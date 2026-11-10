param([Parameter(Mandatory=$true)][string]$JarPath,
      [Parameter(Mandatory=$true)][ValidateSet('check','verify','explain','record')][string]$Command,
      [Parameter(Mandatory=$true)][string[]]$Arguments)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
& (Join-Path $env:JAVA_HOME 'bin/java.exe') -cp $JarPath com.mscient.intrapaper.g5.G5Cli $Command @Arguments
exit $LASTEXITCODE
