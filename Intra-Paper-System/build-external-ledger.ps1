param([string]$OutputRoot = (Join-Path $PSScriptRoot 'build\external-ledger'))
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
# Compile existing replay dependencies without changing their source or entrypoint.
$sourceArgs = Join-Path $buildRoot 'sources.args'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src\main\java') -Recurse -File -Filter '*.java' |
    Sort-Object FullName | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
[System.IO.File]::WriteAllLines($sourceArgs, $sources, [System.Text.UTF8Encoding]::new($false))
& $javac --release 21 -encoding UTF-8 -d $classes ('@' + $sourceArgs)
if ($LASTEXITCODE -ne 0) { throw "Compilation failed: $LASTEXITCODE" }
$jarPath = Join-Path $buildRoot 'external-ledger-audit.jar'
& $jar --create --file $jarPath --date=1980-01-01T00:00:02Z `
    --main-class com.mscient.intrapaper.audit.reconciliation.ExternalLedgerAuditCli -C $classes .
if ($LASTEXITCODE -ne 0) { throw "JAR creation failed: $LASTEXITCODE" }
Write-Output "Built $jarPath"
Write-Output "SHA256=$((Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash.ToLowerInvariant())"
