$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdkHome = $env:JAVA_HOME
$javac = if ([string]::IsNullOrWhiteSpace($jdkHome)) { $null } else { Join-Path $jdkHome 'bin\javac.exe' }
$jarTool = if ([string]::IsNullOrWhiteSpace($jdkHome)) { $null } else { Join-Path $jdkHome 'bin\jar.exe' }
$sourceRoot = Join-Path $projectRoot 'src\main\java'
$buildRoot = $env:INTRA_PAPER_BUILD_DIR
if ([string]::IsNullOrWhiteSpace($buildRoot)) {
    $buildRoot = Join-Path $env:LOCALAPPDATA 'Intra-Paper-System\build'
}
$classesRoot = Join-Path $buildRoot 'classes'
$jarPath = Join-Path $buildRoot 'intra-paper-system.jar'
$sources = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter '*.java' | ForEach-Object { $_.FullName })

if (-not $javac -or -not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $jarTool)) {
    throw 'Set JAVA_HOME to a JDK 21 installation before building.'
}
$compilerVersion = (& $javac -version 2>&1 | Out-String).Trim()
if ($compilerVersion -notmatch '^javac 21(?:\.|$)') {
    throw "Expected JDK 21, found: $compilerVersion"
}
if ($sources.Count -eq 0) {
    throw "No Java sources found under $sourceRoot"
}

New-Item -ItemType Directory -Force -Path $classesRoot | Out-Null
& $javac --release 21 -encoding UTF-8 -d $classesRoot @sources
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
& $jarTool --create --file $jarPath --main-class com.mscient.intrapaper.cli.IntraPaperCli -C $classesRoot .
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
Write-Output "Built $jarPath"
