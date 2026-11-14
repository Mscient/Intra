param(
    [string]$InstallRoot = (Join-Path $env:RUNNER_TEMP 'intra-paper-jdk')
)
$ErrorActionPreference = 'Stop'
# Microsoft Windows x64 LTS archive and its published SHA-256, pinned together.
# Source: https://learn.microsoft.com/en-us/java/openjdk/download
$version = '21.0.13.0.2'
$expectedHash = 'c5706a9cf482ef1a0622a86eea5b13d557b07f04554572af061e2d02b58c0f99'
$archiveUrl = "https://aka.ms/download-jdk/microsoft-jdk-$version-windows-x64.zip"
$InstallRoot = [System.IO.Path]::GetFullPath($InstallRoot)
New-Item -ItemType Directory -Force -Path $InstallRoot | Out-Null
$log = Join-Path $InstallRoot 'install.log'
@("JDK_VERSION=$version", "ARCHIVE_URL=$archiveUrl", "EXPECTED_SHA256=$expectedHash") |
    Set-Content -LiteralPath $log -Encoding utf8
try {
    $archive = Join-Path $InstallRoot "microsoft-jdk-$version-windows-x64.zip"
    Invoke-WebRequest -Uri $archiveUrl -OutFile $archive
    $actualHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    "ARCHIVE_SHA256=$actualHash" | Add-Content -LiteralPath $log
    if ($actualHash -cne $expectedHash) { throw 'Microsoft JDK archive checksum mismatch.' }
    $jdkDirectory = Join-Path $InstallRoot 'extracted'
    Expand-Archive -LiteralPath $archive -DestinationPath $jdkDirectory
    $jdkHomes = @(Get-ChildItem -LiteralPath $jdkDirectory -Directory |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\javac.exe') })
    if ($jdkHomes.Count -ne 1) { throw 'Expected exactly one extracted Microsoft JDK.' }
    $jdkHome = $jdkHomes[0].FullName
    $release = Get-Content -LiteralPath (Join-Path $jdkHome 'release')
    if ($release -cnotcontains 'IMPLEMENTOR="Microsoft"' -or
        $release -cnotcontains "JAVA_VERSION=`"$version`"") {
        throw 'Extracted JDK vendor/version does not match the pin.'
    }
    $env:JAVA_HOME = $jdkHome
    $env:PATH = (Join-Path $jdkHome 'bin') + [System.IO.Path]::PathSeparator + $env:PATH
    if ($env:GITHUB_ENV) { "JAVA_HOME=$jdkHome" | Add-Content -LiteralPath $env:GITHUB_ENV }
    if ($env:GITHUB_PATH) { (Join-Path $jdkHome 'bin') | Add-Content -LiteralPath $env:GITHUB_PATH }
    "JAVA_HOME=$jdkHome" | Add-Content -LiteralPath $log
    'INSTALL_STATUS=PASS' | Add-Content -LiteralPath $log
} catch {
    "INSTALL_STATUS=FAIL: $_" | Add-Content -LiteralPath $log
    throw
} finally {
    Get-Content -LiteralPath $log | ForEach-Object { Write-Host $_ }
}
