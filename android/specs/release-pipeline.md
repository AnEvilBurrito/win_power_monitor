# Android APK release pipeline

Independent of desktop Cargo packaging. First approved version: `0.7.0-rc.1`,
versionCode `11`, application ID `io.github.anevilburrito.wpm`.

## GitHub Actions

`.github/workflows/android-candidate.yml` is manual-only with `contents: read`.
It checks out the immutable run SHA and accepts only master or Android release
tags whose commit belongs to master. It does not create tags or publish releases.

1. **Build:** JDK 21, validated Gradle 8.13 wrapper, SDK 36/Build Tools 35.0.0.
   Run release unit tests, lint and assembly without signing credentials.
   Verify the unsigned APK and record its hash, identity, commit and successful
   test/lint results. Upload only that APK and internal build record for signing.
2. **Sign:** consume the same run/attempt's inputs in protected environment
   `android-release`. Check the APK hash and identity against the build record
   and checked-out Gradle config. Align, sign with the permanent PKCS12 key,
   and verify the actual signature, manifest and native alignment.
3. **Upload:** calculate SHA-256 of the verified signed bytes and retain the
   signed APK and checksum for 14 days. Keep diagnostics separate.

There is no dependency-source export, native-source archive download, source
ZIP or extracted-source rebuild in this workflow. Legacy source-packaging
helpers are not invoked. WPM remains GPL-3.0-only; release notes must provide
required source access and build/notice links without adding archive assets.

## Signing and checks

Use existing `android-release` secrets, environment password references and
restricted temporary runner storage. Never pass keys to Gradle, cache/upload
signing material or print secret values. Always delete the temporary keystore.
The maintainer approved manual dispatch without a required reviewer for the
first run; do not change environment protection as part of a release.

The environment certificate pin and APK's actual signer must both match:
`D3D54F63925EAD1F229259CA6C711A403DDCE9D06B7B7C76CDFF699C516EF6CF`.

Require the expected app ID, version/code, non-debuggable manifest, all four
ABIs, 16 KiB ZIP alignment and native ELF LOAD alignment. Sign only after
alignment, and do not change signed bytes. Missing or failed checks stop upload.

## Public assets and publication

`wpm-android-release-assets-<sha>-<attempt>` contains exactly:

- `wpm-android-v<VERSION>.apk`
- `wpm-android-v<VERSION>.apk.sha256`

The checksum contains `<lowercase sha256>  <APK basename>\n`.
The release skill downloads that successful run's files, verifies the checksum,
creates an authorized annotated `android-v<VERSION>` tag at the run SHA, and
publishes a GitHub pre-release with `--latest=false`. Preserve desktop Latest.
Never substitute local/debug APKs or mix runs/attempts.

The maintainer handles phone testing after the first pre-release. No installation,
uninstall, data clearing or device-acceptance claim is part of this pipeline.
