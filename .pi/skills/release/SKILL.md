---
name: release
description: Prepare and publish win_power_monitor GitHub pre-releases for Windows x64, Linux x64, and experimental macOS arm64/x64 with annotated tags, verified native packages, matching GPLv3 Corresponding Source, and SHA-256 checksums. Use when asked to release, create a pre-release, tag a version, or publish release assets to GitHub.
---

# Repository pre-releases

Work from the repository root. Creating or editing this skill does not authorize
an actual release. A request to prepare or preview a release does not authorize
commits, tag pushes, uploads, or publication. Before remote mutations, establish
the user's approval of the exact version, commit, assets, and publication intent;
ask only for decisions not already supplied. Never force-push, move an existing
tag, overwrite assets, delete a release, or promote to stable without permission.

## Repository conventions

- Repository: `AnEvilBurrito/win_power_monitor`; confirm the current `origin`.
- Package: `wpm-cli`; executable: `wpm`; version comes from `Cargo.toml` and the
  matching root package entry in `Cargo.lock`.
- Existing releases use annotated tags `v0.1.0`, `v0.1.1`, `v0.1.2` and titles
  such as `wpm 0.1.2 — battery capacity and elapsed time`. Preserve the `v<VERSION>`
  tag and `wpm <VERSION> — <summary>` title convention.
- Those historical GitHub releases have `isPrerelease=false`. For this workflow,
  explicitly use `--prerelease --latest=false`; a Git tag alone is not a GitHub
  pre-release. Do not retroactively alter older releases.
- Default to the same plain version format, e.g. `v0.1.3`, with GitHub's
  pre-release flag. If the user requests a SemVer suffix such as `v0.1.3-rc.1`,
  use `0.1.3-rc.1` in Cargo and all asset names too. Do not select a new version
  silently; propose one from the current manifest, tags, and requested scope.
- Default release scope is Windows x64, Linux x64, and experimental macOS on
  both Apple Silicon (arm64) and Intel (x64), plus one matching vendored source
  ZIP. Every archive has a `.sha256` sidecar: ten assets in total. Historical
  releases were Windows-only; do not inherit that restriction. Omit a platform
  only when the user explicitly approves a narrower release, and disclose it.
- `.github/workflows/candidate.yml` only retains unsigned Windows/source workflow
  artifacts. `.github/workflows/ci.yml` checks all supported platforms and retains
  macOS artifacts. Neither workflow publishes a release when a tag is pushed.

## 1. Inspect and agree on the release

Use read-only commands first:

```bash
pwd
git status --short --branch
git remote -v
git log -8 --oneline
git tag --sort=-version:refname
gh auth status
gh release list --repo AnEvilBurrito/win_power_monitor --limit 10
```

Read `Cargo.toml`, the relevant `Cargo.lock` entry, `docs/release-notes.md`,
`scripts/README.md`, the packaging scripts, and current workflows. Inspect the
latest release with `gh release view <previous-tag> --repo
AnEvilBurrito/win_power_monitor --json tagName,name,body,assets,isPrerelease` and
review `git log <previous-tag>..HEAD` and the diff. Treat previous release notes
as style examples, not authoritative filenames or validation evidence.

Confirm version, target commit/branch, any departure from the default four-target
platform scope, and whether to prepare, create a draft, or publish. Do not ask
whether to include Linux/macOS when the user has already requested a normal
multi-platform release. Check local and remote tag/release existence before
creation (`git show-ref --verify refs/tags/<tag>`, `git ls-remote --tags origin`,
`gh release view <tag> --repo AnEvilBurrito/win_power_monitor`). Distinguish
not-found from authentication/network failure. Stop on conflicting existing
objects; do not automatically replace them or resume an unknown release.

## 2. Prepare an immutable, checked commit

1. Respect all pre-existing work. Do not stash, reset, switch branches, or stage
   unrelated files automatically. A release must be built from a clean tracked
   working tree with no untracked files that could affect the build/package.
   Ignored `target/` and `dist/` are expected but are not trusted release inputs.
2. Update the package version and matching lockfile entry together using the
   normal read/edit tools. Avoid unrelated dependency upgrades. Update
   `docs/release-notes.md` with the reviewed scope and honest limitations.
3. If authorized to commit, load `.pi/skills/commit/SKILL.md` and follow it.
   Otherwise stop at the prepared diff and request permission to commit.
4. From the final committed tree run the repository gates:

   ```bash
   cargo fmt --check
   cargo clippy --locked --all-targets -- -D warnings
   cargo test --locked
   ```

   Stop on failure. Check CI for the exact release commit with `gh run list` /
   `gh run view`; use `gh run watch <run-id> --exit-status` for pending runs.
   A local Windows pass does not prove Linux/macOS pass. Require successful
   relevant platform CI and native packaging for every platform to be shipped;
   report missing validation instead of inventing a pass.
5. Record the full commit SHA with `git rev-parse HEAD`. Recheck the clean tree,
   package/lockfile version, and unchanged HEAD before packaging, tagging, and
   publishing. If anything changes, stop and rebuild/revalidate as necessary.

## 3. Package and verify all platforms

