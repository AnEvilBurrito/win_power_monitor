# Android release pipeline

This is independent of desktop Cargo packaging. Authorized first version:
`0.7.0-rc.1`, versionCode `11`, application ID `io.github.anevilburrito.wpm`.
No device installation or acceptance is automated. The first public pre-release
explicitly defers phone testing to the maintainer.

## Jobs and trust

`android-candidate.yml` is workflow_dispatch-only, on master or android-v* tags,
with contents:read. Checkout the immutable run SHA. Build/test and extracted
source rebuild run without signing secrets. A subsequent job scoped to the
`android-release` environment signs only that run/attempt's unsigned APK.
Decode the PKCS12 into RUNNER_TEMP, use environment password references, disable
configuration caching, remove temporary key on failure/success, and never cache
or upload signing material. The user approved manual dispatch as the approval
mechanism for the first run (environment currently has no reviewer rule).

Expected certificate SHA256:
`D3D54F63925EAD1F229259CA6C711A403DDCE9D06B7B7C76CDFF699C516EF6CF`.
The environment variable and actual signer must both match this independent pin.

## Public artifact contract

Final artifact: `wpm-android-release-assets-<sha>-<attempt>`, 14 days, exactly:

- `wpm-android-v<VERSION>.apk`
- `wpm-android-v<VERSION>.apk.sha256`
- `wpm-android-v<VERSION>-source.zip`
- `wpm-android-v<VERSION>-source.zip.sha256`

Sidecars contain `<lowercase sha256>  <basename>\n`. Source ZIP paths are rooted
at the archive root, not a wrapper folder. Include Android tracked build/source
files, root LICENSE, required release scripts, dependency sources/POMs/notices,
source build instructions, and `ANDROID-RELEASE.json`. Exclude local.properties,
cache/build directories, personal paths, credentials and private signing files.
Only whitelisted tracked inputs may enter the archive; gitignore is not enough.

`ANDROID-RELEASE.json` schema:

```json
{
  "schema": 1,
  "version": "0.7.0-rc.1",
  "version_code": 11,
  "application_id": "io.github.anevilburrito.wpm",
  "commit": "40 lowercase hex characters",
  "certificate_sha256": "64 uppercase hex characters",
  "apk_sha256": "64 lowercase hex characters",
  "abis": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"],
  "toolchain": {"java": "21", "gradle": "8.13", "compile_sdk": 36, "build_tools": "35.0.0"},
  "checks": {
    "release_unit_tests": true,
    "release_lint": true,
    "source_rebuild": true,
    "apk_signature": true,
    "apk_manifest": true,
    "zip_alignment_16k": true,
    "native_elf_alignment_16k": true
  }
}
```

The Python-only download verifier validates inventory, hashes, archive safety,
required source/build files, dependency source hashes, Gradle app identity and
the record against explicit expected values. It does NOT independently perform
cryptographic APK signature verification; successful trusted CI checks provide
that evidence. Fail closed; do not infer success from missing records.

## Dependency source export

`android/release-dependencies.gradle.kts`, applied from app/build.gradle.kts,
registers `:app:exportReleaseDependencies`. Lock releaseRuntimeClasspath and
export resolved external runtime components to
`android/app/build/release-dependencies/manifest.json` plus files. Resolve sources
JAR and POM for every runtime component, fail if missing. The source export has
no signing secrets. Capture licenses/notices from POM, source and runtime archives
(including classes.jar within AARs). Do not include binary runtime artifacts.

Dependency manifest shape (paths relative to release-dependencies/):

```json
{
  "schema": 1,
  "dependencies": [{
    "coordinate": "group:name:version",
    "source": "files/group-name-version-sources.jar",
    "source_sha256": "64 lowercase hex",
    "pom": "files/group-name-version.pom",
    "pom_sha256": "64 lowercase hex",
    "licenses": [{"name": "Apache-2.0", "url": "https://www.apache.org/licenses/LICENSE-2.0.txt"}]
  }]
}
```

Copy this directory into source ZIP as `android/dependency-sources/`. Include
full Apache-2.0 text and captured notices. Check and record licensing; unknown
licenses require review, not silent omission. Maven source JARs may omit native
C++ source: additionally include pinned source/build inputs for graphics-path
1.0.1 and its native dependencies, recording provenance and hashes. Dependency
coordinates and archive hashes are recorded; networked dependency resolution is
allowed. Do not claim an offline or bit-reproducible Gradle build.

## Verification and publication

JDK21, Gradle wrapper8.13, API36/Build Tools35.0.0. Run release JVM tests, lint,
assembly and dependency export; build unsigned release again from extracted
source. Sign aligned APK using protected key only afterward. Verify using
apksigner, aapt, zipalign and ELF program headers: expected app/version/code,
non-debuggable, four expected ABIs and >=16KiB native LOAD alignment. Do not
modify signed bytes. Package source with checks tied to successful commands.

Download final artifact only from the exact successful SHA/run/attempt, verify
again locally, create an annotated android-v<VERSION> tag at that SHA, draft
with four exact files, check assets, then publish --prerelease --latest=false.
Desktop Latest remains v0.1.6 unless the maintainer independently changes it.
Never reuse stale artifacts after a code change or failed job.
