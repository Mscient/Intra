param([Parameter(ValueFromRemainingArguments = $true)][string[]]$ResearchArguments)
$ErrorActionPreference = 'Stop'
$buildRoot = if ($env:INTRA_PAPER_BUILD_DIR) { $env:INTRA_PAPER_BUILD_DIR } else { Join-Path $PSScriptRoot 'build' }
$jarPath = Join-Path $buildRoot 'intra-paper-system.jar'
if (-not (Test-Path -LiteralPath $jarPath)) { throw "Build the research JAR first: $jarPath" }
$javaPath = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
& $javaPath -cp $jarPath com.mscient.intrapaper.evaluation.portfolio.NiftyResearchWorkflow @ResearchArguments
exit $LASTEXITCODE
