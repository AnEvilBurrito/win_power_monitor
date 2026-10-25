---
name: winget
description: Prepare and validate WinGet manifests for existing win_power_monitor Windows releases, hand off elevated or interactive testing/submission to the user, and inspect PR/catalog status noninteractively. Use when asked to publish or update wpm on winget, prepare manifests, or submit a winget-pkgs PR. Assumes tools are installed.
---

# WinGet community submissions

Work from this repository's root. Use the existing published Windows x64 ZIP;
no MSI, rebuild, new GitHub release, or code signing is required for the portable
submission path. This skill starts after tool installation: assume `winget`,
`wingetcreate`, Git, and authenticated `gh` are available. Check availability and
versions; report missing prerequisites rather than installing tools automatically.
PowerShell examples below are commands for Windows, not Bash syntax.

## Agent execution boundary

This skill's executable workflow is **noninteractive preparation, validation, and
read-only verification only**. Creating/editing the skill does not authorize a
submission. Even when publication is requested, hand off elevated, interactive,
and long-running operations to the user rather than launching them from tools.

- Never run authentication/login flows, change WinGet settings, accept agreements,
  launch a dashboard/browser/Sandbox, install/uninstall/upgrade applications, or
  invoke WingetCreate submission from the agent.
- Do not create/push forks or branches as a workaround for the manual submission
  boundary. Use only existing authentication for read-only GitHub queries.
- Use `--disable-interactivity` on WinGet queries/validation and finite tool
  timeouts. Disable GitHub/Git prompting in the command environment. If credentials,
  agreements, elevation, or user input are required, report the exact blocker and
  stop that phase. Do not retry without noninteractive flags or wait on a prompt.
- Do not expose tokens in commands, output, manifests, commits, or PR descriptions.
  Never terminate user processes, delete configuration, bypass security checks,
  invoke elevation, or accept legal agreements on the user's behalf.

[MANUAL.md](MANUAL.md) is a **user-run handoff checklist, not agent-executable
instructions**. Present only the relevant steps with concrete paths/values, then
return control to the user. Resume verification after they report completion;
never start a tool waiting for their terminal/browser input.

## Conventions

