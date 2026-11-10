param([Parameter(Mandatory=$true)][string]$OutputRoot)
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
function Get-G7LocalPath([string]$G7Requested) {
    if ([string]::IsNullOrWhiteSpace($G7Requested) -or $G7Requested -match '^(\\\\|//)' -or $G7Requested.Contains('://') -or
        $G7Requested -match '(^|[\\/])\.\.([\\/]|$)') { throw 'Ordinary local paths without traversal are required.' }
    $g7Absolute = [IO.Path]::GetFullPath($G7Requested)
    if ($g7Absolute -notmatch '^[A-Za-z]:\\' -or $g7Absolute.Substring(2).Contains(':')) { throw 'Network/device/alternate-stream paths are forbidden.' }
    $g7Step = $g7Absolute
    while ($g7Step) {
        if (Test-Path -LiteralPath $g7Step) {
            $g7Item = Get-Item -LiteralPath $g7Step -Force
            if (($g7Item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Symlink/junction paths are forbidden.' }
        }
        $g7Step = [IO.Path]::GetDirectoryName($g7Step)
    }
    return $g7Absolute
}
$g7Jdk = Get-G7LocalPath $env:JAVA_HOME
$g7Output = Get-G7LocalPath $OutputRoot
if ([IO.Path]::GetPathRoot($g7Output) -cne [IO.Path]::GetPathRoot($g7Jdk)) { throw 'Use the provisioned local JDK volume.' }
$release = Get-Content -LiteralPath (Join-Path $env:JAVA_HOME 'release')
if ($release -cnotcontains 'IMPLEMENTOR="Microsoft"' -or $release -cnotcontains 'JAVA_VERSION="21.0.13.0.2"') { throw 'Use pinned Microsoft JDK 21.0.13.0.2.' }
$g7HeadBefore = (& git -C $PSScriptRoot rev-parse --verify HEAD | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $g7HeadBefore -notmatch '^[0-9a-f]{40}$') { throw 'Exact local Git revision required.' }
$g7StatusBefore = @(& git -C $PSScriptRoot status --porcelain --untracked-files=normal)
if ($LASTEXITCODE -ne 0) { throw 'Git status unavailable.' }
$g7CommitDate = (& git -C $PSScriptRoot show -s --format=%cI HEAD | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Git commit time unavailable.' }
$run = Join-Path $g7Output ([guid]::NewGuid().ToString('N'))
$classes = Join-Path $run 'classes'
New-Item -ItemType Directory -Path $classes | Out-Null
$utf8 = [Text.UTF8Encoding]::new($false)
function Get-G7Sources {
    $g7Pending = [Collections.Generic.Stack[string]]::new()
    $g7Pending.Push((Join-Path $PSScriptRoot 'src/main/java'))
    $g7Result = [Collections.Generic.List[string]]::new()
    while ($g7Pending.Count -gt 0) {
        $g7Directory = Get-G7LocalPath ($g7Pending.Pop())
        foreach ($g7Entry in Get-ChildItem -LiteralPath $g7Directory -Force) {
            if (($g7Entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Linked source trees are forbidden.' }
            if ($g7Entry.PSIsContainer) { $g7Pending.Push($g7Entry.FullName) }
            elseif ($g7Entry.Extension -ceq '.java') { $g7Result.Add($g7Entry.FullName) }
        }
    }
    $g7Sorted = $g7Result.ToArray()
    [Array]::Sort($g7Sorted, [StringComparer]::Ordinal)
    return $g7Sorted
}
$sources = @(Get-G7Sources)
if ($sources.Count -eq 0) { throw 'No Java sources found.' }
$g7SourceHashes = @($sources | ForEach-Object { $_ + '=' + (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash })
$argFile = Join-Path $run 'sources.args'
[IO.File]::WriteAllLines($argFile, [string[]]@($sources | ForEach-Object { '"' + $_.Replace('\','/') + '"' }), $utf8)
& (Join-Path $g7Jdk 'bin/javac.exe') --release 21 -encoding UTF-8 -proc:none -classpath $classes -d $classes ('@' + $argFile)
if ($LASTEXITCODE -ne 0) { throw 'G7 compilation failed.' }
$commit = if ($g7StatusBefore.Count -eq 0) { $g7HeadBefore } else { 'unknown-dirty' }
$manifest = Join-Path $run 'MANIFEST.MF'
[IO.File]::WriteAllText($manifest, "Manifest-Version: 1.0`r`nMain-Class: com.mscient.intrapaper.ops.governance.G7Cli`r`nBuild-Commit: $commit`r`nImplementation-Version: proposed-g7-v1`r`n`r`n", $utf8)
$jar = Join-Path $run 'g7-operations.jar'
$g7ClassFiles = @(Get-ChildItem -LiteralPath $classes -Recurse -File | ForEach-Object { [IO.Path]::GetRelativePath($classes, $_.FullName).Replace('\','/') })
[Array]::Sort($g7ClassFiles, [StringComparer]::Ordinal)
$g7JarArgs = [Collections.Generic.List[string]]::new()
foreach ($g7Value in @('--create','--file',('"' + $jar.Replace('\','/') + '"'),'--manifest',('"' + $manifest.Replace('\','/') + '"'),'--date',$g7CommitDate)) { $g7JarArgs.Add($g7Value) }
foreach ($g7ClassFile in $g7ClassFiles) { $g7JarArgs.Add('-C'); $g7JarArgs.Add('"' + $classes.Replace('\','/') + '"'); $g7JarArgs.Add('"' + $g7ClassFile + '"') }
$g7JarArgFile = Join-Path $run 'jar.args'
[IO.File]::WriteAllLines($g7JarArgFile, $g7JarArgs.ToArray(), $utf8)
& (Join-Path $g7Jdk 'bin/jar.exe') ('@' + $g7JarArgFile)
if ($LASTEXITCODE -ne 0) { throw 'G7 packaging failed.' }
$g7AfterHashes = @(Get-G7Sources | ForEach-Object { $_ + '=' + (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash })
$g7HeadAfter = (& git -C $PSScriptRoot rev-parse --verify HEAD | Out-String).Trim()
$g7StatusAfter = @(& git -C $PSScriptRoot status --porcelain --untracked-files=normal)
if ($LASTEXITCODE -ne 0 -or $g7HeadBefore -cne $g7HeadAfter -or (Compare-Object $g7SourceHashes $g7AfterHashes) -or
    ($g7StatusBefore -join "`n") -cne ($g7StatusAfter -join "`n")) { throw 'Source/Git inputs changed during build; reject this package.' }
Get-FileHash -LiteralPath $jar -Algorithm SHA256 | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run 'jar-hash.json') -Encoding utf8
Write-Output $jar
