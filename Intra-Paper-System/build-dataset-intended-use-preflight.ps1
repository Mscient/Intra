param([string]$OutputRoot = (Join-Path $PSScriptRoot 'build\dataset-intended-use-preflight'))
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $version" }
$buildRoot = Join-Path ([System.IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $buildRoot 'classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java\com\mscient\intrapaper\permission\preflight') -Filter '*.java' -File | ForEach-Object FullName)
$sources += Join-Path $PSScriptRoot 'src\main\java\com\mscient\intrapaper\data\StrictJsonParser.java'
& $javac --release 21 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Standalone checker compilation failed: $LASTEXITCODE" }
$jarPath = Join-Path $buildRoot 'dataset-intended-use-preflight.jar'
& $jar --create --file $jarPath --main-class com.mscient.intrapaper.permission.preflight.PermissionPreflightCli -C $classes .
if ($LASTEXITCODE -ne 0) { throw "Standalone checker packaging failed: $LASTEXITCODE" }
Write-Output $jarPath
