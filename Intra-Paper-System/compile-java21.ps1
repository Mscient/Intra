param(
    [Parameter(Mandatory = $true)][string]$Javac,
    [Parameter(Mandatory = $true)][string]$Destination,
    [Parameter(Mandatory = $true)][string[]]$Sources,
    [string]$Classpath
)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
# Argument files avoid Windows' native command-length limit and preserve UTF-8 paths.
$arguments = Join-Path $Destination ('javac-' + [guid]::NewGuid().ToString('N') + '.args')
New-Item -ItemType Directory -Force -Path $Destination | Out-Null
$exitCode = 1
try {
    [IO.File]::WriteAllLines($arguments, [string[]]@($Sources | ForEach-Object {
        '"' + [IO.Path]::GetFullPath($_).Replace('\', '/') + '"'
    }), [Text.UTF8Encoding]::new($false))
    $options = @('--release', '21', '-encoding', 'UTF-8', '-d', $Destination)
    if ($Classpath) { $options += @('-cp', $Classpath) }
    & $Javac @options ('@' + $arguments)
    $exitCode = $LASTEXITCODE
} finally {
    Remove-Item -LiteralPath $arguments -Force -ErrorAction SilentlyContinue
}
exit $exitCode
