param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$AuditArgs
)
$ErrorActionPreference = 'Stop'
$jdkHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($jdkHome)) { throw 'Set JAVA_HOME to JDK 21.' }
$java = Join-Path $jdkHome 'bin\java.exe'
$javac = Join-Path $jdkHome 'bin\javac.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($version -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $version" }
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw "Build the project first; JAR missing: $JarPath" }
& $java -cp $JarPath com.mscient.intrapaper.audit.portfolio.PortfolioRiskAuditCli @AuditArgs
exit $LASTEXITCODE
