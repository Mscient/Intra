$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdkHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($jdkHome)) { throw 'Set JAVA_HOME to JDK 21.' }
$javac = Join-Path $jdkHome 'bin\javac.exe'
$java = Join-Path $jdkHome 'bin\java.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($version -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $version" }
$auditTestRoot = [System.IO.Path]::GetFullPath((Join-Path $env:TEMP ('portfolio-risk-audit-checks-' + [guid]::NewGuid().ToString('N'))))
$auditTempPrefix = [System.IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
if (-not $auditTestRoot.StartsWith($auditTempPrefix, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid test root.' }
New-Item -ItemType Directory -Path $auditTestRoot | Out-Null
try {
    $classes = Join-Path $auditTestRoot 'classes'
    New-Item -ItemType Directory -Path $classes | Out-Null
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Recurse -File -Filter '*.java' | ForEach-Object { $_.FullName })
    $test = Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\audit\portfolio\PortfolioRiskAuditTest.java'
    & $javac --release 21 -encoding UTF-8 -d $classes @sources $test
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed: $LASTEXITCODE" }
    & $java -cp $classes com.mscient.intrapaper.audit.portfolio.PortfolioRiskAuditTest $projectRoot
    if ($LASTEXITCODE -ne 0) { throw "Audit checks failed: $LASTEXITCODE" }
} finally {
    $resolvedAuditRoot = [System.IO.Path]::GetFullPath($auditTestRoot)
    if (-not $resolvedAuditRoot.StartsWith($auditTempPrefix, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing cleanup outside TEMP.' }
    $item = Get-Item -LiteralPath $resolvedAuditRoot -Force
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Refusing cleanup of a reparse point.' }
    Remove-Item -LiteralPath $resolvedAuditRoot -Recurse -Force
}
