$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceRoot = Join-Path $projectRoot 'src\main\java'
$buildRoot = Join-Path $projectRoot 'build'
$classesRoot = Join-Path $buildRoot 'classes'
$jarPath = Join-Path $buildRoot 'intra-paper-system.jar'
$sources = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter '*.java' | ForEach-Object { $_.FullName })

if ($sources.Count -eq 0) {
    throw "No Java sources found under $sourceRoot"
}

New-Item -ItemType Directory -Force -Path $classesRoot | Out-Null
& javac --release 21 -encoding UTF-8 -d $classesRoot @sources
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
& jar --create --file $jarPath -C $classesRoot .
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
Write-Output "Built $jarPath"
