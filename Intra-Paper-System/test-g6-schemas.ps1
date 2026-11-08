param(
    [Parameter(Mandatory = $true)][string]$Dossier,
    [string[]]$ReportDirectories = @()
)
$ErrorActionPreference = 'Stop'
$schemaRoot = Join-Path $PSScriptRoot 'schemas/g6-capacity-review'
$documents = @{}
Get-ChildItem -LiteralPath $schemaRoot -Filter '*.schema.json' -File | ForEach-Object {
    $documents[$_.Name] = Get-Content -LiteralPath $_.FullName -Raw | ConvertFrom-Json -AsHashtable
}
# Resolve only preloaded local schemas. Test-Json never fetches external references.
function Expand-LocalSchema($Value, [string]$Document, [int]$Depth = 0) {
    if ($Depth -gt 80) { throw 'Cyclic/excessive local schema reference depth.' }
    if ($Value -is [System.Collections.IDictionary]) {
        if ($Value.Contains('$ref')) {
            $reference = [string]$Value['$ref']
            if ($reference -notmatch '^(?<file>[a-z-]+\.schema\.json)?(?<pointer>#/.*)?$') { throw "Nonlocal schema reference: $reference" }
            $file = if ($Matches.file) { $Matches.file } else { $Document }
            if (-not $documents.ContainsKey($file)) { throw "Unknown local schema: $file" }
            $target = $documents[$file]
            if ($Matches.pointer) {
                foreach ($part in $Matches.pointer.Substring(2).Split('/')) { $target = $target[$part.Replace('~1','/').Replace('~0','~')] }
            }
            if ($null -eq $target) { throw "Unresolved local schema: $reference" }
            return Expand-LocalSchema $target $file ($Depth + 1)
        }
        $copy = [ordered]@{}
        foreach ($key in $Value.Keys) { $copy[$key] = Expand-LocalSchema $Value[$key] $Document ($Depth + 1) }
        return $copy
    }
    if ($Value -is [System.Collections.IList]) {
        $items = @(); foreach ($item in $Value) { $items += ,(Expand-LocalSchema $item $Document ($Depth + 1)) }
        return ,$items
    }
    return $Value
}
$expanded = @{}
foreach ($name in $documents.Keys) { $expanded[$name] = Expand-LocalSchema $documents[$name] $name | ConvertTo-Json -Depth 100 -Compress }
$checked = 0
foreach ($directory in (@($Dossier) + $ReportDirectories)) {
    foreach ($file in Get-ChildItem -LiteralPath $directory -Filter '*.json' -File -Recurse) {
        $json = Get-Content -LiteralPath $file.FullName -Raw
        $document = $json | ConvertFrom-Json -AsHashtable
        if ($document.schema -notmatch '^g6-(?<kind>[a-z-]+)/v1$') { continue }
        $kind = if ($Matches.kind -eq 'review') { 'report' } else { $Matches.kind }
        $schema = $expanded["$kind.schema.json"]
        if (-not $schema) { throw "Missing schema for $($file.FullName)" }
        if (-not (Test-Json -Json $json -Schema $schema)) { throw "Schema failure: $($file.FullName)" }
        $checked++
    }
}
if ($checked -eq 0) { throw 'No G6 documents were validated.' }
$manifest = Get-Content -LiteralPath (Join-Path $Dossier 'manifest.json') -Raw | ConvertFrom-Json -AsHashtable
$manifest['scale_authorized'] = $true
if (Test-Json -Json ($manifest | ConvertTo-Json -Depth 100) -Schema $expanded['manifest.schema.json'] -ErrorAction SilentlyContinue) { throw 'Schema accepted unknown authority field.' }
Write-Output "PASS offline G6 JSON schemas: $checked documents; unknown authority field rejected. No external references fetched."
