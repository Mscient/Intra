param([Parameter(Mandatory=$true)][string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$release = Get-Content -LiteralPath (Join-Path $env:JAVA_HOME 'release')
if ($release -cnotcontains 'IMPLEMENTOR="Microsoft"' -or $release -cnotcontains 'JAVA_VERSION="21.0.13.0.2"') { throw 'Use pinned Microsoft JDK 21.0.13.0.2.' }
$run = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
$sources = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src/main/java/com/mscient/intrapaper/g0') -File -Filter '*.java' | Sort-Object Name
$argFile = Join-Path $run 'sources.args'
[IO.File]::WriteAllLines($argFile, [string[]]@($sources | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }), $utf8)
& (Join-Path $env:JAVA_HOME 'bin/javac.exe') --release 21 -encoding UTF-8 -d $classes ('@' + $argFile)
if ($LASTEXITCODE -ne 0) { throw 'G0 compilation failed.' }
$commit = (& git -C $PSScriptRoot rev-parse HEAD | Out-String).Trim()
$dirty = @(& git -C $PSScriptRoot status --porcelain)
if ($LASTEXITCODE -ne 0 -or $dirty.Count -ne 0) { $commit = 'unknown-dirty' }
$manifest = Join-Path $run 'MANIFEST.MF'
[IO.File]::WriteAllText($manifest, "Manifest-Version: 1.0`r`nMain-Class: com.mscient.intrapaper.g0.G0Checker`r`nBuild-Commit: $commit`r`nImplementation-Version: g0-v1`r`n`r`n", $utf8)
$stage = Join-Path $run 'staged.jar'
$jar = Join-Path $run 'g0-charter.jar'
& (Join-Path $env:JAVA_HOME 'bin/jar.exe') --create --file $stage --manifest $manifest -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'G0 packaging failed.' }
[IO.File]::Move($stage,$jar,$false)
Write-Output $jar