- Source repository: `AnEvilBurrito/win_power_monitor`; confirm `origin`.
- Submission target: `microsoft/winget-pkgs` (not this project's GitHub releases).
- Proposed first package ID: `AnEvilBurrito.wpm`; publisher: `AnEvilBurrito`;
  display name: `Windows Power Monitor`; command alias: `wpm`.
  Confirm these on the first submission. Once listed, preserve the established
  identity and metadata unless the user explicitly approves a migration.
- Version comes from the requested published release, not necessarily the current
  working tree. For example, `v0.1.4` maps to manifest version `0.1.4`.
- Windows asset: `wpm-v<VERSION>-windows-x64.zip`; portable executable at ZIP root:
  `wpm.exe`. Manifest `InstallerSha256` hashes the entire ZIP, not the executable,
  source archive, or an Actions wrapper ZIP.
- Use a stable, official, version-specific download URL. Do not use `/latest/`,
  expiring Actions artifact links, mutable branch URLs, or a local rebuild.
- Keep GPL license/docs in the package and require the release to expose matching
  Corresponding Source. WinGet hosts manifests; our release hosts the binaries.
- Local manifest location: `packaging/winget/<VERSION>/`. This is a convention for
  this skill, not an existing build-pipeline output.
- Upstream path for the proposed ID:
  `manifests/a/AnEvilBurrito/wpm/<VERSION>/`. WingetCreate handles placement.
- One version per upstream PR, manifests only. Do not include this skill, source,
  README changes, binaries, checksum sidecars, or test notes as upstream files.

## 1. Inspect the release and existing submissions

Read the current `scripts/package.ps1`, `scripts/verify-package.ps1`, relevant
README/platform limitations, and any existing local WinGet manifests. Inspect
local changes without staging or overwriting unrelated work. Do not change Cargo
versions just to submit an already published release.

```powershell
git status --short --branch
git remote -v
winget --version
wingetcreate version
$env:GH_PROMPT_DISABLED = '1'
$env:GIT_TERMINAL_PROMPT = '0'
gh auth status
gh release view <tag> --repo AnEvilBurrito/win_power_monitor --json tagName,isDraft,isPrerelease,publishedAt,url,assets
winget search --id <PackageIdentifier> --exact --source winget --disable-interactivity
gh pr list --repo microsoft/winget-pkgs --state open --search "<PackageIdentifier>" --json number,title,url
```

Replace placeholders with approved values before running commands. Inspect older
manifests/catalog versions for an existing package, and search open PRs by project
URL/name as well if its identity is uncertain. Distinguish an expected no-match
exit from authentication, network, or source errors. Do not create a duplicate
submission; resume a known existing PR only with permission.

Require a public, non-draft release with the intended Windows ZIP, its SHA-256
sidecar, and matching source asset. For an ordinary submission prefer a release
with `isPrerelease=false`; if the requested release is a pre-release, stop for an
explicit channel/policy decision. Never promote or edit the GitHub release to
satisfy this skill. If assets do not exist, use the separate `release` skill only
when the user authorizes release work.

Check current upstream guidance before generating manifests; schema versions,
policies, and tool options can change:

- [First-time checklist](https://github.com/microsoft/winget-pkgs/blob/master/doc/FirstContribution.md)
- [Authoring](https://github.com/microsoft/winget-pkgs/blob/master/doc/Authoring.md)
- [Schema versions](https://github.com/microsoft/winget-pkgs/blob/master/doc/manifest/README.md)
- [Portable ZIP fields (1.12.0)](https://github.com/microsoft/winget-pkgs/blob/master/doc/manifest/schema/1.12.0/installer.md)
- [Policies](https://github.com/microsoft/winget-pkgs/blob/master/doc/Policies.md)
- [WingetCreate submit](https://github.com/microsoft/winget-create/blob/main/doc/submit.md)

## 2. Verify the exact published Windows package

Download only the selected release's Windows ZIP and its `.zip.sha256` sidecar
into a fresh workspace-relative directory, for example:

```powershell
gh release download <tag> --repo AnEvilBurrito/win_power_monitor --pattern "wpm-v<VERSION>-windows-x64.zip" --pattern "wpm-v<VERSION>-windows-x64.zip.sha256" --dir dist/winget-input-<VERSION>
Get-FileHash .\dist\winget-input-<VERSION>\wpm-v<VERSION>-windows-x64.zip -Algorithm SHA256
.\scripts\verify-package.ps1 -Zip .\dist\winget-input-<VERSION>\wpm-v<VERSION>-windows-x64.zip
```

Check every exit status. Do not clobber an existing directory or substitute stale
`dist/` files. A previously downloaded ZIP is reusable only after matching its
hash to this release's metadata/sidecar. Require agreement between the local ZIP,
sidecar, GitHub asset digest when available, and final manifest hash. Never copy
an old hash into a new version.

The current verifier checks archive membership, inner executable hash, x64 PE,
unsigned status, embedded `asInvoker` manifest, and absence of dynamic CRT imports.
Inspect it before use: future packaging/signing changes may require a reviewed
verifier update, not bypassing a failed check. Do not repack or strip documentation
from the ZIP. Source-asset presence is not itself a license-compliance audit.

## 3. Prepare the three manifests

Use normal read/edit/write tools to create or update the files. Read any existing
file before modifying it. No singleton manifest. These templates use schema
`1.12.0`, verified when this skill was written; recheck the current supported
schema and update all headers/ManifestVersion fields together if necessary.
Replace every placeholder; derive release date from the release's `publishedAt`
(UTC date), not today's date or the asset download time.

### `<PackageIdentifier>.yaml`

```yaml
# yaml-language-server: $schema=https://aka.ms/winget-manifest.version.1.12.0.schema.json

PackageIdentifier: <PackageIdentifier>
PackageVersion: <VERSION>
DefaultLocale: en-US
ManifestType: version
ManifestVersion: 1.12.0
```

### `<PackageIdentifier>.installer.yaml`

```yaml
# yaml-language-server: $schema=https://aka.ms/winget-manifest.installer.1.12.0.schema.json

PackageIdentifier: <PackageIdentifier>
PackageVersion: <VERSION>
InstallerType: zip
NestedInstallerType: portable
NestedInstallerFiles:
  - RelativeFilePath: wpm.exe
    PortableCommandAlias: wpm
Commands:
  - wpm
ReleaseDate: <YYYY-MM-DD>
Installers:
  - Architecture: x64
    InstallerUrl: https://github.com/AnEvilBurrito/win_power_monitor/releases/download/v<VERSION>/wpm-v<VERSION>-windows-x64.zip
    InstallerSha256: <VERIFIED-ZIP-SHA256>
ManifestType: installer
ManifestVersion: 1.12.0
```

### `<PackageIdentifier>.locale.en-US.yaml`

```yaml
# yaml-language-server: $schema=https://aka.ms/winget-manifest.defaultLocale.1.12.0.schema.json

PackageIdentifier: <PackageIdentifier>
PackageVersion: <VERSION>
PackageLocale: en-US
Publisher: AnEvilBurrito
PublisherUrl: https://github.com/AnEvilBurrito
PublisherSupportUrl: https://github.com/AnEvilBurrito/win_power_monitor/issues
PackageName: Windows Power Monitor
PackageUrl: https://github.com/AnEvilBurrito/win_power_monitor
License: GPL-3.0-only
LicenseUrl: https://github.com/AnEvilBurrito/win_power_monitor/blob/v<VERSION>/LICENSE
ShortDescription: Terminal power dashboard with battery-flow monitoring, live charts, and diagnostics.
Description: |-
  Windows Power Monitor (wpm) displays battery charge and discharge power,
  voltage, current, percentage, and session statistics in a terminal dashboard.
  It includes a numeric Windows tray icon, themes, CSV logging, and JSON exports.
  Battery-flow readings require compatible battery hardware and drivers and
  do not represent whole-system or wall power consumption.
Tags:
  - battery
  - monitoring
  - power
  - terminal
ReleaseNotesUrl: https://github.com/AnEvilBurrito/win_power_monitor/releases/tag/v<VERSION>
ManifestType: defaultLocale
ManifestVersion: 1.12.0
```

Omit `Scope` for ZIP/portable manifests: the checked WinGet client warns that
scope metadata is unsupported for portable installers. Request user scope with
`--scope user` during the manual installation test instead.

Use the actual release tag/asset URL if conventions differ. Metadata must describe
that release, not future features. Do not declare Windows ARM64/x86, system-wide
installation, a minimum Windows build, dependencies, or signing guarantees without
evidence. Current ZIP portable installation needs no EXE silent switches and must
not launch WPM during installation. Preserve current metadata/additional locales
when updating an established package instead of blindly replacing with templates.

Preparation-only requests stop after local validation and a reviewed diff. A
repository commit is separate work governed by `.pi/skills/commit/SKILL.md`; this
workflow does not stage, commit, or push changes.

## 4. Validate noninteractively, then hand off lifecycle tests

```powershell
winget validate --manifest .\packaging\winget\<VERSION> --disable-interactivity
```

Fix errors and understand warnings. Schema validation is not an installation test.
Unsigned ZIP portable packages are not inherently rejected for lacking signing,
but still must pass upstream malware/PUA and installation checks. Never bypass hash
verification, Defender, SmartScreen, organizational policy, or failed validation.

**MANUAL HANDOFF:** local-manifest setting changes require elevation; installation,
removal, upgrades, fresh-terminal/PATH checks, and dashboard/tray checks belong in
the user's test environment. Present the relevant steps from `MANUAL.md` and stop
this phase. Do not run the commands even if the user has approved testing; approval
does not make an interactive/elevated operation safe for this harness.

Resume after the user supplies a test report. Record environment, commands, exit
statuses, and skipped checks, attributing user-reported results accurately. Require
verification of expected version, managed command path, unattended installation,
docs/license retention, removal, and configuration preservation. A local-manifest
install can appear under an `ARP\User\X64\...__DefaultSource` fallback ID before
catalog correlation exists. Inspect it by exact package name or the actual ID
returned by `winget list`; do not treat a missing catalog-ID match as proof that
installation failed. When an older version is available, request upgrade coverage
too. Unavailable battery readings are valid on unsupported hardware; desktop tests
do not prove laptop telemetry.
Resolve reported failures before submission. Clearly document unperformed tests
and get a decision on whether to proceed; never invent test passes.

## 5. Hand off submission, then inspect the PR

Recheck catalog and open PRs immediately before handoff. If this version is already
listed, do not propose resubmission; if there is a known PR, inspect its head/files
before proposing an update. Stop for unknown ownership or conflicting changes.
Require a reviewed manifest set with matching ID/version, no placeholders,
verified hashes, successful validation, and an honest test report.

**MANUAL HANDOFF:** WingetCreate submission can prompt for login/confirmation and
open a browser. Give the user the concrete submission command from `MANUAL.md`,
then stop and request the resulting PR URL. The user handles authentication and
any contributor agreement. Do not invoke submission to test whether cached login
avoids prompts, pass a token on the command line, or create/push a fork as a bypass.

After the user returns the URL, inspect the PR's base repository/branch, head,
title, body, and changed files using noninteractive `gh pr view` and `gh pr diff`.
Require exactly the approved version's manifests under the correct upstream path,
with no binaries or unrelated files. Check that its checklist reflects tests
actually performed. Routine manifest submissions do not need a separate issue.
On partial failure inspect existing remote objects read-only before recommending
another attempt; never blindly create duplicates or delete remote objects.
Submission is not merge or catalog publication.

## 6. Inspect checks and confirm catalog publication

Check the PR with `gh pr checks <number> --repo microsoft/winget-pkgs` and inspect
review comments using bounded, noninteractive queries. Do not use `--watch`, wait
indefinitely for CI/review/merge, or launch a browser. Report pending checks and
return control to the user. Prepare reviewed local fixes if needed; remote updates
remain user-run handoffs. Never weaken checks. If a release asset changed
unexpectedly, investigate rather than refreshing its hash blindly.

After merge, inspect the catalog without prompting:

```powershell
winget show --id <PackageIdentifier> --exact --version <VERSION> --source winget --disable-interactivity
```

Require the intended version, publisher, official download URL, and hash. Catalog
visibility can lag merge. If a refresh, agreement, or login is needed, hand off the
relevant `MANUAL.md` step instead of running it or accepting terms automatically.
Do not open another PR or poll indefinitely because publication is pending.
A final catalog installation test is also user-run; never install from the agent.

Only after catalog availability is confirmed, and repository edits are authorized,
update this project's README from planned distribution to the verified install
command. Keep those edits out of the upstream manifest PR. Do not conflate
experimental macOS support with the Windows listing's readiness.

Report the package ID/version, release URL, verified ZIP hash, local manifest
paths, agent validation and user-reported test results/limitations, PR URL/status,
catalog status, and the exact next manual handoff. Never describe a prepared
manifest, open PR, or merged-but-unpublished version as already installable from
the public source.
