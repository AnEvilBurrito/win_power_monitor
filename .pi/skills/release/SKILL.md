---
name: release
description: Prepare and publish win_power_monitor GitHub pre-releases from verified GitHub Actions artifacts for Windows x64, Linux x64, and experimental macOS arm64/x64, with annotated tags, matching GPLv3 source, and SHA-256 checksums. Use when asked to release, create a pre-release, tag a version, or publish release assets to GitHub.
---

# Artifact-based repository pre-releases

Work from the repository root. Use GitHub-hosted native runners, not manual
builds on the developer's Windows/Linux/Mac machines. The developer needs Git,
authenticated GitHub CLI, and Python 3.11+ for artifact verification; local Rust,
MSVC, Linux, and macOS build environments are not required.

Creating/editing this skill does not authorize a release. Preparation alone does
not authorize commits, branch/tag pushes, workflow dispatch, uploads, or
publication. Establish approval of the version, release commit, and requested
remote actions; ask only for missing decisions. Never force-push, move tags,
overwrite assets, delete releases, or promote to stable without permission.

## Conventions and pipeline

- Repository: `AnEvilBurrito/win_power_monitor`; confirm the current `origin`.
- Cargo package: `wpm-cli`; executable: `wpm`. Keep the `Cargo.toml` and root
  `Cargo.lock` package versions aligned.
- Preserve annotated tags `v<VERSION>` and titles `wpm <VERSION> — <summary>`.
  Existing tags include `v0.1.0`, `v0.1.1`, and `v0.1.2`. Propose, do not silently
  choose, the next version. Default to plain versions such as `v0.1.3`; if asked
  for `v0.1.3-rc.1`, use `0.1.3-rc.1` in Cargo and all asset names too.
- Historical releases have `isPrerelease=false`. This workflow explicitly uses
  `--prerelease --latest=false`; do not alter older releases.
- Default scope is Windows x64, Linux x64, experimental macOS Apple Silicon
  (arm64) and Intel (x64), and one shared vendored source ZIP. Each archive has
  a `.sha256` sidecar: ten files total. Do not silently omit platforms.
- `.github/workflows/candidate.yml` is the release build entry point. It is
  manually dispatched, checks out the run's immutable SHA, and never publishes:
  1. Windows runs formatting, clippy, tests, package verification, and the shared
     source packager's extracted offline Windows build.
  2. Linux and both macOS runners run formatting, clippy, tests, verify/download
     that same source ZIP, offline-build it natively, then package/check binaries.
  3. Only after all native jobs succeed, `release-assets` assembles and verifies
     the exact inventory, archive hashes, and source commit/version identity.
  4. The final artifact is `wpm-release-assets-<release-sha>-<attempt>`, retained
     for 14 days. Intermediate candidates are not release-ready substitutes.
- Ordinary `ci.yml` artifacts are for testing, not a complete release set.
  Do not mix runs, versions, attempts, old local packages, or CI wrapper ZIPs.

## 1. Inspect and prepare the release commit

```bash
pwd
git status --short --branch
git remote -v
git log -8 --oneline
git tag --sort=-version:refname
gh auth status
gh release list --repo AnEvilBurrito/win_power_monitor --limit 10
```

Read `Cargo.toml`, the relevant lockfile entry, `docs/release-notes.md`,
`scripts/README.md`, `scripts/verify-release-assets.py`, the packaging scripts,
and current `candidate.yml`. Inspect the previous release and
`git log <previous-tag>..HEAD`; do not copy stale download filenames or claims.
Confirm version, target branch/commit, and whether to prepare, draft, or publish.
Check local and remote tags/releases for conflicts; distinguish not-found from
network/authentication errors. Do not replace or resume unknown existing objects.

Respect unrelated/staged work. Prepare version/notes changes through the normal
read/edit tools without unrelated dependency upgrades. If authorized to commit,
load `.pi/skills/commit/SKILL.md`; otherwise stop at the reviewed diff. Record
`git rev-parse HEAD` and require a clean release tree. The workflow/helper changes
must already be committed at that SHA. Review tracked source for secrets and
license/notice obligations; vendoring is not a license audit.

