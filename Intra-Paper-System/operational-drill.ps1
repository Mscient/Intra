param(
    [Parameter(Mandatory = $true)][string]$JarPath,
    [Parameter(Mandatory = $true, Position = 0)][ValidateSet('run', 'verify')][string]$Command,
    [Parameter(Mandatory = $true, Position = 1)][string]$InputPath,
    [Parameter(Mandatory = $true, Position = 2)][string]$OutputOrHash,
    [string]$JdkHome
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($JdkHome)) { $JdkHome = Split-Path (Split-Path (Get-Command java -ErrorAction Stop).Source) }
$java = Join-Path $JdkHome 'bin\java.exe'
if ($Command -eq 'run') { $OutputOrHash = [IO.Path]::GetFullPath($OutputOrHash) }
& $java -cp ([IO.Path]::GetFullPath($JarPath)) com.mscient.intrapaper.ops.operationaldrill.OperationalDrillCli $Command ([IO.Path]::GetFullPath($InputPath)) $OutputOrHash
exit $LASTEXITCODE
