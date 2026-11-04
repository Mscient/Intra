param(
    [string] $Fixture = '..\research\systems\option-pricing-benchmark\r11_reference_cases.csv'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$jdkHome = $env:JAVA_HOME
$javac = if ([string]::IsNullOrWhiteSpace($jdkHome)) { $null } else { Join-Path $jdkHome 'bin\javac.exe' }
$java = if ([string]::IsNullOrWhiteSpace($jdkHome)) { $null } else { Join-Path $jdkHome 'bin\java.exe' }
$previousBuildDir = $env:INTRA_PAPER_BUILD_DIR
$buildRoot = Join-Path $env:LOCALAPPDATA 'Intra-Paper-System\c2-validation-build'
$validationRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('intra-pricer-validation-' + [guid]::NewGuid().ToString('N'))
$tempPrefix = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\intra-pricer-validation-'
$validationRootAbsolute = [System.IO.Path]::GetFullPath($validationRoot)

if (-not $javac -or -not (Test-Path -LiteralPath $javac) -or -not (Test-Path -LiteralPath $java)) {
    throw 'Set JAVA_HOME to a JDK 21 installation before running pricer validation.'
}
$compilerVersion = (& $javac -version 2>&1 | Out-String).Trim()
if ($compilerVersion -notmatch '^javac 21(?:\.|$)') {
    throw "Expected JDK 21, found: $compilerVersion"
}
if (-not $validationRootAbsolute.StartsWith($tempPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Validation classes directory must be inside the system temporary directory.'
}

$fixturePath = [System.IO.Path]::GetFullPath((Join-Path $projectRoot $Fixture))
$harnessRoot = Join-Path $projectRoot 'validation\java'
$harnessSources = @(Get-ChildItem -LiteralPath $harnessRoot -Recurse -File -Filter '*.java' |
    ForEach-Object { $_.FullName })
if ($harnessSources.Count -eq 0) {
    throw "No validation Java sources found under $harnessRoot"
}

try {
    $env:INTRA_PAPER_BUILD_DIR = $buildRoot
    & (Join-Path $projectRoot 'build.ps1')
    if ($LASTEXITCODE -ne 0) { throw "Java 21 production build failed with exit code $LASTEXITCODE" }
    $jarPath = Join-Path $buildRoot 'intra-paper-system.jar'
    if (-not (Test-Path -LiteralPath $jarPath -PathType Leaf)) { throw "Build JAR not found: $jarPath" }

    New-Item -ItemType Directory -Force -Path $validationRootAbsolute | Out-Null
    & $javac --release 21 -encoding UTF-8 -classpath $jarPath -d $validationRootAbsolute @harnessSources
    if ($LASTEXITCODE -ne 0) { throw "Validation harness compilation failed with exit code $LASTEXITCODE" }

    $classPath = $jarPath + ';' + $validationRootAbsolute
    & $java -classpath $classPath com.mscient.intrapaper.pricing.OptionPricingValidation $fixturePath
    if ($LASTEXITCODE -ne 0) { throw "Option-pricer validation failed with exit code $LASTEXITCODE" }
} finally {
    if ($null -eq $previousBuildDir) {
        Remove-Item Env:\INTRA_PAPER_BUILD_DIR -ErrorAction SilentlyContinue
    } else {
        $env:INTRA_PAPER_BUILD_DIR = $previousBuildDir
    }
    if ((Test-Path -LiteralPath $validationRootAbsolute) -and
        $validationRootAbsolute.StartsWith($tempPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $validationRootAbsolute -Recurse -Force
    }
}