Push the approved release branch commit only when authorized, so GitHub can
build it. Do not require the developer to run each native build locally: the
candidate workflow supplies the Rust gates and native/offline packaging checks.
A preparation-only request can stop here with a diff and planned dispatch.

## 2. Build once with GitHub Actions

Dispatch using a remote branch or existing tag containing the approved SHA;
`--ref` is a branch/tag, not an arbitrary SHA. The workflow_dispatch definition
must be available on the default branch. Do not create a release tag just to
start the build. Examples below require actual approved placeholder values:

```bash
gh workflow run candidate.yml --repo AnEvilBurrito/win_power_monitor \
  --ref <remote-branch-or-existing-tag>
gh run list --repo AnEvilBurrito/win_power_monitor \
  --workflow candidate.yml --event workflow_dispatch --commit <release-sha> \
  --json databaseId,headSha,status,conclusion,attempt,url
gh run watch <run-id> --repo AnEvilBurrito/win_power_monitor --exit-status
gh run view <run-id> --repo AnEvilBurrito/win_power_monitor \
  --json headSha,status,conclusion,attempt,jobs,url
```

Use the dispatched run ID, not simply the latest successful run. Confirm the
repository/workflow, exact `headSha`, completed/success conclusion, attempt, all
four native jobs, and final `release-assets` job. A moved branch or failed,
skipped, cancelled, or incomplete job is a blocker. Inspect logs rather than
bypassing a failed check. Reuse a prior run only if all these checks hold and
its final artifact still exists. No local multi-platform rebuild is necessary.

Artifacts are attempt-specific to prevent accidental mixing. If a rerun is
needed, rerun **all** jobs (`gh run rerun <run-id> --repo
AnEvilBurrito/win_power_monitor`), not just failed jobs: Unix jobs need the source
artifact generated by Windows in the same attempt. Recheck the new attempt and
all jobs. If artifacts expired, dispatch a new run at the same approved commit;
never substitute stale `dist/` contents.

## 3. Download and verify the complete artifact

Use a new, empty destination per run/attempt. `gh run download` extracts the
Actions wrapper ZIP; upload the package files inside it, not the wrapper:

```bash
gh run download <run-id> --repo AnEvilBurrito/win_power_monitor \
  --name wpm-release-assets-<release-sha>-<attempt> \
  --dir dist/release-<run-id>-<attempt>
python scripts/verify-release-assets.py dist/release-<run-id>-<attempt> \
  --version <VERSION> --commit <release-sha>
```

The standard-library verifier requires exactly the following inventory, validates
every sidecar filename/hash, and checks the source manifest, lockfile, vendored
configuration/dependencies, and source build record's exact commit/version:

```text
wpm-v<VERSION>-windows-x64.zip
wpm-v<VERSION>-windows-x64.zip.sha256
wpm-v<VERSION>-linux-x64.tar.gz
wpm-v<VERSION>-linux-x64.tar.gz.sha256
wpm-v<VERSION>-macos-arm64.tar.gz
wpm-v<VERSION>-macos-arm64.tar.gz.sha256
wpm-v<VERSION>-macos-x64.tar.gz
wpm-v<VERSION>-macos-x64.tar.gz.sha256
wpm-v<VERSION>-source.zip
wpm-v<VERSION>-source.zip.sha256
```

Native binary checks and source offline builds are evidenced by the successful
candidate jobs; do not try running foreign executables on the developer's host.
Record the run URL, attempt, SHA, version, final artifact, and archive hashes.
Never rebuild/repackage downloaded files locally or upload `dist/*`. If any
check fails, stop. The default verifier intentionally requires all platforms;
a user-approved narrower release requires an explicit reviewed workflow/verifier
change, not ignoring its missing-file errors.

## 4. Draft release notes

