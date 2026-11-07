param([string]$OutputRoot = (Join-Path $PSScriptRoot 'build\data-permission-coverage'))
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw 'Set JAVA_HOME to JDK 21.' }
$javac = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$jar = Join-Path $env:JAVA_HOME 'bin\jar.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 21(?:\.|$)') { throw 'JDK 21 is required.' }
$runRoot = Join-Path ([System.IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $runRoot 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sourceRoot = Join-Path $PSScriptRoot 'src\main\java\com\mscient\intrapaper'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $sourceRoot 'permission\coverage') -Filter '*.java' -File | ForEach-Object FullName)
$sources += Join-Path $sourceRoot 'data\StrictJsonParser.java'
$sourceList = Join-Path $runRoot 'sources.args'
[System.IO.File]::WriteAllLines($sourceList, @($sources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' }), [System.Text.UTF8Encoding]::new($false))
& $javac --release 21 -encoding UTF-8 -d $classes ("@" + $sourceList)
if ($LASTEXITCODE -ne 0) { throw 'Standalone compilation failed.' }
$jarPath = Join-Path $runRoot 'data-permission-coverage.jar'
& $jar --create --file $jarPath --main-class com.mscient.intrapaper.permission.coverage.PermissionCoverageCli -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Standalone packaging failed.' }
Write-Output $jarPath
