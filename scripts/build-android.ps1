#requires -Version 7.0
<#
.SYNOPSIS
    Runs the Android release Gradle tasks of this repository from any directory.

.DESCRIPTION
    Invokes the checksummed Gradle wrapper inside android/ (gradlew.bat on Windows,
    gradlew on Linux/macOS). The caller's current directory is never changed: the
    child Gradle process is configured through
    System.Diagnostics.ProcessStartInfo.WorkingDirectory = <repo>/android, so the
    tool/agent cwd, $PWD and the interactive shell location stay untouched.

    Environment prerequisites (inherited from the calling process; nothing is
    hardcoded and no personal paths are stored in this script):
      * JAVA_HOME must point at a JDK 17 or 21 installation (Gradle 8.13 / AGP
        requirement). A fresh terminal does not inherit Android Studio's Gradle JDK
        selection, so set it for the session first, for example:
              $env:JAVA_HOME = 'C:\path\to\jdk-21'    # Windows PowerShell
              export JAVA_HOME="$HOME/jdk-21"         # Linux/macOS
        This script never sets, overrides or persists JAVA_HOME; the child process
        simply inherits it. If it is missing, Gradle falls back to `java` on PATH
        and fails when that is absent or too old.
      * android/local.properties must locate the Android SDK. ANDROID_HOME is not
        required when that file exists.

    Every invocation passes --no-daemon --console=plain --no-configuration-cache so
    runs are non-interactive, leave no background daemon and never reuse a stale
    configuration cache. --write-locks is appended only when requested with
    -WriteLocks.

.PARAMETER Tasks
    Gradle task names or task paths. Defaults to testReleaseUnitTest, lintRelease
    and assembleRelease. Each token is validated against a
    simple Gradle identifier pattern (letters, digits, '.', '_', '-' and ':'), which
    also keeps the Windows cmd.exe wrapper free of quoting/injection surprises.
    Unqualified names resolve like any Gradle task name; use ':app:<task>' to be
    explicit.

.PARAMETER WriteLocks
    Append --write-locks so Gradle records dependency locking state for the locked
    release runtime configuration (updates the tracked gradle.lockfile). Omit it
    unless lock state is intentionally being refreshed; read-only runs must not
    rewrite it.

.EXAMPLE
    pwsh ./scripts/build-android.ps1

.EXAMPLE
    pwsh ./scripts/build-android.ps1 -Tasks :app:testReleaseUnitTest -WriteLocks

.OUTPUTS
    None. The script exits with the Gradle child process exit code.
#>
[CmdletBinding()]
param(
    [string[]]$Tasks = @(
        'testReleaseUnitTest',
        'lintRelease',
        'assembleRelease'
    ),
    [switch]$WriteLocks
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Task tokens are restricted to plain Gradle identifiers. This rejects option
# injection ("-x", "--init-script"), whitespace, quotes and shell metacharacters
# before anything reaches cmd.exe or the wrapper.
$taskTokenPattern = '^:?[A-Za-z0-9_][A-Za-z0-9_.:-]*$'

$repoRoot = Split-Path -Parent $PSScriptRoot
$androidDir = Join-Path $repoRoot 'android'
if (-not (Test-Path -LiteralPath $androidDir -PathType Container)) {
    throw "Missing Android project directory: $androidDir"
}

if ($null -eq $Tasks -or $Tasks.Count -eq 0) {
    throw 'At least one Gradle task is required.'
}
$validatedTasks = [System.Collections.Generic.List[string]]::new()
foreach ($requested in $Tasks) {
    if ([string]::IsNullOrWhiteSpace($requested)) {
        throw 'Gradle task tokens must not be empty.'
    }
    $token = $requested.Trim()
    if ($token -notmatch $taskTokenPattern) {
        throw "Refusing unsafe Gradle task token '$requested': expected a simple task path such as ':app:assembleRelease'."
    }
    $validatedTasks.Add($token)
}

$gradleArguments = [System.Collections.Generic.List[string]]::new()
$gradleArguments.Add('--no-daemon')
$gradleArguments.Add('--console=plain')
$gradleArguments.Add('--no-configuration-cache')
if ($WriteLocks) {
    $gradleArguments.Add('--write-locks')
}
foreach ($token in $validatedTasks) {
    $gradleArguments.Add($token)
}

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    Write-Warning 'JAVA_HOME is not set for this process. Gradle needs JDK 17/21: set JAVA_HOME for the session (for example $env:JAVA_HOME = ''C:\path\to\jdk-21'') or ensure a suitable java is on PATH.'
}

# WorkingDirectory (not Set-Location/Push-Location) selects the Gradle project
# root for the child only; the caller's cwd is unchanged.
$startInfo = [System.Diagnostics.ProcessStartInfo]::new()
$startInfo.UseShellExecute = $false
$startInfo.WorkingDirectory = $androidDir

if ($IsWindows) {
    $wrapper = Join-Path $androidDir 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
        throw "Missing Gradle wrapper: $wrapper"
    }
    # gradlew.bat is a batch file, so it must run through the command interpreter.
    # cmd.exe /d ignores AutoRun registry commands (the user's cmd shell setup cannot
    # inject commands) and /c runs the command and returns its exit code. The wrapper
    # is passed as a bare relative name because WorkingDirectory is already android/,
    # and every argument is a validated metacharacter-free token.
    $comspec = if ([string]::IsNullOrWhiteSpace($env:ComSpec)) { 'cmd.exe' } else { $env:ComSpec }
    $startInfo.FileName = $comspec
    $startInfo.ArgumentList.Add('/d')
    $startInfo.ArgumentList.Add('/c')
    $startInfo.ArgumentList.Add('gradlew.bat')
    foreach ($argument in $gradleArguments) {
        $startInfo.ArgumentList.Add($argument)
    }
}
else {
    $wrapper = Join-Path $androidDir 'gradlew'
    if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
        throw "Missing Gradle wrapper: $wrapper"
    }
    # Linux/macOS: the POSIX wrapper is executable directly, no shell needed.
    $startInfo.FileName = $wrapper
    foreach ($argument in $gradleArguments) {
        $startInfo.ArgumentList.Add($argument)
    }
}

Write-Host "Gradle working directory: $androidDir"
Write-Host "Gradle command: $($startInfo.FileName) $($gradleArguments -join ' ')"
Write-Host "Write locks: $([bool]$WriteLocks)"

try {
    $gradle = [System.Diagnostics.Process]::Start($startInfo)
}
catch [System.ComponentModel.Win32Exception] {
    throw "Unable to start the Gradle wrapper '$($startInfo.FileName)': $($_.Exception.Message) On Linux/macOS verify that android/gradlew is executable (chmod +x android/gradlew)."
}

# Output is not redirected: the child inherits the console handles so Gradle
# progress and failures are shown live while this script waits.
$gradle.WaitForExit()
exit $gradle.ExitCode
