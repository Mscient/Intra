param([string]$OutputRoot = (Join-Path $PSScriptRoot ('build\offline-' + [guid]::NewGuid().ToString('N'))),
      [string]$ExpectedCommit)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$runnerArguments = @((Join-Path $PSScriptRoot 'test-offline.mjs'), '--output-root', $OutputRoot)
if ($ExpectedCommit) { $runnerArguments += @('--expected-commit', $ExpectedCommit) }
& node @runnerArguments
if ($LASTEXITCODE -ne 0) { throw "Offline compile/test/package checks failed with exit code $LASTEXITCODE" }
