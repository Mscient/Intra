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
$buildRoot = [System.IO.Path]::GetFullPath($buildRoot)
$classesRoot = Join-Path $buildRoot ("classes-" + [guid]::NewGuid().ToString('N'))
$jarPath = Join-Path $buildRoot 'intra-paper-system.jar'
$stagedJarPath = Join-Path $buildRoot ("intra-paper-system-" + [guid]::NewGuid().ToString('N') + '.tmp.jar')
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

$classesRootAbsolute = [System.IO.Path]::GetFullPath($classesRoot)
$buildRootPrefix = $buildRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if (-not $classesRootAbsolute.StartsWith($buildRootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Temporary classes directory must be inside the configured build directory.'
}
New-Item -ItemType Directory -Force -Path $classesRootAbsolute | Out-Null
$sourceArgumentPath = Join-Path $classesRootAbsolute 'javac-sources.args'
try {
    # Pass source paths through a UTF-8 argument file to avoid Windows command-line limits.
    $sourceArguments = @($sources | ForEach-Object { '"' + $_.Replace('\', '/') + '"' })
    [System.IO.File]::WriteAllLines($sourceArgumentPath, $sourceArguments, [System.Text.UTF8Encoding]::new($false))
    & $javac --release 21 -encoding UTF-8 -d $classesRootAbsolute ("@" + $sourceArgumentPath)
    if ($LASTEXITCODE -ne 0) {
        throw "javac failed with exit code $LASTEXITCODE"
    }
    Remove-Item -LiteralPath $sourceArgumentPath -Force
    $buildCommit = 'unknown'
    $gitCommand = Get-Command git -ErrorAction SilentlyContinue
    if ($gitCommand) {
        $candidateCommit = (& $gitCommand.Source -C $projectRoot rev-parse --verify HEAD 2>$null | Out-String).Trim()
        if ($LASTEXITCODE -eq 0 -and $candidateCommit -match '^[0-9a-f]{40}$') {
            $worktreeStatus = @(& $gitCommand.Source -C $projectRoot status --porcelain --untracked-files=normal 2>$null)
            if ($LASTEXITCODE -eq 0 -and $worktreeStatus.Count -eq 0) {
                $buildCommit = $candidateCommit
            } else {
                $buildCommit = 'unknown-dirty'
            }
        }
    }
    $manifestPath = Join-Path $buildRoot 'MANIFEST.MF'
    $manifestText = "Manifest-Version: 1.0`r`nMain-Class: com.mscient.intrapaper.cli.IntraPaperCli`r`nBuild-Commit: $buildCommit`r`nImplementation-Version: 1.0.0`r`n`r`n"
    [System.IO.File]::WriteAllText($manifestPath, $manifestText, [System.Text.UTF8Encoding]::new($false))
    & $jarTool --create --file $stagedJarPath --manifest $manifestPath -C $classesRootAbsolute .
    if ($LASTEXITCODE -ne 0) {
        throw "jar failed with exit code $LASTEXITCODE"
    }
    [System.IO.File]::Move($stagedJarPath, $jarPath, $true)
} finally {
    if (Test-Path -LiteralPath $sourceArgumentPath) {
        Remove-Item -LiteralPath $sourceArgumentPath -Force
    }
    if (Test-Path -LiteralPath $classesRootAbsolute) {
        Remove-Item -LiteralPath $classesRootAbsolute -Recurse -Force
    }
    if (Test-Path -LiteralPath $stagedJarPath) {
        Remove-Item -LiteralPath $stagedJarPath -Force
    }
}
Write-Output "Built $jarPath"