Write reviewed notes to `dist/release-notes-<VERSION>.md`, outside the verified
asset directory. Include changes since the previous tag, a download table for
all four targets and shared source, exact filenames, SHA-256/extraction guidance,
and the candidate run URL/commit. Source ZIP includes `.cargo`, vendored locked
dependencies, native build prerequisites/instructions in `scripts/README.md`,
and a Windows-specific generated build record. Cite native offline checks from
the candidate run; GitHub automatic source archives are not a substitute.

Windows builds are unsigned. Linux uses laptop sysfs battery telemetry, has no
tray or whole-system desktop power meter, and is not necessarily static or
compatible with every distribution/libc. Record the Linux runner/build environment.
macOS battery flow is experimental, has no menu-bar integration or whole-system
power measurement, and is not Developer ID signed/notarized. Real Intel/Apple
Silicon battery validation remains pending unless new evidence establishes it.
Native CI/fixtures are not real-hardware validation; never invent test/signing
claims or advise bypassing OS security protections.

## 5. Tag, stage, and publish only when authorized

Check every command's exit status. Reconfirm the release SHA/version, verified
run/attempt, hashes, and remote tag/release absence before mutation. Use explicit
approved values, not literal angle-bracket placeholders:

```bash
git tag -a <tag> <release-sha> -m "wpm <VERSION> <summary>"
git rev-parse <tag>^{commit}
git push origin refs/tags/<tag>:refs/tags/<tag>
git ls-remote --tags origin refs/tags/<tag> 'refs/tags/<tag>^{}'
```

Require an annotated tag and the remote peeled commit equal to the artifact's
release SHA. Push only the intended tag, never all branches/tags.

Create a draft with the ten exact downloaded files:

```bash
gh release create <tag> \
  dist/release-<run-id>-<attempt>/wpm-v<VERSION>-windows-x64.zip \
  dist/release-<run-id>-<attempt>/wpm-v<VERSION>-windows-x64.zip.sha256 \
  dist/release-<run-id>-<attempt>/wpm-v<VERSION>-linux-x64.tar.gz \
  dist/release-<run-id>-<attempt>/wpm-v<VERSION>-linux-x64.tar.gz.sha256 \
  dist/release-<run-id>-<attempt>/wpm-v<VERSION>-macos-arm64.tar.gz \
  dist/release-<run-id>-<attempt>/wpm-v<VERSION>-macos-arm64.tar.gz.sha256 \
  dist/release-<run-id>-<attempt>/wpm-v<VERSION>-macos-x64.tar.gz \
  dist/release-<run-id>-<attempt>/wpm-v<VERSION>-macos-x64.tar.gz.sha256 \
  dist/release-<run-id>-<attempt>/wpm-v<VERSION>-source.zip \
  dist/release-<run-id>-<attempt>/wpm-v<VERSION>-source.zip.sha256 \
  --repo AnEvilBurrito/win_power_monitor \
  --verify-tag --draft --prerelease --latest=false \
  --title "wpm <VERSION> — <summary>" \
  --notes-file dist/release-notes-<VERSION>.md
```

Inspect with `gh release view <tag> --repo AnEvilBurrito/win_power_monitor --json
url,tagName,name,body,isDraft,isPrerelease,assets`. Verify the draft flag,
pre-release flag, title/notes, all ten assets, sizes, and uploaded SHA-256 digests
against downloaded files; download/hash if server digests are unavailable.
Publish only when all checks pass and publication is authorized:

```bash
gh release edit <tag> --repo AnEvilBurrito/win_power_monitor \
  --verify-tag --draft=false --prerelease --latest=false
```

Re-read the release and require `isDraft=false`, `isPrerelease=true`, intended
assets, and the exact tag SHA. For draft-only requests, leave `isDraft=true`.
Report URL, tag, SHA, candidate run/attempt, and checks/limitations. On partial
failure report what exists and what failed; leave objects intact rather than
deleting, clobbering, or blindly retrying publication.
