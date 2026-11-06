---
name: android-release
description: Prepare, verify, draft, and publish signed win_power_monitor Android APK pre-releases from GitHub Actions artifacts, with certificate pinning, matching GPLv3 source, and SHA-256 checksums. Use when asked to release Android, build a signed APK candidate, tag an Android version, or publish Android assets. Not for desktop Rust releases.
---

# Signed Android APK pre-releases

Work from the `win_power_monitor` repository root; repository paths below are
root-relative. This skill covers the native Kotlin/Compose app in `android/`,
not Rust desktop binaries, Play Store submission, or Android App Bundles (AAB).
Use the sibling `../release/SKILL.md` skill for desktop releases. Do not change
Cargo versions, desktop release assets, or desktop signing as part of this flow.

Creating/loading this skill does not authorize a release. Confirm the version,
versionCode, application ID, release commit, and requested actions. Preparation
alone does not authorize commits, pushes, workflow dispatch, device installation,
tags, uploads, or publication. Ask only for missing decisions. Never force-push,
move tags, replace assets, rotate signing keys, or promote to stable implicitly.

## 0. Readiness gate: do not mistake a debug build for a release

Current pipeline (inspect the actual files before every release):

- `.github/workflows/android.yml` builds debug APKs only, with the `.debug` app
  ID suffix. It does not consume the release signing secrets.
- `.github/workflows/android-candidate.yml` is manually dispatched. It builds,
  tests and rebuilds the source without keys, then signs in a separate protected
  `android-release` job. It never tags or publishes.
- `scripts/android-candidate.py` binds successful build/source checks to the run
  SHA and hashes, signs using environment credentials, and verifies the APK.
- `scripts/verify-android-apk.py` performs SDK manifest/signature, ZIP and ELF
  checks. `scripts/package-android-source.py` packages committed source plus
  dependency/native sources; `scripts/verify-android-release-assets.py` verifies
  the four downloaded assets and source/build records.
- The release Gradle variant is deliberately unsigned so public source builds
  need no key; protected CI signs it separately. The permanent app ID was
  approved as `io.github.anevilburrito.wpm` for the first release.
- GitHub environment `android-release` is configured. Secret names/screenshots
  do not verify their values; actual signing and the pin check must succeed.

Before dispatch or publication, inspect the actual workflow, Gradle signing
integration or separate signing step, source packager, and Android asset verifier.
**If any required piece is missing or unverified, stop and report the blocker.**
Implement it only when that implementation is authorized; creating this skill
is not permission to implement the pipeline. Do not dispatch `android.yml` or
`candidate.yml` as a substitute, rename a debug APK into a release, or reuse the
desktop verifier, which expects a different asset inventory.

Require the workflow and helpers to satisfy the contracts below. If reviewed
implementation changes paths or names, update this skill to match; never guess
executable commands or flags.

## 1. Identity, versioning, and signing contract

- Repository: `AnEvilBurrito/win_power_monitor`; verify `origin` before mutations.
- Current app ID: `io.github.anevilburrito.wpm`. Record approval of the permanent
  ID before dispatching the first official candidate; do not rename it as a
  side effect of versioning. All verification must use that approved ID.
- Debug builds use `.debug` so future debug/release installs can coexist. Do
  not confuse either with old unsuffixed debug-signed installations.
- Version source: `android/app/build.gradle.kts`. Keep the approved `versionName`
  equal to `<VERSION>`, without a `-dev` suffix on a distributed release.
  Pre-release names such as `0.7.0-rc.1` are valid proposals, not pre-approved.
- `versionCode` is an independent positive integer. Require it to increase over
  every previously distributed Android build in this update stream, including
  pre-releases. Check prior release notes/build records and artifact metadata,
  not just the current file. If no Android releases exist, use the highest
  known distributed development code as the baseline; ask if history is unclear.
- Annotated tag: `android-v<VERSION>`; title: `wpm Android <VERSION> — <summary>`.
  Android versions are independent of Cargo versions and desktop `v*` tags.
- Default to GitHub pre-releases with `--prerelease --latest=false`. Do not
  change the repository's desktop Latest release or historical releases.
- The same app ID, signing identity, and increasing versionCode are required
  for ordinary in-place updates. Existing debug-signed installations with the
  unsuffixed ID will normally need removal before the first release install.
  Warn the user to export important recordings first: uninstalling deletes
  private history/settings. Never uninstall or clear data without approval.

