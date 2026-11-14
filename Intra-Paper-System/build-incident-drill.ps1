param([string]$JdkHome, [string]$OutputRoot = (Join-Path $PSScriptRoot 'build\incident-drill'))
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
if ([string]::IsNullOrWhiteSpace($JdkHome)) { $JdkHome = Split-Path (Split-Path (Get-Command javac -ErrorAction Stop).Source) }
$javac = Join-Path $JdkHome 'bin\javac.exe'
$jar = Join-Path $JdkHome 'bin\jar.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $version" }
$buildRoot = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $buildRoot 'classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$sourceRoot = Join-Path $PSScriptRoot 'src\main\java'
$sources = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter '*.java' | Sort-Object FullName)
$sourceArgs = Join-Path $buildRoot 'sources.args'
$utf8 = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllLines($sourceArgs, [string[]]@($sources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }), $utf8)
& $javac --release 21 -encoding UTF-8 -d $classes ('@' + $sourceArgs)
if ($LASTEXITCODE -ne 0) { throw "Compilation failed: $LASTEXITCODE" }
$jarPath = Join-Path $buildRoot 'incident-drill.jar'
& $jar --create --file $jarPath --date=1980-01-01T00:00:02Z --main-class com.mscient.intrapaper.ops.drill.IncidentDrillCli -C $classes .
if ($LASTEXITCODE -ne 0) { throw "Packaging failed: $LASTEXITCODE" }
$hashes = @($sources | ForEach-Object { $_.FullName }) + @($PSCommandPath)
[IO.File]::WriteAllLines((Join-Path $buildRoot 'source-sha256.txt'), [string[]]@(Get-FileHash -Algorithm SHA256 -LiteralPath $hashes |
    ForEach-Object { $_.Hash.ToLowerInvariant() + '  ' + $_.Path }), $utf8)
[IO.File]::WriteAllText((Join-Path $buildRoot 'jdk-identity.txt'), ($version + "`n" + (Get-Content (Join-Path $JdkHome 'release') | Out-String)), $utf8)
Write-Output "Built $jarPath"
Write-Output "SHA256=$((Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant())"
