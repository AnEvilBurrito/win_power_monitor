# WinGet submission - manual user steps

**User-run checklist, not agent-executable instructions.** The agent must not run
these commands, launch browser/device-login flows, or wait for interactive input.
Use this document only to present the relevant handoff to the user. Tool
installation is outside this workflow.

Replace `<VERSION>`, `<PackageIdentifier>`, and manifest paths with the values
verified during preparation. Run PowerShell examples in the approved Windows
Sandbox/test account where possible. Report commands, results, and limitations
back to the agent; do not share tokens or credentials.

## Commands and interactions requiring a handoff

| Action | Why the user must do it |
| --- | --- |
| `gh auth login`, WingetCreate sign-in, browser/device authorization | Interactive authentication; may wait indefinitely for input |
| `winget settings --enable LocalManifestFiles` | Requires an administrator PowerShell and changes a WinGet setting |
| Accepting WinGet source/package agreements | Requires the user's consent; the agent must not silently accept terms |
| Starting/configuring Windows Sandbox or opening a fresh terminal | Requires an interactive user environment |
| `winget install`, `winget uninstall`, and upgrade lifecycle tests | Mutate the installation; may encounter prompts, UAC, agreements, or running processes |
| `wpm` without one-shot flags | Opens the interactive dashboard/tray and runs until the user exits |
| `wingetcreate submit` | Can prompt for authentication/confirmation and opens an external PR/browser |
| Signing Microsoft's contributor agreement | Legal consent and browser interaction |
| `winget source update` | May require source-agreement interaction; catalog refresh is user-owned here |

The agent can prepare/validate manifests and inspect release/PR/catalog metadata
noninteractively. If authentication or source terms are missing, it stops before
these manual steps instead of attempting them to see whether a prompt appears.

## 1. Authentication or source setup, only if needed

Authenticate GitHub/WingetCreate in your own terminal/browser when requested. Do
not put access tokens directly into command lines or paste them into chat.
Review source agreements yourself if WinGet requests them. Do not bypass an
organization's security policy or disable security protections.

## 2. Enable local manifest tests - ELEVATED, USER ONLY

If local manifests are not already enabled, run once in an **administrator
PowerShell**, subject to organizational policy:

```powershell
winget settings --enable LocalManifestFiles
```

Close the elevated shell. Run the remaining lifecycle tests as a normal user.
If policy blocks the setting, report that limitation; do not try to override it.

## 3. Install and inspect - USER ONLY

Use a clean test account/Sandbox rather than replacing an existing installation.
In a normal PowerShell, from the checkout containing the prepared manifests:

```powershell
winget install --manifest .\packaging\winget\<VERSION> --scope user
```

Review any requested agreements. Open a fresh PowerShell so PATH changes are
visible, then run:

```powershell
Get-Command wpm -All | Format-List Name,Source
wpm --version
wpm --once
winget list --name "Windows Power Monitor" --exact
```

Before catalog publication, a local-manifest install can be listed under an ARP
fallback ID, for example `ARP\User\X64\AnEvilBurrito.wpm__DefaultSource`, rather
than `<PackageIdentifier>`. An exact package-ID query can therefore miss an
installed test package. Use its exact display name or the actual ID returned by
`winget list`; do not reinstall merely because the intended catalog ID is absent.

Check that `wpm` resolves to the WinGet-managed executable rather than a manual
copy, that the version is correct, and that README/LICENSE remain in the installed
package. Unavailable telemetry on unsupported hardware is not an install failure.

### Interactive dashboard test - USER ONLY

```powershell
wpm
```

Check dashboard/tray behavior and exit normally (`q` for the dashboard; close any
remaining tray process). The agent must not launch this long-running command or
kill your processes to proceed. A desktop test is not proof of laptop telemetry.

## 4. Removal, reinstall, and upgrade - USER ONLY

After closing WPM/tray, remove the test installation:

```powershell
winget uninstall --name "Windows Power Monitor" --exact
```

If the name is ambiguous, inspect the listed entries and use the exact installed
ID for this test package with `--id` instead; do not remove an unrelated copy.

Verify removal of the managed command/package and preservation of pre-existing
`%APPDATA%\wpm` configuration. Do not remove unrelated manual installations or
configuration. Reinstall using the prepared manifest to check repeatability.

If an older version is available, use verified older-version manifests or an
existing older catalog package, then install the new local manifest and verify
upgrade/version/configuration behavior. Keep older test manifests out of the new
version's submission. Record behavior with WPM running without forcibly closing
unrelated processes. Report skipped tests and failures accurately.

## 5. Submit the reviewed manifest set - INTERACTIVE, USER ONLY

After agent preparation/validation and review of the test report, submit from
your own terminal:

```powershell
wingetcreate submit --prtitle "New package: <PackageIdentifier> version <VERSION>" .\packaging\winget\<VERSION>
```

For an established package use title `New version: <PackageIdentifier> version
<VERSION>`. Follow sign-in/confirmation prompts yourself. Do not pass `--token`
on the command line or use `--replace` to remove historical versions.
WingetCreate handles upstream placement without requiring a local full clone of
`microsoft/winget-pkgs`. Complete Microsoft's contributor agreement yourself if
requested. Routine manifest submissions do not require a separate issue.

Return the PR URL to the agent for read-only verification of the manifest paths,
version, metadata, checklist, and checks. If submission partly fails, report what
exists before retrying; do not create duplicate PRs or remove remote objects blindly.
Submission is not merge or catalog publication.

## 6. Refresh and test the published catalog package - USER ONLY

After merge, refresh the catalog if needed:

```powershell
winget source update --name winget
```

The agent can then inspect the intended version using noninteractive `winget
show`. Once catalog visibility is confirmed, a final installation test is:

```powershell
winget install --id <PackageIdentifier> --exact --version <VERSION> --source winget --scope user
```

Report the outcome. Catalog propagation may lag merge; do not open another PR
because the version is not immediately visible. README publication claims should
only change after the intended catalog version is confirmed available.