Build every target from a clean checkout of the same recorded release SHA and
Cargo version. Run commands at the repository root on the specified native host;
Git Bash on Windows is not a Linux/macOS build environment. Run the section 2
Rust gates on every native target, or use successful exact-SHA CI evidence.
Record the host/toolchain, target, SHA, version, checks, and archive hashes for
each package. Native build success is not proof of real battery telemetry.

### Windows x64 and shared source bundle

With PowerShell 7, MSVC Build Tools, Windows SDK, and Rust installed:

```bash
pwsh -NoProfile -File scripts/package.ps1
pwsh -NoProfile -File scripts/package-source.ps1 -Ref <release-sha>
```

`package.ps1` runs `scripts/verify-package.ps1`, checking the Windows package.
It builds the working tree; `package-source.ps1` exports only the committed ref.
Their inputs must match exactly. The source script vendors locked dependencies,
records the commit/toolchain, validates archive contents/checksums, and
offline-builds the extracted source for Windows. Do not use `-SkipBuildCheck`
for a published release. Inspect the packaged `wpm.exe --version` on Windows.

### Linux x64

On an x86-64 Linux host with Rust, GNU tar, gzip, and coreutils:

```bash
./scripts/package-linux.sh x86_64-unknown-linux-gnu
./scripts/verify-package-linux.sh dist/wpm-v<VERSION>-linux-x64.tar.gz
./dist/wpm-v<VERSION>-linux-x64/wpm --version
```

The packager/verifier checks the outer SHA-256, member allowlist, inner binary
checksum, executable permission, and x86-64 ELF identity. Keep this script's
supported x64 target; changing the target argument does not make its fixed x64
filenames or verifier appropriate for Linux arm64. Document the build distro,
libc/runtime compatibility, and actual smoke-test evidence; do not imply a
static or universally compatible Linux binary without verification.

### Experimental macOS arm64 and x64

On each native Mac architecture with Rust stable and Xcode Command Line Tools:

```bash
bash scripts/package-macos.sh
```

Run once on Apple Silicon for `aarch64-apple-darwin` / `macos-arm64` and once on
Intel for `x86_64-apple-darwin` / `macos-x64`. The script selects the host target,
checks outer/inner SHA-256, verifies Mach-O architecture with `lipo`, and runs
`wpm --version`. Do not relabel one architecture's package as the other or as
universal. Read `docs/dev/macos-install.md`; CI/fixtures do not establish real
MacBook battery validation. Builds are not Developer ID signed or notarized.

### Obtain native packages from CI when needed

Inspect the workflows before relying on retained artifacts. Currently:

- `candidate.yml` retains Windows/source archives and sidecars for 14 days.
- `ci.yml` retains both macOS architectures for 14 days under
  `wpm-macos-macos-15-<release-sha>` and
  `wpm-macos-macos-15-intel-<release-sha>`.
- The CI Linux job packages/verifies but does **not** upload its output;
  the CI Windows job also does not retain its package. A green job alone does
  not supply a downloadable release asset.

For macOS, after confirming the run's `headSha` and successful matrix jobs:

```bash
gh run download <ci-run-id> --repo AnEvilBurrito/win_power_monitor \
  --name wpm-macos-macos-15-<release-sha> --dir dist/incoming/macos-arm64
gh run download <ci-run-id> --repo AnEvilBurrito/win_power_monitor \
  --name wpm-macos-macos-15-intel-<release-sha> --dir dist/incoming/macos-x64
```

Inspect downloaded paths; verify the archive/sidecar pair and native CI checks
before including them. Use each actual verified path in the upload command if
it remains under `dist/incoming/`; never upload the Actions wrapper ZIP.
For Linux, obtain a verified native build from an available Linux environment,
or seek approval for a workflow change that retains its archives/checksums and
run it at the final release SHA. If a host, artifact, or check is unavailable,
stop and report the blocker; do not silently publish Windows-only or omit a
macOS architecture. Workflow edits must be committed and validated like any
other release change, not applied after the recorded release SHA.

### Matching source and final inventory

One vendored source ZIP can accompany all four binaries only after validating
that it includes their exact source, dependencies, and build instructions.
The source packager currently performs only a Windows offline build. Transfer
the same final ZIP to Linux x64 and both macOS hosts, verify its SHA-256, extract
all contents (including `.cargo` and `vendor`) to a fresh validation directory,
and run the appropriate command from that extracted source root:

```bash
# Linux x64
cargo build --offline --locked --release --target x86_64-unknown-linux-gnu
# macOS Apple Silicon (on its native host)
cargo build --offline --locked --release --target aarch64-apple-darwin
# macOS Intel (on its native host)
cargo build --offline --locked --release --target x86_64-apple-darwin
```

Install each native toolchain/SDK first; they are not bundled. Verify the source
`SOURCE-BUILD.md` commit/version. Record native offline checks and prerequisites
in release notes because the generated source build record is Windows-specific.
If dependencies/instructions are missing, stop and fix the source packaging in
a reviewed commit, then rebuild/revalidate the final bundle on every platform.
Do not claim cross-platform source verification from Windows checks alone.
Review tracked content for secrets and dependency notices; vendoring is not a
license audit. GitHub's automatic source archives are not a substitute for the
matching complete Corresponding Source bundle.

