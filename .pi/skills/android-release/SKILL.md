---
name: android-release
description: Build and publish a signed win_power_monitor Android APK and its SHA-256 checksum through GitHub Actions and a GitHub pre-release. Use when asked to release Android or run an Android signed-APK release. Not for desktop Rust releases.
---

# Android APK pre-release

## Scope

Follow this flow only: **push approved changes → GitHub Actions builds/signs
APK → download and verify → publish GitHub pre-release → hand off the link**.
Work from the repository root; keep agent tools there. Repository paths below
are root-relative. Do not change Cargo or desktop releases.

Publish exactly two assets:

```text
wpm-android-v<VERSION>.apk
wpm-android-v<VERSION>.apk.sha256
```

No source ZIP, dependency vendoring/export, native-source downloads, extracted
source rebuild, or extra packaging framework belongs in this skill. Do not call
the legacy source packager or four-asset verifier as release prerequisites.
WPM remains GPL-3.0-only: retain required notices and ensure GPL-required source
access is available through clear release-note links to the exact source commit
and build instructions. This two-asset scope does not waive license obligations.

## 1. Confirm the release

- Verify repository/origin: `AnEvilBurrito/win_power_monitor`.
- Inspect `git status`, current Android Gradle config and existing Android tags
  and releases. Preserve unrelated changes and require a clean release commit.
- Confirm versionName, increasing versionCode, commit and requested actions.
  The first approved target is `0.7.0-rc.1`, code `11`.
- Permanent app ID: `io.github.anevilburrito.wpm`; debug builds are separate.
- Tag: `android-v<VERSION>`. Android versions are independent of desktop tags.
- Creation/loading of this skill alone does not authorize commits, pushes, tags,
  dispatch or publication. Reuse explicit user approval; ask only what is missing.
  For authorized commits follow the sibling `../commit/SKILL.md` skill.

## 2. Push and run GitHub Actions

Inspect `.github/workflows/android-candidate.yml`. It must build/test/lint the
release variant, sign with the permanent key, verify the actual APK, calculate
SHA-256 and upload the two assets. It must not publish automatically.
If it still contains legacy source-archiving steps, report that mismatch; simplify
the workflow only when implementation is authorized, rather than extending the
archiving machinery or dispatching an incompatible workflow unchanged.

Use protected environment `android-release` and its existing secrets:
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`. Alias is `wpm-release`; the generated PKCS12 uses the same
password for the store/key entries. Never print, commit or upload credentials.
Use temporary runner storage, environment password references and guaranteed
cleanup. Never expose signing secrets to PR/debug builds or untrusted code.

Require `vars.ANDROID_SIGNING_CERT_SHA256` and the APK's verified signing
certificate to match this approved public fingerprint:

```text
D3D54F63925EAD1F229259CA6C711A403DDCE9D06B7B7C76CDFF699C516EF6CF
```

Push the approved clean commit when authorized, then manually dispatch on the
approved branch/existing tag. Do not create a release tag just to start a build.
Use the exact run SHA, run ID and attempt. **Immediately show the Actions URL**.
Watch until all required build/signing checks succeed; report failures plainly
and fix only what blocks this APK release. Never substitute a debug/local APK.

## 3. Download and verify

Download `wpm-android-release-assets-<sha>-<attempt>` from that successful run to
a fresh directory. Require exactly the two named regular files, no extra assets.
Verify the sidecar names the APK and its SHA-256 matches the downloaded bytes.
Confirm CI verified the release app ID, version/code, non-debuggable manifest,
correct signer and Android package/native alignment. A checksum is not a signing
certificate check; use the successful signing/verification job as that evidence.
Do not rebuild, re-sign or modify the downloaded APK.

## 4. Publish and hand off

Check remote tag/release conflicts. Create an annotated `android-v<VERSION>` tag
at the verified run's exact SHA and push only that tag when authorized.
Draft a GitHub release attaching only the APK and its checksum; use
`--prerelease --latest=false`. Include version/code, app ID, source/build links,
Actions run URL, checksum instructions and remaining limitations in the notes.
Verify the uploaded two-file inventory and hashes, then publish when authorized.
Never overwrite assets, move tags or replace the desktop Latest release.

The maintainer handles phone testing after the first pre-release. Do not install,
uninstall, clear data or claim device acceptance. Warn that an older unsuffixed
APK signed with a debug key may require uninstalling before the release APK;
export recordings first because uninstalling deletes private history/settings.

Return the pre-release URL and direct APK link, version/code and brief verification
results. Clearly state any failed/incomplete step; never report a release that
was not actually published.