### Protected signing environment

Use the GitHub environment `android-release` only in a trusted signing job.
Confirm its deployment rules allow the approved ref (intended rules: branch
`master`, tags `android-v*`, configured separately). Confirm reviewer protection
or an explicitly user-approved alternative; never disable protection to unblock
an approval wait. Never expose these credentials to PR/debug workflows, fork
code, arbitrary workflow inputs, or an unchecked checkout SHA.

Environment secrets:

| Name | Meaning |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded release PKCS12 keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Expected alias `wpm-release` |
| `ANDROID_KEY_PASSWORD` | Private-key password; same as the store password for the generated PKCS12 |

GitHub environment configuration variable: `vars.ANDROID_SIGNING_CERT_SHA256`.
The workflow must explicitly map it into its verifier/signing checks; it does
not automatically become a process environment variable. Require it to match
this user-approved public certificate pin from the user's `keytool -list -v`
output dated 6 November 2026. The first signed candidate must confirm it using
`apksigner verify --print-certs`. Normalize by removing colons/whitespace and
comparing case-insensitively:

```text
D3D54F63925EAD1F229259CA6C711A403DDCE9D06B7B7C76CDFF699C516EF6CF
```

Require a complete 64-hex fingerprint and a match against the APK's actual
signer. A different fingerprint is a blocker, not permission to update the pin
or generate a replacement key. Key rotation requires a separate approved plan.

Never request passwords or the keystore in chat, print secret values, place
passwords in command arguments, or commit signing material. Disable shell tracing
around secrets. Decode the keystore into a restricted file under `RUNNER_TEMP`,
not the checkout; handle base64 whitespace from Windows safely. Pass passwords
through environment/file mechanisms, and remove temporary signing material even
on failure. Never upload it, include it in source archives, or cache it. Keep
credentials out of Gradle configuration caches and diagnostics. The user keeps
encrypted offline backups; GitHub secrets are not a recoverable backup.

This key signs Android APKs only. It is not a Git/GitHub authentication key or a
Windows Authenticode/macOS Developer ID credential.

## 2. Inspect and prepare the approved release commit

```bash
pwd
git status --short --branch
git remote -v
git log -8 --oneline
git tag --list 'android-v*' --sort=-version:refname
gh auth status
gh release list --repo AnEvilBurrito/win_power_monitor --limit 30
```

Read the current Android Gradle files, `android/README.md`,
`android/specs/live-current.md`, `android/specs/device-acceptance.md`, release
workflow, Android-specific source packager, and verifier. Do not use
`scripts/package-source.ps1` as the Android packager: it is Cargo-oriented.
Inspect changes since the previous Android tag. Do not infer Android versions
or notes from desktop releases. Check remote tags/releases for conflicts and
inspect prior Android release metadata for versionCode history; the initial
30-entry listing is only an overview, so paginate through all relevant records.
Record the existing repository Latest release using the releases/latest API,
or confirmed absence, to compare after publication. Distinguish not-found from
authentication/network errors.

Confirm whether the request is preparation-only, candidate build, draft, or
publication. Respect unrelated work. Do not silently bump versions, turn on
shrinking, upgrade dependencies, or modify desktop workflows. If committing is
authorized, load the sibling `../commit/SKILL.md` skill. Require a clean release
tree and record the full immutable commit SHA after approved changes. The
workflow/helpers must already be committed at that SHA before building.

The developer needs Git, authenticated GitHub CLI, and the verifier's documented
runtime (Python 3.11+). Builds and SDK-based checks run on GitHub-hosted
runners; do not require local Android SDK/JDK installation or signing-key access
for ordinary release orchestration. Push only the approved branch commit, and
only if authorized. Preparation-only can stop at a reviewed diff/blocker report.

## 3. Required candidate workflow contract

`android-candidate.yml` must be manually dispatched and must not
publish, tag, or have unnecessary write permissions (`contents: read`). Require:

1. Trusted approved branch/existing tag, exact immutable checkout SHA, validated
   Gradle wrapper, explicit JDK version in the workflow, and SDK/build-tools
   versions consistent with Gradle config.
   Do not create a new release tag just to start a candidate build.
2. Release-variant JVM tests, lint, and assembly. Debug-only verification is not
   enough. Keep signing credentials out of build/test jobs where practical.
3. Protected-environment signing using the permanent key. Align before signing;
   never modify APK bytes after signing. Fail closed on missing credentials.
