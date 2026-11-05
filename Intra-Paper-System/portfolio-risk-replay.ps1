param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$ReplayArgs
)
$ErrorActionPreference = 'Stop'
$javaCommand = Get-Command java -ErrorAction Stop
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw "Build the project first; JAR missing: $JarPath" }
& $javaCommand.Source -cp $JarPath com.mscient.intrapaper.simulation.portfolio.risk.PortfolioRiskReplayRunner @ReplayArgs
exit $LASTEXITCODE
