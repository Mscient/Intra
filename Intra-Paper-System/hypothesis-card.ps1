param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('inspect', 'archive', 'verify', 'check-trial', 'schema')]
    [string]$Command,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments,
    [string]$JarPath = (Join-Path $env:LOCALAPPDATA 'Intra-Paper-System\build\intra-paper-system.jar')
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
& (Join-Path $env:JAVA_HOME 'bin\java.exe') -cp $JarPath com.mscient.intrapaper.evaluation.hypothesis.HypothesisCardCli $Command @Arguments
exit $LASTEXITCODE