4. APK checks using Android SDK tools: cryptographic signature verification,
   exact expected signer, app ID, versionName/versionCode, non-debuggable release
   manifest, actual ABI inventory from the APK, ZIP alignment and native ELF
   LOAD-segment compatibility with 16 KiB pages. Compare ABIs to the approved
   target set; do not silently add filters. ZIP alignment is not an ELF check.
5. Matching complete GPLv3 Corresponding Source: Android source/resources/tests,
   Gradle wrapper/configuration, root LICENSE, build scripts and instructions,
   and dependency sources/licenses/notices where required by their licenses.
   Pin/record resolved dependency coordinates and retrieval instructions; audit
   license obligations rather than assuming Maven binaries suffice. Exclude
   signing material (including `.p12`, `.jks`, `.keystore` and credential files),
   local.properties, personal paths, caches and build outputs. Enforce archive
   exclusions independently of gitignore. GitHub automatic source ZIPs and the
   desktop Cargo source package are not substitutes for this verification.
6. Extract/build the supplied source via a documented unsigned release-variant
   path without production credentials or throwaway signing keys. That unsigned
   APK is never publishable; the publication pipeline still fails closed without
   production signing. Record toolchains, dependency resolution and what was
   tested; claim offline or reproducible builds only if actually demonstrated.
7. A source-archive build record containing the source SHA, Android version/code,
   app ID, certificate fingerprint, ABI/toolchain information, and check results.
   Validate records against the real APK in CI, not only against each other.
8. Hash and verify the exact four-file inventory below, then upload a final
   `wpm-android-release-assets-<release-sha>-<attempt>` artifact, retained 14 days.
   Keep test/lint diagnostics separate; they are not publication assets.

After all readiness gates pass, dispatch the implemented workflow using a remote
branch or existing tag, not an arbitrary SHA. Its workflow_dispatch definition
must be present on the default branch. Example commands require real approved
values in place of placeholders:

```bash
gh workflow run android-candidate.yml --repo AnEvilBurrito/win_power_monitor \
  --ref <approved-branch-or-existing-tag>
gh run list --repo AnEvilBurrito/win_power_monitor \
  --workflow android-candidate.yml --event workflow_dispatch \
  --commit <release-sha> --json databaseId,headSha,status,conclusion,attempt,url
gh run watch <run-id> --repo AnEvilBurrito/win_power_monitor --exit-status
gh run view <run-id> --repo AnEvilBurrito/win_power_monitor \
  --json headSha,status,conclusion,attempt,jobs,url
```

Select the exact run ID, not merely the latest successful run. Check workflow,
SHA, completed/success status, attempt, and every required job. Approval waits
are not failures to bypass; failed/skipped/cancelled jobs are not success.
Use the final artifact only. If rerunning, rerun the full workflow and verify
all jobs/artifacts from the same attempt. If artifacts expire, build a new
candidate at the same approved SHA; do not substitute local/stale APKs.

## 4. Download and verify the publication assets

Use a new empty directory per run/attempt, after confirming the workflow and
helpers are present and reviewed:

```bash
gh run download <run-id> --repo AnEvilBurrito/win_power_monitor \
  --name wpm-android-release-assets-<release-sha>-<attempt> \
  --dir dist/android-release-<run-id>-<attempt>
python scripts/verify-android-release-assets.py dist/android-release-<run-id>-<attempt> \
  --version <VERSION> --version-code <CODE> --commit <release-sha> \
  --application-id <approved-application-id> \
  --certificate-sha256 D3D54F63925EAD1F229259CA6C711A403DDCE9D06B7B7C76CDFF699C516EF6CF
```

Required exact public inventory:

```text
wpm-android-v<VERSION>.apk
wpm-android-v<VERSION>.apk.sha256
wpm-android-v<VERSION>-source.zip
wpm-android-v<VERSION>-source.zip.sha256
```

The verifier must reject extras/missing files, incorrect sidecar
filenames/hashes, mismatched source records, unsafe archive paths, and leaked
signing/local configuration files. The Python-only download verifier
must cross-check the CI source/build record's SHA, version/code, app ID and
fingerprint against explicit expected values, and check the APK hash recorded
by CI. Actual APK signature/manifest and ELF validation happens with the SDK
and binary tools in the candidate jobs. A hash/JSON comparison is not independent
cryptographic APK signature verification; do not claim that it is. If a later
verifier parses APK identity itself, document that additional check accurately.

