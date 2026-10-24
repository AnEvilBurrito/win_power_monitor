# Release packaging (PowerShell 7)

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

The manual candidate workflow retains both binary/source ZIPs and their checksums
as workflow artifacts, not GitHub Releases. Vendoring is not a license audit.

## Linux packaging (bash)

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
