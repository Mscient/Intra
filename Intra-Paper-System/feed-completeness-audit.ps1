param(
    [Parameter(Mandatory=$true)][ValidateSet('run','verify','verify-artifact')][string]$Mode,
    [Parameter(Mandatory=$true)][string]$Evidence,
    [string]$Artifact,
    [Parameter(Mandatory=$true)][string]$JarPath
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
$java = Join-Path $env:JAVA_HOME 'bin\java.exe'
$version = (& $java -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version "21(?:\.|"|-)') { throw 'Expected Java 21.' }
if ($Mode -ne 'verify' -and [string]::IsNullOrWhiteSpace($Artifact)) { throw 'Artifact is required for run and verify-artifact.' }
if ($Mode -eq 'verify' -and $Artifact) { throw 'verify takes no Artifact.' }
$arguments = @($Mode, (Resolve-Path -LiteralPath $Evidence).Path)
if ($Artifact) { $arguments += [IO.Path]::GetFullPath($Artifact) }
& $java -cp (Resolve-Path -LiteralPath $JarPath).Path com.mscient.intrapaper.audit.feed.FeedAuditCli @arguments
exit $LASTEXITCODE
