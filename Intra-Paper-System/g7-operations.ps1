# Parse named arguments inside the redacted error boundary. PowerShell parameter
# binding errors otherwise echo caller values before this script's catch runs.
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
# The -File host normalizes -Name:value before populating $args. For a direct
# launcher invocation, validate its original argument tokens instead.
$g7InvocationArgs = $args
$g7HostArgs = [Environment]::GetCommandLineArgs()
for ($g7HostIndex = 0; $g7HostIndex + 1 -lt $g7HostArgs.Count; $g7HostIndex++) {
    if ($g7HostArgs[$g7HostIndex] -ieq '-File' -and
        [IO.Path]::GetFullPath($g7HostArgs[$g7HostIndex + 1]) -ieq $PSCommandPath) {
        $g7InvocationArgs = @($g7HostArgs | Select-Object -Skip ($g7HostIndex + 2))
        break
    }
}
$g7Options = @{}
for ($g7Index = 0; $g7Index -lt $g7InvocationArgs.Count; $g7Index += 2) {
    $g7Name = [string]$g7InvocationArgs[$g7Index]
    if ($g7Name -cnotin @('-Command','-JarPath','-Dossier','-Output','-History','-Event') -or
        $g7Options.ContainsKey($g7Name) -or $g7Index + 1 -ge $g7InvocationArgs.Count) { throw 'Invalid named arguments.' }
    $g7Value = [string]$g7InvocationArgs[$g7Index + 1]
    if ([string]::IsNullOrWhiteSpace($g7Value) -or $g7Value.StartsWith('-')) { throw 'Argument value required.' }
    $g7Options[$g7Name] = $g7Value
}
$Command = $g7Options['-Command']; $JarPath = $g7Options['-JarPath']
$Dossier = $g7Options['-Dossier']; $Output = $g7Options['-Output']
$History = $g7Options['-History']; $Event = $g7Options['-Event']
if ($Command -cnotin @('evaluate','verify','explain','append') -or -not $JarPath) { throw 'Command and JarPath required.' }
# Validate the complete command shape before reading any supplied path or JDK.
$arguments = @()
switch ($Command) {
    'evaluate' { if (-not $Dossier -or -not $Output -or $Event) { throw 'evaluate requires Dossier and Output; History is optional.' }; $arguments += @($Dossier,$Output); if ($History) { $arguments += $History } }
    'verify' { if (-not $Dossier -or -not $Output -or $Event) { throw 'verify requires Dossier and Output; History is optional.' }; $arguments += @($Dossier,$Output); if ($History) { $arguments += $History } }
    'explain' { if ($Output -or $History -or $Event) { throw 'explain accepts an optional Dossier only.' }; if ($Dossier) { $arguments += $Dossier } }
    'append' { if (-not $Dossier -or -not $Output -or -not $History -or -not $Event) { throw 'append requires Dossier, History, Event and new Output.' }; $arguments += @($Dossier,$History,$Event,$Output) }
}
$g7Jdk = Get-G7LocalPath $env:JAVA_HOME
$g7Jar = Get-G7LocalPath $JarPath
if (-not (Test-Path -LiteralPath $g7Jar -PathType Leaf) -or [IO.Path]::GetPathRoot($g7Jar) -cne [IO.Path]::GetPathRoot($g7Jdk)) { throw 'Ordinary local JAR on the JDK volume required.' }
foreach ($g7Path in @($Dossier,$Output,$History,$Event)) { if ($g7Path) { Get-G7LocalPath $g7Path | Out-Null } }
$g7Release = Get-Content -LiteralPath (Get-G7LocalPath (Join-Path $g7Jdk 'release'))
if ($g7Release -cnotcontains 'IMPLEMENTOR="Microsoft"' -or $g7Release -cnotcontains 'JAVA_VERSION="21.0.13.0.2"') { throw 'Use pinned Microsoft JDK 21.0.13.0.2.' }
$java = Get-G7LocalPath (Join-Path $g7Jdk 'bin/java.exe')
$arguments = @('-cp', $g7Jar, 'com.mscient.intrapaper.ops.governance.G7Cli', $Command) + $arguments
# Launcher errors can disclose paths before G7Cli starts. Release only G7 JSON.
$g7Response = @(& $java @arguments 2>&1)
$g7Exit = $LASTEXITCODE
$g7Text = $g7Response -join "`n"
$g7Diagnostic = ConvertFrom-Json -InputObject $g7Text -AsHashtable
if ($g7Exit -notin @(0,1,2,3) -or $g7Diagnostic['schema'] -cnotin @('g7-error/v1','g7-result/v1','g7-explanation/v1') -or
    $g7Diagnostic['g7_closed'] -cne $false -or $g7Diagnostic['operation_authorized'] -cne $false -or $g7Diagnostic['scale_authorized'] -cne $false) { throw 'Invalid diagnostic response.' }
[Console]::Out.Write($g7Text + "`n")
exit $g7Exit
} catch {
    [Console]::Out.Write('{"g7_closed":false,"g7_proposed":true,"operation_authorized":false,"scale_authorized":false,"schema":"g7-error/v1","severity":null,"status":"FAIL","required_action":"Invocation rejected. Use ordinary local files and the documented command arguments. No authority granted."}' + "`n")
    exit 1
}
