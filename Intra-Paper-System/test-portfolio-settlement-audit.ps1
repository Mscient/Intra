param([string]$JarPath)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdkHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($jdkHome)) { throw 'Set JAVA_HOME to JDK 21.' }
$javac = Join-Path $jdkHome 'bin\javac.exe'
$java = Join-Path $jdkHome 'bin\java.exe'
$version = (& $javac -version 2>&1 | Out-String).Trim()
if ($version -notmatch '^javac 21(?:\.|$)') { throw "Expected JDK 21, found: $version" }
$settlementTestRoot = [System.IO.Path]::GetFullPath((Join-Path $env:TEMP ('portfolio-settlement-checks-' + [guid]::NewGuid().ToString('N'))))
$settlementTempPrefix = [System.IO.Path]::GetFullPath($env:TEMP).TrimEnd('\') + '\'
if (-not $settlementTestRoot.StartsWith($settlementTempPrefix, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Invalid test root.' }
New-Item -ItemType Directory -Path $settlementTestRoot | Out-Null
try {
    $classes = Join-Path $settlementTestRoot 'classes'
    New-Item -ItemType Directory -Path $classes | Out-Null
    $test = Join-Path $projectRoot 'src\test\java\com\mscient\intrapaper\audit\portfolio\PortfolioSettlementAuditTest.java'
    if ([string]::IsNullOrWhiteSpace($JarPath)) {
        $sources = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src\main\java') -Recurse -File -Filter '*.java' | ForEach-Object { $_.FullName })
        & (Join-Path $PSScriptRoot 'compile-java21.ps1') -Javac $javac -Destination $classes -Sources ($sources + @($test))
        $classpath = $classes
    } else {
        if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'JAR missing.' }
        & $javac --release 21 -encoding UTF-8 -cp $JarPath -d $classes $test
        $classpath = $JarPath + [System.IO.Path]::PathSeparator + $classes
    }
    if ($LASTEXITCODE -ne 0) { throw "Compilation failed: $LASTEXITCODE" }
    & $java -cp $classpath com.mscient.intrapaper.audit.portfolio.PortfolioSettlementAuditTest $projectRoot
    if ($LASTEXITCODE -ne 0) { throw "Settlement checks failed: $LASTEXITCODE" }
} finally {
    $resolvedSettlementRoot = [System.IO.Path]::GetFullPath($settlementTestRoot)
    if (-not $resolvedSettlementRoot.StartsWith($settlementTempPrefix, [System.StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing cleanup outside TEMP.' }
    $item = Get-Item -LiteralPath $resolvedSettlementRoot -Force
    if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Refusing cleanup of a reparse point.' }
    Remove-Item -LiteralPath $resolvedSettlementRoot -Recurse -Force
}
