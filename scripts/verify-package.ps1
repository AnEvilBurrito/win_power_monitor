param([string]$Zip = "$PSScriptRoot\..\dist\wpm-v0.1.0-windows-x64.zip")
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Zip = (Resolve-Path $Zip).Path
$expectedHash = ((Get-Content "$Zip.sha256" -Raw).Trim() -split '\s+')[0]
if ((Get-FileHash $Zip -Algorithm SHA256).Hash -ne $expectedHash) { throw 'ZIP checksum mismatch' }
$archive = [IO.Compression.ZipFile]::OpenRead($Zip)
try {
    $names = @($archive.Entries.FullName | Sort-Object)
    $expected = @('LICENSE', 'README.md', 'SHA256SUMS.txt', 'capture-reference.ps1', 'docs/promo.png', 'wpm.exe') | Sort-Object
    if (($names -join '|') -cne ($expected -join '|')) { throw "Unexpected archive entries: $names" }
    function Read-Entry([string]$Name) {
        $stream = $archive.GetEntry($Name).Open()
        $memory = [IO.MemoryStream]::new()
        try { $stream.CopyTo($memory); return ,$memory.ToArray() } finally { $stream.Dispose(); $memory.Dispose() }
    }
    $exe = Read-Entry 'wpm.exe'
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $actual = [Convert]::ToHexString($sha.ComputeHash($exe)).ToLowerInvariant() } finally { $sha.Dispose() }
    $sums = [Text.Encoding]::ASCII.GetString((Read-Entry 'SHA256SUMS.txt')).Trim()
    if ($sums -cne "$actual  wpm.exe") { throw 'EXE checksum mismatch' }
    function U16([int]$Offset) { [BitConverter]::ToUInt16($exe, $Offset) }
    function U32([int]$Offset) { [BitConverter]::ToUInt32($exe, $Offset) }
    $pe = U32 0x3c
    if ((U32 $pe) -ne 0x4550 -or (U16 ($pe + 4)) -ne 0x8664) { throw 'Not an x64 PE' }
    $optional = $pe + 24
    if ((U16 $optional) -ne 0x20b) { throw 'Not PE32+' }
    # Security directory must be empty: these assets are deliberately UNSIGNED.
    if ((U32 ($optional + 144)) -ne 0 -or (U32 ($optional + 148)) -ne 0) { throw 'Unexpected Authenticode signature' }
    $sections = $optional + (U16 ($pe + 20))
    function Rva-Offset([uint32]$Rva) {
        for ($i = 0; $i -lt (U16 ($pe + 6)); $i++) {
            $section = $sections + 40 * $i
            $va = U32 ($section + 12)
            $size = [Math]::Max((U32 ($section + 8)), (U32 ($section + 16)))
            if ($Rva -ge $va -and $Rva -lt $va + $size) { return [int]($Rva - $va + (U32 ($section + 20))) }
        }
        throw "Unmapped RVA $Rva"
    }
    $imports = @()
    $descriptor = Rva-Offset (U32 ($optional + 120))
    while ((U32 ($descriptor + 12)) -ne 0) {
        $offset = Rva-Offset (U32 ($descriptor + 12))
        $end = $offset
        while ($exe[$end] -ne 0) { $end++ }
        $imports += [Text.Encoding]::ASCII.GetString($exe, $offset, $end - $offset)
        $descriptor += 20
    }
    if ($imports | Where-Object { $_ -match '^(vcruntime|msvcp|msvcr|ucrtbase)|^api-ms-win-crt-' }) { throw "Dynamic CRT dependency: $imports" }
    $text = [Text.Encoding]::ASCII.GetString($exe)
    if ($text -notmatch 'requestedExecutionLevel\s+level=["'']asInvoker["'']\s+uiAccess=["'']false["'']') { throw 'Missing embedded asInvoker manifest' }
    Write-Output "Verified: x64, unsigned, asInvoker, static CRT imports, archive allowlist and checksums. Imports: $($imports -join ', ')"
} finally { $archive.Dispose() }
