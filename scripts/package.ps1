param([ValidateSet('x86_64-pc-windows-msvc')][string]$Target = 'x86_64-pc-windows-msvc')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    $version = (cargo metadata --no-deps --format-version 1 | ConvertFrom-Json).packages[0].version
    if ($LASTEXITCODE -ne 0) { throw 'cargo metadata failed' }
    cargo build --locked --release --target $Target
    if ($LASTEXITCODE -ne 0) { throw 'cargo build failed' }
    $name = "wpm-v$version-windows-x64"
    $package = Join-Path $root "dist\$name"
    if (Test-Path $package) { Get-ChildItem -LiteralPath $package -Force | Remove-Item -Recurse -Force }
    New-Item -ItemType Directory -Force $package | Out-Null
    Copy-Item "target\$Target\release\wpm.exe" "$package\wpm.exe"
    Copy-Item README.md "$package\README.md"
    Copy-Item LICENSE "$package\LICENSE"
    New-Item -ItemType Directory -Force "$package\docs" | Out-Null
    Copy-Item docs\promo.png "$package\docs\"
    $hash = (Get-FileHash "$package\wpm.exe" -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  wpm.exe" | Set-Content "$package\SHA256SUMS.txt" -Encoding ascii
    $zip = Join-Path $root "dist\$name.zip"
    if (Test-Path $zip) { Remove-Item $zip }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::Open($zip, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in @('LICENSE', 'README.md', 'SHA256SUMS.txt', 'docs/promo.png', 'wpm.exe')) {
            $entry = $archive.CreateEntry($file)
            $entry.LastWriteTime = [DateTimeOffset]::new(2026, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
            $inputStream = [IO.File]::OpenRead("$package\$file")
            $outputStream = $entry.Open()
            try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
        }
    } finally { $archive.Dispose() }
    $zipHash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    "$zipHash  $name.zip" | Set-Content "$zip.sha256" -Encoding ascii
    & "$PSScriptRoot\verify-package.ps1" -Zip $zip
    Write-Output "UNSIGNED GPL-3.0-only candidate: $zip"
    Write-Output 'Before distribution provide matching GPLv3 Corresponding Source. Nothing published.'
} finally { Pop-Location }
