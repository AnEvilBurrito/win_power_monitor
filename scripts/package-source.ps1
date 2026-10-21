#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$Ref = 'HEAD',
    [switch]$SkipBuildCheck,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$target = 'x86_64-pc-windows-msvc'
$stage = $null
$oldTargetDir = [Environment]::GetEnvironmentVariable('CARGO_TARGET_DIR', 'Process')

function Invoke-Checked {
    param([string]$Command, [string[]]$Arguments)
    $result = & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Command failed (exit $LASTEXITCODE): $($Arguments -join ' ')" }
    return $result
}

Push-Location $root
try {
    $revision = (Invoke-Checked git @('rev-parse', '--verify', '--end-of-options', "$Ref^{commit}")).Trim()
    $tracked = @(Invoke-Checked git @('ls-tree', '-r', '--name-only', $revision))
    foreach ($path in $tracked) {
        if ($path -match '(^|/)(\.git|\.test-uses|target|dist)(/|$)|(^|/)(\.env(?:\..*)?|credentials(?:\..*)?|id_rsa|id_ed25519|[^/]+\.(?:pem|pfx|p12|key))$') {
            throw "Ref contains a private/build path; refusing to package: $path"
        }
    }
    if (@(Invoke-Checked git @('ls-tree', '-r', $revision)) -match '^(120000|160000) ') {
        throw 'Symlinks and submodules require explicit source-release handling.'
    }
    $dist = Join-Path $root 'dist'
    New-Item -ItemType Directory -Force $dist | Out-Null
    if ((Get-Item -LiteralPath $dist).Attributes -band [IO.FileAttributes]::ReparsePoint) {
        throw 'dist must not be a link.'
    }
    $stage = Join-Path $dist ('.source-stage-' + [guid]::NewGuid().ToString('N'))
    $source = Join-Path $stage 'source'
    $env:CARGO_TARGET_DIR = Join-Path $stage 'build'
    New-Item -ItemType Directory $source -Force | Out-Null
    $export = Join-Path $stage 'tracked.zip'
    Invoke-Checked git @('archive', '--format=zip', "--output=$export", $revision)
    [IO.Compression.ZipFile]::ExtractToDirectory($export, $source)
    Push-Location $source
    try {
        if (!(Test-Path -LiteralPath 'Cargo.lock') -or !(Test-Path -LiteralPath 'LICENSE')) {
            throw 'The selected ref must include Cargo.lock and LICENSE.'
        }
        if (Test-Path -LiteralPath 'vendor') { throw 'The selected ref already contains vendor; refusing to merge sources.' }
        $vendorConfig = @(Invoke-Checked cargo @('vendor', '--locked', '--versioned-dirs', 'vendor')) -join "`n"
        if ($vendorConfig -notmatch '(?m)^\[source\.' -or $vendorConfig -notmatch 'directory = "vendor"') {
            throw 'Unexpected cargo vendor configuration.'
        }
        New-Item -ItemType Directory -Force '.cargo' | Out-Null
        $configPath = Join-Path $source '.cargo\config.toml'
        if (Test-Path -LiteralPath '.cargo\config') { throw 'Legacy .cargo/config requires manual configuration merging.' }
        $config = if (Test-Path -LiteralPath $configPath) { [IO.File]::ReadAllText($configPath) } else { '' }
        if ($config -match '(?m)^\s*\[\s*source[.\]]') { throw 'Existing Cargo source configuration requires manual merging.' }
        [IO.File]::WriteAllText($configPath, $config.TrimEnd() + "`n`n" + $vendorConfig + "`n")
        $metadata = (Invoke-Checked cargo @('metadata', '--offline', '--locked', '--no-deps', '--format-version', '1')) | ConvertFrom-Json
        $package = @($metadata.packages | Where-Object name -EQ 'wpm-cli')
        if ($package.Count -ne 1) { throw 'Expected exactly one wpm-cli package in the selected ref.' }
        $version = $package[0].version
        if ($version -notmatch '^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$') { throw 'Unsafe package version.' }
        $name = "wpm-v$version-source.zip"
        $output = Join-Path $dist $name
        foreach ($destination in @($output, "$output.sha256")) {
            if (Test-Path -LiteralPath $destination) {
                $item = Get-Item -LiteralPath $destination -Force
                if (!$Force) { throw "Output exists: $destination. Use -Force to replace only the named source assets." }
                if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                    throw "Output must be a regular file: $destination"
                }
            }
        }
        $rustVersion = (Invoke-Checked rustc @('-Vv')) -join "`n"
        $cargoVersion = Invoke-Checked cargo @('-V')
        $check = if ($SkipBuildCheck) { 'SKIPPED explicitly with -SkipBuildCheck; not build-verified.' } else { 'Verified from the extracted ZIP with the offline command below; no executable was run.' }
        @"
# Corresponding Source build

Package: wpm-cli $version
Requested Git ref: $Ref
Exact Git commit: $revision

This is the tracked content exported by git archive from that commit, plus
vendored locked dependencies, merged Cargo configuration and these build records.
Uncommitted files are never copied. Scripts added after the selected commit
(including package-source.ps1) are intentionally absent: this is an exact-ref
export, not a copy of the current working tree. Git archive export attributes apply.

## Licenses and dependency notices

The project is GPL-3.0-only; see LICENSE. Dependency sources and their individual
license, copyright and notice files are retained under vendor. Cargo.lock records
resolved versions. Preserve these materials when redistributing. Vendoring is not
a legal-compliance audit; review dependency redistribution obligations before release.
Publish this matching source alongside the binary from the same immutable commit.

## Offline Windows x64 build

Install Rust/Cargo with the x86_64-pc-windows-msvc target, Visual Studio C++ Build
Tools and the Windows SDK first (these tools are not bundled). See
BUILD-TOOLCHAIN.txt for the versions used. Extract the entire ZIP, including .cargo
and vendor, into a fresh directory and run there using PowerShell 7:

``````powershell
cargo build --offline --locked --release --target x86_64-pc-windows-msvc
``````

The merged .cargo/config.toml preserves the selected ref's static CRT settings
and uses the bundled vendor directory. No network is needed for dependency
resolution. Result: target\x86_64-pc-windows-msvc\release\wpm.exe.
Build check: $check
"@ | Set-Content -LiteralPath 'SOURCE-BUILD.md' -Encoding utf8NoBOM
        @"
Git commit: $revision
Target: $target
$rustVersion
$cargoVersion
PowerShell: $($PSVersionTable.PSVersion)
Visual Studio C++ Build Tools and Windows SDK: installed host tooling (not bundled).
Build command: cargo build --offline --locked --release --target $target
Build check: $check
"@ | Set-Content -LiteralPath 'BUILD-TOOLCHAIN.txt' -Encoding utf8NoBOM
    } finally { Pop-Location }

    $files = [Collections.Generic.List[string]]::new()
    foreach ($file in Get-ChildItem -LiteralPath $source -File -Recurse -Force) {
        if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw "Linked file: $($file.FullName)" }
        $relative = [IO.Path]::GetRelativePath($source, $file.FullName).Replace('\', '/')
        if ($relative -match '^target(/|$)|(^|/)(\.git|\.test-uses)(/|$)') { throw "Forbidden archive entry: $relative" }
        $files.Add($relative)
    }
    $files.Sort([StringComparer]::Ordinal)
    $zipPath = Join-Path $stage $name
    $zip = [IO.Compression.ZipFile]::Open($zipPath, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($relative in $files) {
            $entry = $zip.CreateEntry($relative, [IO.Compression.CompressionLevel]::Optimal)
            $entry.LastWriteTime = [DateTimeOffset]::new(2026, 1, 1, 0, 0, 0, [TimeSpan]::Zero)
            $inputStream = [IO.File]::OpenRead((Join-Path $source $relative))
            $outputStream = $entry.Open()
            try { $inputStream.CopyTo($outputStream) } finally { $inputStream.Dispose(); $outputStream.Dispose() }
        }
    } finally { $zip.Dispose() }
    $verify = Join-Path $stage 'verify'
    $zip = [IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        if ($zip.Entries.Count -ne $files.Count) { throw 'ZIP membership count mismatch.' }
        for ($i = 0; $i -lt $files.Count; $i++) {
            if ($zip.Entries[$i].FullName -cne $files[$i]) { throw 'ZIP membership/order mismatch.' }
        }
    } finally { $zip.Dispose() }
    [IO.Compression.ZipFile]::ExtractToDirectory($zipPath, $verify)
    foreach ($relative in $files) {
        $expected = (Get-FileHash -LiteralPath (Join-Path $source $relative) -Algorithm SHA256).Hash
        $actual = (Get-FileHash -LiteralPath (Join-Path $verify $relative) -Algorithm SHA256).Hash
        if ($actual -cne $expected) { throw "ZIP content mismatch: $relative" }
    }
    if (!$SkipBuildCheck) {
        $env:CARGO_TARGET_DIR = Join-Path $stage 'build'
        Push-Location $verify
        try {
            Invoke-Checked cargo @('build', '--offline', '--locked', '--release', '--target', $target, '--target-dir', $env:CARGO_TARGET_DIR)
        } finally { Pop-Location }
    }
    $hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $checksumPath = "$zipPath.sha256"
    "$hash  $name" | Set-Content -LiteralPath $checksumPath -Encoding ascii
    if ((Get-Content -LiteralPath $checksumPath) -cne "$hash  $name") { throw 'Checksum verification failed.' }
    Move-Item -LiteralPath $zipPath -Destination $output -Force:$Force
    Move-Item -LiteralPath $checksumPath -Destination "$output.sha256" -Force:$Force
    Write-Output "Source ref: $Ref ($revision)"
    Write-Output "ZIP membership and SHA256 verified: $($files.Count) files"
    Write-Output "Build check: $check"
    Write-Output "Source archive: $output ($((Get-Item -LiteralPath $output).Length) bytes)"
    Write-Output "SHA256: $hash"
    Write-Output "Checksum: $output.sha256"
    Write-Output 'Nothing published.'
} finally {
    [Environment]::SetEnvironmentVariable('CARGO_TARGET_DIR', $oldTargetDir, 'Process')
    Pop-Location
    if ($stage -and (Test-Path -LiteralPath $stage)) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