Default inventory, replacing `<VERSION>` with the exact Cargo version:

```text
dist/wpm-v<VERSION>-windows-x64.zip
dist/wpm-v<VERSION>-windows-x64.zip.sha256
dist/wpm-v<VERSION>-linux-x64.tar.gz
dist/wpm-v<VERSION>-linux-x64.tar.gz.sha256
dist/wpm-v<VERSION>-macos-arm64.tar.gz
dist/wpm-v<VERSION>-macos-arm64.tar.gz.sha256
dist/wpm-v<VERSION>-macos-x64.tar.gz
dist/wpm-v<VERSION>-macos-x64.tar.gz.sha256
dist/wpm-v<VERSION>-source.zip
dist/wpm-v<VERSION>-source.zip.sha256
```

Independently compare archive SHA-256 hashes to sidecars and record the final
explicit paths, including any verified download subdirectories. Never upload
`dist/*`: it may contain old versions, extracted files, or staging directories.
Existing source outputs cause an error; use `-Force` only after approval to
replace those exact local outputs. Binary packaging can replace same-version
local candidates, so flag existing outputs before running any packager.

## 4. Draft release notes

Prepare reviewed notes in `dist/release-notes-<VERSION>.md` using the file tools.
Include key changes since the previous tag, exact download filenames, checksum
verification guidance, and the matching source download. Use the current
version everywhere; do not copy stale filenames from historical release notes.
Include a download table for Windows x64, Linux x64, experimental macOS Apple
Silicon/Intel, and shared source, with native extraction/run and checksum guidance.
Windows binaries are unsigned. Linux uses laptop sysfs battery telemetry, with
no tray or whole-system desktop power meter. macOS battery flow is experimental,
with no menu-bar integration or whole-system power measurement; builds are not
Developer ID signed/notarized and real Intel/Apple Silicon battery validation
remains pending unless new evidence establishes otherwise. Never advise users
to bypass OS security protections. Preserve other telemetry/platform limitations
and distinguish synthetic/CI checks from real-hardware validation. Do not claim
signing, hardware support, or tests that were not verified. Disclose any
user-approved platform omissions explicitly.

## 5. Tag, stage, and publish (only when authorized)

Use exact approved values in place of angle-bracket placeholders. These are
examples to adapt, not commands to run with literal placeholders. Check every
command's exit status and stop immediately on failure.

```bash
git tag -a <tag> <release-sha> -m "wpm <VERSION> <summary>"
git rev-parse <tag>^{commit}
git push origin refs/tags/<tag>:refs/tags/<tag>
git ls-remote --tags origin refs/tags/<tag> 'refs/tags/<tag>^{}'
```

Verify the local tag is annotated and the remote peeled commit is exactly the
recorded release SHA. Push only that tag, not all tags or branches. If a branch
push is needed for CI or to retain the release commit, obtain permission first.

Create one multi-platform draft with all ten default assets explicitly named.
Adapt the paths to the verified final inventory (e.g. macOS CI download
subdirectories); remove a platform pair only for a user-approved narrower scope:

```bash
gh release create <tag> \
  dist/wpm-v<VERSION>-windows-x64.zip \
  dist/wpm-v<VERSION>-windows-x64.zip.sha256 \
  dist/wpm-v<VERSION>-linux-x64.tar.gz \
  dist/wpm-v<VERSION>-linux-x64.tar.gz.sha256 \
  dist/wpm-v<VERSION>-macos-arm64.tar.gz \
  dist/wpm-v<VERSION>-macos-arm64.tar.gz.sha256 \
  dist/wpm-v<VERSION>-macos-x64.tar.gz \
  dist/wpm-v<VERSION>-macos-x64.tar.gz.sha256 \
  dist/wpm-v<VERSION>-source.zip \
  dist/wpm-v<VERSION>-source.zip.sha256 \
  --repo AnEvilBurrito/win_power_monitor \
  --verify-tag --draft --prerelease --latest=false \
  --title "wpm <VERSION> — <summary>" \
  --notes-file dist/release-notes-<VERSION>.md
```

Inspect the draft's tag, title, notes, prerelease flag, and complete asset list
with `gh release view <tag> --repo AnEvilBurrito/win_power_monitor --json
url,tagName,name,body,isDraft,isPrerelease,assets`. Confirm uploaded sizes and
SHA-256 digests against local files; download and hash if digests are unavailable.
Only publish once all uploads/checks succeed and publication is authorized:

```bash
gh release edit <tag> --repo AnEvilBurrito/win_power_monitor \
  --verify-tag --draft=false --prerelease --latest=false
```

Re-read the release: require `isDraft=false`, `isPrerelease=true`, the intended
assets and exact tag commit. Report its URL, tag, commit SHA, checks, and any
limitations. If the request was draft-only, leave `isDraft=true` and report that.
On partial failure, report which local/remote objects exist and what failed;
leave them intact rather than deleting, retrying publication blindly, or
clobbering existing assets.