Never rebuild, re-sign, repackage, or rename verified downloaded files before
publication. Never mix runs/attempts, upload an Actions wrapper ZIP, or use a
`dist/*` wildcard. A verification failure is a blocker.

## 5. Device acceptance and release notes

The maintainer explicitly authorized publishing the first `0.7.0-rc.1` candidate
before phone acceptance and will handle testing afterward. State that limitation
in its notes; do not claim acceptance. Otherwise arrange approved testing of the
exact signed APK. Manual file transfer/install is sufficient; optional adb needs
platform-tools, not a full local SDK. Preserve/export existing data before any
signing-ID transition. For later releases, test an in-place update from the prior
signed release and check recordings/settings survive. Apply the behavioral
checks in `android/specs/device-acceptance.md` to the signed APK, replacing its
Android Studio/debug-install instruction; testing a debug rebuild is not enough.
Record device/OS, lifecycle/background behavior, measurement/UI results and
remaining limitations. Automated tests do not establish physical accuracy, OEM
compatibility, battery overhead, or screen-off/Doze endurance.

A device-test failure blocks publication until resolved or an explicitly scoped
pre-release limitation is approved. Any code/configuration fix requires a new
commit, candidate run, verification and acceptance of the new artifact. Do not
re-sign/rebuild a replacement under the old candidate's approval.

Write reviewed notes outside the verified asset directory, for example
`dist/android-release-notes-<VERSION>.md`. Include Android-specific changes,
minimum Android/API support from current config, package ID, versionCode,
certificate fingerprint, install/update/data-loss warnings, four exact filenames,
checksum instructions, matching source/build information, run URL/attempt/SHA,
and tested versus unverified behavior. Do not claim independent sensor accuracy
or stable-release acceptance without evidence. Keep Play distribution separate.

## 6. Tag, draft, and publish only when authorized

Reconfirm the approved version/code/SHA, successful run/attempt, four verified
files, certificate and remote tag/release absence. Never overwrite or resume an
unknown existing release. These commands require explicit authorization and
substitution of approved values; check every exit status:

```bash
git tag -a android-v<VERSION> <release-sha> -m "wpm Android <VERSION> <summary>"
git rev-parse android-v<VERSION>^{commit}
git push origin refs/tags/android-v<VERSION>:refs/tags/android-v<VERSION>
git ls-remote --tags origin 'refs/tags/android-v<VERSION>' 'refs/tags/android-v<VERSION>^{}'
```

Require the remote annotated tag's peeled SHA to equal the candidate's SHA.
Create a draft using only the four verified downloaded files:

```bash
gh release create android-v<VERSION> \
  dist/android-release-<run-id>-<attempt>/wpm-android-v<VERSION>.apk \
  dist/android-release-<run-id>-<attempt>/wpm-android-v<VERSION>.apk.sha256 \
  dist/android-release-<run-id>-<attempt>/wpm-android-v<VERSION>-source.zip \
  dist/android-release-<run-id>-<attempt>/wpm-android-v<VERSION>-source.zip.sha256 \
  --repo AnEvilBurrito/win_power_monitor --verify-tag \
  --draft --prerelease --latest=false \
  --title "wpm Android <VERSION> — <summary>" \
  --notes-file dist/android-release-notes-<VERSION>.md
```

Inspect the draft with `gh release view android-v<VERSION> --repo
AnEvilBurrito/win_power_monitor --json
url,tagName,name,body,isDraft,isPrerelease,assets`. Check flags, notes, exact
inventory, sizes and uploaded digests against the verified downloads; download
and hash if server digests are unavailable. For draft-only requests, stop here.
Publish only after checks and explicit publication approval:

```bash
gh release edit android-v<VERSION> --repo AnEvilBurrito/win_power_monitor \
  --verify-tag --draft=false --prerelease --latest=false
```

Re-read the release and verify its tag SHA, assets and public pre-release state.
Compare the releases/latest API result with the recorded baseline to confirm
Android publication did not replace the desktop Latest release; `gh release
view --json` does not provide an `isLatest` field. Report any unexpected change
rather than silently editing another release.
Report URL, version/code, application ID, certificate fingerprint, source SHA,
run/attempt, hashes, checks and limitations. On partial failure, report exactly
what exists and what failed; do not delete, clobber or retry blindly.
