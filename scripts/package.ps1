param([string]$Target = 'x86_64-pc-windows-msvc')
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    cargo build --locked --release --target $Target
    if ($LASTEXITCODE -ne 0) { throw 'cargo build failed' }
    $package = Join-Path $root 'dist\wpm-windows-x64'
    New-Item -ItemType Directory -Force $package | Out-Null
    Copy-Item "target\$Target\release\wpm.exe" "$package\wpm.exe" -Force
    Copy-Item README.md "$package\README.md" -Force
    Copy-Item scripts\capture-reference.ps1 "$package\capture-reference.ps1" -Force
    $hash = (Get-FileHash "$package\wpm.exe" -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  wpm.exe" | Set-Content "$package\SHA256SUMS.txt" -Encoding ascii
    $zip = Join-Path $root 'dist\wpm-windows-x64.zip'
    Compress-Archive -Path "$package\*" -DestinationPath $zip -Force
    $zipHash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
    "$zipHash  wpm-windows-x64.zip" | Set-Content 'dist\wpm-windows-x64.zip.sha256' -Encoding ascii
    Write-Output "Package: $zip"
    Write-Output "SHA256: $zipHash"
} finally { Pop-Location }
