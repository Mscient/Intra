param(
    [Parameter(Mandatory = $true)][string]$TrialLedger,
    [Parameter(Mandatory = $true)][string]$TrialCatalog,
    [Parameter(Mandatory = $true)][string]$RunsRoot,
    [Parameter(Mandatory = $true)][int]$ChronologicalBlocks,
    [Parameter(Mandatory = $true)][string]$OutputJson,
    [string]$OutcomeLedger
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$taskJdkHome = $env:JAVA_HOME
$java = if ([string]::IsNullOrWhiteSpace($taskJdkHome)) { $null } else { Join-Path $taskJdkHome 'bin\java.exe' }
if (-not $java -or -not (Test-Path -LiteralPath $java)) { throw 'Set JAVA_HOME to a JDK 21 installation.' }
$buildRoot = $env:INTRA_PAPER_BUILD_DIR
if ([string]::IsNullOrWhiteSpace($buildRoot)) { $buildRoot = Join-Path $env:LOCALAPPDATA 'Intra-Paper-System\build' }
$jarPath = Join-Path $buildRoot 'intra-paper-system.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Build the project with build.ps1 before evaluating portfolio trials.' }
$evaluationArgs = @('evaluate', $TrialLedger, $TrialCatalog, $RunsRoot, [string]$ChronologicalBlocks, $OutputJson)
if (-not [string]::IsNullOrWhiteSpace($OutcomeLedger)) { $evaluationArgs += $OutcomeLedger }
& $java -cp $jarPath com.mscient.intrapaper.evaluation.portfolio.PortfolioTrialEvaluationCli @evaluationArgs
if ($LASTEXITCODE -ne 0) { throw "Portfolio trial evaluation failed with exit code $LASTEXITCODE" }
