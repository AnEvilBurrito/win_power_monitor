#Requires -Version 5.1
<#
.SYNOPSIS
Generate the standard Gradle 8.13 wrapper after installing JDK 17/21 and the SDK.
Run from the repository root: powershell -File .\android\setup-gradle.ps1
Downloads only from Gradle's official distribution URL and verifies SHA-256.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$version = '8.13'
$sha256 = '20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78'
$url = "https://services.gradle.org/distributions/gradle-$version-bin.zip"

try {
    # Check Java before creating caches or accessing the network.
    if ($env:JAVA_HOME) {
        $java = Join-Path ($env:JAVA_HOME.Trim().Trim('"')) 'bin\java.exe'
    } else {
        $java = (Get-Command java.exe -ErrorAction Stop).Source
    }
    if (-not (Test-Path -LiteralPath $java -PathType Leaf)) {
        throw "Java not found at '$java'. Set JAVA_HOME to a JDK 17 or 21 installation."
    }
    $javaHome = Split-Path (Split-Path $java -Parent) -Parent
    if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\javac.exe'))) {
        throw 'A full JDK 17 or 21 is required, not a JRE. Set JAVA_HOME to its root.'
    }
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $java
    $info.Arguments = '-version'
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.CreateNoWindow = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(15000)) {
            $process.Kill()
            $process.WaitForExit()
            throw 'java -version timed out.'
        }
        $javaVersion = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0 -or $javaVersion -notmatch 'version "(17|21)[.\-+\"]') {
            throw "Use JDK 17 or 21 for Gradle $version; set JAVA_HOME and retry. Detected: $($javaVersion.Trim())"
        }
    } finally {
        $process.Dispose()
    }
    $env:JAVA_HOME = $javaHome
    Write-Host "Using JDK: $javaHome"

    $sdkConfigured = ($env:ANDROID_HOME -and (Test-Path -LiteralPath $env:ANDROID_HOME)) -or
        (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'local.properties'))
    if (-not $sdkConfigured) {
        throw 'Install the Android SDK and set ANDROID_HOME (or android/local.properties) before running this helper. See android/README.md.'
    }

    $gradleHome = $env:GRADLE_USER_HOME
    if (-not $gradleHome) { $gradleHome = Join-Path $env:USERPROFILE '.gradle' }
    $cache = Join-Path $gradleHome 'wpm-bootstrap'
    [void](New-Item -ItemType Directory -Path $cache -Force)
    $archive = Join-Path $cache "gradle-$version-bin.zip"
    $extracted = Join-Path $cache "gradle-$version"
    $gradle = Join-Path $extracted 'bin\gradle.bat'

    if (Test-Path -LiteralPath $archive) {
        if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha256) {
            throw "Cached Gradle archive checksum mismatch. Remove '$archive' and '$extracted', then retry."
        }
    } else {
        $download = "$archive.download"
        try {
            [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
            Write-Host "Downloading $url"
            $client = New-Object Net.WebClient
            try { $client.DownloadFile($url, $download) } finally { $client.Dispose() }
            if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash.ToLowerInvariant() -ne $sha256) {
                throw 'Downloaded Gradle archive checksum mismatch; nothing was extracted.'
            }
            Move-Item -LiteralPath $download -Destination $archive -Force
        } finally {
            if (Test-Path -LiteralPath $download) { Remove-Item -LiteralPath $download -Force }
        }
    }
    # Always extract from the verified archive rather than trusting an old tree.
    if (Test-Path -LiteralPath $extracted) { Remove-Item -LiteralPath $extracted -Recurse -Force }
    Expand-Archive -LiteralPath $archive -DestinationPath $cache -Force
    & $gradle --no-daemon --project-dir $PSScriptRoot wrapper --gradle-version $version --distribution-type bin --gradle-distribution-sha256-sum $sha256 --network-timeout 10000
    if ($LASTEXITCODE -ne 0) { throw "Gradle wrapper generation failed (exit $LASTEXITCODE). See the error above." }
    foreach ($file in @('gradlew', 'gradlew.bat', 'gradle\wrapper\gradle-wrapper.jar')) {
        if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot $file))) {
            throw "Gradle did not generate $file."
        }
    }
    $properties = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'gradle\wrapper\gradle-wrapper.properties') -Raw
    if (-not $properties.Contains("distributionSha256Sum=$sha256")) {
        throw 'Generated wrapper properties are missing the pinned distribution checksum.'
    }
    Write-Host 'Wrapper ready. Next: Set-Location android; .\gradlew.bat :app:assembleDebug :app:lintDebug'
    exit 0
} catch {
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}
