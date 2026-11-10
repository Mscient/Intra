param(
    [Parameter(Mandatory=$true)][ValidateSet('evaluate','verify','explain','append')][string]$Command,
    [Parameter(Mandatory=$true)][string]$JarPath,
    [string]$Dossier,
    [string]$Output,
    [string]$History,
    [string]$Event
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
function Get-G7LocalPath([string]$G7Requested) {
    if ([string]::IsNullOrWhiteSpace($G7Requested) -or $G7Requested -match '^(\\\\|//)' -or $G7Requested.Contains('://') -or
        $G7Requested -match '(^|[\\/])\.\.([\\/]|$)') { throw 'Ordinary local paths without traversal are required.' }
    $g7Absolute = [IO.Path]::GetFullPath($G7Requested)
    if ($g7Absolute -notmatch '^[A-Za-z]:\\' -or $g7Absolute.Substring(2).Contains(':')) { throw 'Network/device/alternate-stream paths are forbidden.' }
    $g7Step = $g7Absolute
    while ($g7Step) {
        if (Test-Path -LiteralPath $g7Step) {
            if (((Get-Item -LiteralPath $g7Step -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Symlink/junction paths are forbidden.' }
        }
        $g7Step = [IO.Path]::GetDirectoryName($g7Step)
    }
    return $g7Absolute
}
try {
$g7Jdk = Get-G7LocalPath $env:JAVA_HOME
$g7Jar = Get-G7LocalPath $JarPath
if (-not (Test-Path -LiteralPath $g7Jar -PathType Leaf) -or [IO.Path]::GetPathRoot($g7Jar) -cne [IO.Path]::GetPathRoot($g7Jdk)) { throw 'Ordinary local JAR on the JDK volume required.' }
foreach ($g7Path in @($Dossier,$Output,$History,$Event)) { if ($g7Path) { Get-G7LocalPath $g7Path | Out-Null } }
$java = Join-Path $g7Jdk 'bin/java.exe'
$arguments = @('-cp', $g7Jar, 'com.mscient.intrapaper.ops.governance.G7Cli', $Command)
switch ($Command) {
    'evaluate' { if (-not $Dossier -or -not $Output -or $Event) { throw 'evaluate requires Dossier and Output; History is optional.' }; $arguments += @($Dossier,$Output); if ($History) { $arguments += $History } }
    'verify' { if (-not $Dossier -or -not $Output -or $Event) { throw 'verify requires Dossier and Output; History is optional.' }; $arguments += @($Dossier,$Output); if ($History) { $arguments += $History } }
    'explain' { if ($Output -or $History -or $Event) { throw 'explain accepts an optional Dossier only.' }; if ($Dossier) { $arguments += $Dossier } }
    'append' { if (-not $Dossier -or -not $Output -or -not $History -or -not $Event) { throw 'append requires Dossier, History, Event and new Output.' }; $arguments += @($Dossier,$History,$Event,$Output) }
}
& $java @arguments
exit $LASTEXITCODE
} catch {
    [Console]::Out.Write('{"g7_closed":false,"g7_proposed":true,"operation_authorized":false,"scale_authorized":false,"schema":"g7-error/v1","severity":null,"status":"FAIL","required_action":"Invocation rejected. Use ordinary local files and the documented command arguments. No authority granted."}' + "`n")
    exit 1
}
