param(
    [Parameter(Mandatory = $true)][ValidateSet('review','verify','explain','append')][string]$Command,
    [string]$Dossier,
    [string]$History,
    [string]$Output,
    [string]$Event,
    [Parameter(Mandatory = $true)][string]$JarPath
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$version = (& $java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version "21(?:\.|\")') { throw 'Java 21 is required.' }
$cliArgs = @($Command)
switch ($Command) {
    'review' { if (-not $Dossier -or -not $History -or -not $Output) { throw 'review requires Dossier, History, Output.' }; $cliArgs += @($Dossier,$History,$Output) }
    'verify' { if (-not $Dossier -or -not $Output) { throw 'verify requires Dossier and Output.' }; $cliArgs += @($Dossier,$Output); if ($History) { $cliArgs += $History } }
    'explain' { if ($Dossier -or $History) { if (-not $Dossier -or -not $History) { throw 'explain requires both Dossier and History or neither.' }; $cliArgs += @($Dossier,$History) } }
    'append' { if (-not $History -or -not $Event -or -not $Output) { throw 'append requires History, Event, Output.' }; $cliArgs += @($History,$Event,$Output) }
}
& $java -cp (Resolve-Path -LiteralPath $JarPath).Path com.mscient.intrapaper.review.g6.G6Cli @cliArgs
exit $LASTEXITCODE
