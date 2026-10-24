# Release packaging

## Recommended: build all release assets with GitHub Actions

The manual **Unsigned release candidate (no publishing)** workflow in
`.github/workflows/candidate.yml` builds Windows x64, Linux x64, and experimental
macOS arm64/x64 on GitHub-hosted native runners. No local cross-platform build
setup is required. It runs formatting, clippy and tests, verifies native binary
packages, and creates one vendored Corresponding Source ZIP. Windows verifies
an offline build of that ZIP; Linux and both macOS runners download, checksum,
and offline-build the same ZIP before their packages are retained.

After committing/pushing the reviewed version, with permission, dispatch the
workflow on the remote release branch:

```bash
gh workflow run candidate.yml --ref <release-branch>
gh run list --workflow candidate.yml --commit <full-release-sha>
gh run watch <run-id> --exit-status
gh run view <run-id> --json headSha,status,conclusion,attempt,jobs,url
```

The workflow_dispatch definition must exist on the default branch. Confirm that
`headSha` equals the intended release commit, the whole run succeeded, and all
four native targets plus the final assembly job passed. Download only its final
artifact, retained for 14 days:

```bash
gh run download <run-id> \
  --name wpm-release-assets-<full-release-sha>-<attempt> \
  --dir dist/release-<run-id>-<attempt>
python scripts/verify-release-assets.py dist/release-<run-id>-<attempt> \
  --version <version> --commit <full-release-sha>
```

Use a fresh destination. The verifier requires Python 3.11+ (standard library
only) and checks the exact ten-file inventory: four native binary archives,
shared source ZIP, and their five SHA-256 sidecars. It checks source package,
lockfile, vendored content/configuration, and recorded commit identity too.
The source ZIP is the only source asset; do not upload the Actions wrapper ZIP.
Record the run URL/attempt and checksums for release notes. This workflow cannot
tag or publish a release (`contents: read`); use the repository `release` skill
for separately authorized draft-first GitHub pre-release publication.

Intermediate artifacts are attempt-specific. Rerun **all jobs**, not failed jobs
only, so every target uses the source generated in the same attempt. If assets
expire, dispatch a fresh exact-commit run. Never combine outputs from different
commits or attempts. The ordinary `CI` workflow remains useful for development,
but is not a complete release-assets pipeline. CI success does not establish
real laptop battery telemetry validation; macOS remains experimental/unsigned.

## Optional local Windows packaging (PowerShell 7)

Run from the repository root:

```powershell
.\scripts\package.ps1
.\scripts\package-source.ps1 -Ref HEAD
# Or export an immutable release tag:
.\scripts\package-source.ps1 -Ref v0.1.0 -Force
```

The binary packager builds the working tree. The source packager exports only the
selected committed ref, deriving its version there; commit reviewed changes first
so binary and source match. Scripts absent from that ref are not injected. Review
tracked content for secrets before packaging; path checks are not a secret scanner.

Source output is `dist\wpm-v<VERSION>-source.zip` plus `.zip.sha256`. Existing
outputs require `-Force`, which replaces only these two files. Other dist assets
are untouched. ZIP entries have stable ordering/fixed timestamps, include hidden
files and dependency notices, and are validated against the staged source by
membership and SHA256. Toolchain/build records are included. Exact ZIP hashes may
change with the toolchain or compression implementation.

`cargo vendor --locked --versioned-dirs` may download dependencies. Rust, the
Windows MSVC target, C++ Build Tools and Windows SDK must already be installed.
The extracted ZIP is built offline by default; no executable is run.
`-SkipBuildCheck` explicitly skips that check and marks the bundle unverified.
Staging/build directories are cleaned even on failure, and `CARGO_TARGET_DIR` is
restored. No Python is required. Nothing is tagged, uploaded or published.

The candidate workflow retains all four native packages, the shared source ZIP,
and their checksums as workflow artifacts, not GitHub Releases. Vendoring is not
a license audit.

## Offline build from the shared source ZIP

Extract the entire verified source ZIP, including `.cargo` and `vendor`, to a
fresh directory. Install Rust stable, the desired target, and native tooling
first: MSVC C++ Build Tools/Windows SDK on Windows, a C linker/build toolchain
on Linux, or Xcode Command Line Tools on macOS. From the extracted source root,
run the command for the native host:

```bash
# Windows x64
cargo build --offline --locked --release --target x86_64-pc-windows-msvc
# Linux x64
cargo build --offline --locked --release --target x86_64-unknown-linux-gnu
# macOS Apple Silicon
cargo build --offline --locked --release --target aarch64-apple-darwin
# macOS Intel
cargo build --offline --locked --release --target x86_64-apple-darwin
```

The generated `SOURCE-BUILD.md` / `BUILD-TOOLCHAIN.txt` describe the Windows
packaging host/check only. For other native build checks and runner/toolchain
evidence, refer to the successful candidate run linked in release notes. Native
SDKs/toolchains are not bundled. Outputs are under `target/<target>/release/`.
An offline build verifies dependency availability, not bit-for-bit binary identity.

## Linux packaging (bash)

For experimental macOS native packaging, run `bash scripts/package-macos.sh`
on macOS. It builds arm64 or x64 for the host, checks checksums and Mach-O
architecture, and creates a separate macOS tarball. It is not Developer ID
signed/notarized. See [macOS testing](../docs/dev/macos-install.md).

`package.ps1` and `package-source.ps1` stay Windows-only. On Linux:

```bash
./scripts/package-linux.sh          # dist/wpm-v<version>-linux-x64.tar.gz + .sha256
./scripts/verify-package-linux.sh dist/wpm-v<version>-linux-x64.tar.gz
```

The tarball contains `wpm`, `README.md`, `LICENSE`, `docs/promo.png` and
`SHA256SUMS.txt`, with sorted members and fixed timestamps so rebuilds from the
same tree produce an identical SHA-256. The verifier checks the tarball checksum,
the member allowlist, the inner binary checksum, and that `wpm` is an x86-64 ELF.
No packaging script writes outside `dist/`, so the Windows and Linux candidates
do not collide. Nothing is tagged, uploaded or published.
