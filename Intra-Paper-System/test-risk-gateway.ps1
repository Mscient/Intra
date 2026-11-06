param(
    [string]$JdkHome = $env:JAVA_HOME,
    [string]$OutputRoot = (Join-Path $PSScriptRoot 'build\risk-gateway-verification')
)
$ErrorActionPreference = 'Stop'
$java = Join-Path $JdkHome 'bin\java.exe'
$javac = Join-Path $JdkHome 'bin\javac.exe'
$jar = Join-Path $JdkHome 'bin\jar.exe'
foreach ($tool in @($java, $javac, $jar)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw "JDK 21 tool missing: $tool" }
}
$compilerVersion = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $compilerVersion -notmatch '^javac 21(?:\.|$)') {
    throw "Expected JDK 21 compiler, found: $compilerVersion"
}
$runRoot = Join-Path ([System.IO.Path]::GetFullPath($OutputRoot)) ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $runRoot 'classes'
$tests = Join-Path $runRoot 'test-classes'
New-Item -ItemType Directory -Path $classes, $tests -Force | Out-Null
$utf8 = [System.Text.UTF8Encoding]::new($false)
$sourceRoot = Join-Path $PSScriptRoot 'src\main\java\com\mscient\intrapaper\riskgateway'
$sources = @('RiskGateway.java', 'RiskGatewayJournal.java', 'DurableRiskGateway.java' |
    ForEach-Object { Join-Path $sourceRoot $_ })
$testSource = Join-Path $PSScriptRoot 'src\test\java\com\mscient\intrapaper\riskgateway\RiskGatewayDurabilityTest.java'
$release = Join-Path $JdkHome 'release'
$identity = @("JAVA_HOME=$JdkHome", "COMPILER=$compilerVersion")
$identity += @(Get-Content -LiteralPath $release)
$identity += @(Get-FileHash -Algorithm SHA256 -LiteralPath $release, $java, $javac, $jar |
    ForEach-Object { "SHA256 $($_.Hash.ToLowerInvariant()) $($_.Path)" })
[System.IO.File]::WriteAllLines((Join-Path $runRoot 'jdk-identity.txt'), [string[]]$identity, $utf8)
$sourceHashes = @(Get-FileHash -Algorithm SHA256 -LiteralPath ($sources + @($testSource, $PSCommandPath)) |
    ForEach-Object { "SHA256 $($_.Hash.ToLowerInvariant()) $($_.Path)" })
[System.IO.File]::WriteAllLines((Join-Path $runRoot 'source-sha256.txt'), [string[]]$sourceHashes, $utf8)
Write-Output "Evidence directory: $runRoot"
& $javac --release 21 -encoding UTF-8 -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Risk gateway compilation failed: $LASTEXITCODE" }
$package = Join-Path $runRoot 'risk-gateway.jar'
& $jar --create --file $package -C $classes .
if ($LASTEXITCODE -ne 0) { throw "Risk gateway packaging failed: $LASTEXITCODE" }
$packageHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $package).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText((Join-Path $runRoot 'package-sha256.txt'), "$packageHash  risk-gateway.jar`n", $utf8)
& $javac --release 21 -encoding UTF-8 -cp $package -d $tests $testSource
if ($LASTEXITCODE -ne 0) { throw "Focused test compilation failed: $LASTEXITCODE" }
$classPath = $package + [System.IO.Path]::PathSeparator + $tests
& $java -ea -Xmx768m -cp $classPath com.mscient.intrapaper.riskgateway.RiskGatewayDurabilityTest (Join-Path $runRoot 'fixtures') 2>&1 |
    Tee-Object -FilePath (Join-Path $runRoot 'checks.txt')
if ($LASTEXITCODE -ne 0) { throw "Risk gateway checks failed: $LASTEXITCODE. Evidence: $runRoot" }
Write-Output "Package SHA-256: $packageHash"
Write-Output "Offline evidence retained: $runRoot"
