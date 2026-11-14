param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$EvidenceArgs
)
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($version -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $version" }
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'Build the project first; JAR missing.' }
& $java -cp $JarPath com.mscient.intrapaper.ops.evidence.DailyOpsEvidenceCli @EvidenceArgs
exit $LASTEXITCODE
