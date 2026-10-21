> Historical design reference, not the v0.1.0 capability contract. README.md at
> repository root is authoritative for the bounded MVP: native battery flow,
> explicitly user-confirmed hardware PDH system-input meters on battery-absent PCs,
> no health/cycles/time-to-full or multiple-battery aggregation. Unsigned private
> candidate only; GPL-3.0-only selected; publication not yet authorized.
> Proposed installer/publishing plans below are not implemented release promises.

# wpm — Install Path, Invocation & winget Publishing

**Document:** 3 of 3 — distribution, command surface, and the winget submission process
**Companion docs:** `design-spec.md`, `architecture-and-requirements.md`
**Status:** draft, pre-implementation

---

## 1. Identity decisions (permanent — read before the first release)

| Item | Value | Permanence |
|---|---|---|
| `PackageIdentifier` | `anevilburrito.Wpm` | **cannot be changed after the first merged submission** |
| Command / shim | `wpm` | user-facing contract |
| Asset name | `wpm-v0.1.0-x86_64-pc-windows-msvc.exe` | must stay stable across releases |
| Crate name | `wpm-cli` (crate only), binary `wpm` | free to change |
| Release tag | `v0.1.0` | tag must match `PackageVersion` |
| `PackageVersion` | `0.1.0` (must sort correctly; 4-part also valid) | drives `winget upgrade` |

Before committing to `wpm`, run `winget search wpm` to confirm the ID and moniker are
free, and confirm your `Publisher` string is plausibly yours — reviewers check it.

The publisher display name is `anevilburrito`, and the planned package identifier is
`anevilburrito.Wpm`. Publishing to the winget community repository does not require a
separate winget publisher account or Microsoft Store developer registration. A GitHub
account is needed to submit the manifest pull request to `microsoft/winget-pkgs` and
follow its contribution process. The publisher name does not need to match the GitHub
username; it is not an account or reserved namespace. Submission remains subject to
repository policies and review. See [Microsoft's submission guide](https://learn.microsoft.com/en-us/windows/package-manager/package/).

`YourHandle` in the GitHub URL examples below remains a placeholder for the actual
repository owner; choosing a publisher name does not establish a GitHub account or
repository URL. Confirm the owner and repository name before release.

The crate name and the binary name are independent; `wpm` on crates.io is likely taken,
but that has no bearing on the shim, the manifest, or the asset name.

---

## 2. Install path (winget portable)

```
winget install anevilburrito.Wpm
```

What happens:

| Item | Location |
|---|---|
| Binary (hardlink/symlink) | `%LOCALAPPDATA%\Microsoft\WinGet\Packages\anevilburrito.Wpm_<hash>\wpm.exe` |
| Shim on PATH | `%LOCALAPPDATA%\Microsoft\WinGet\Links\wpm.exe` |
| Config (created on first save, not on install) | `%APPDATA%\wpm\config.toml` |
| Logs | only where `--log` points; no implicit file |
| Registry | nothing |
| `Add/Remove Programs` | **no entry** — portable packages appear only in `winget list` |

The `Links` directory is already on the user PATH, so `wpm` resolves in PowerShell after
the install completes. **An already-open shell needs restarting** to pick up the entry —
this is the single most common "it didn't install" report, so the README must say it.

### 2.1 Verify

```powershell
wpm --version
wpm --self-test
winget list wpm
```

### 2.2 Upgrade and uninstall

```powershell
winget upgrade anevilburrito.Wpm     # picks up new PackageVersion
winget uninstall anevilburrito.Wpm   # removes binary + shim; leaves %APPDATA%\wpm alone
```

Uninstall does not remove the config directory by design — a reinstall should keep the
user's `--rate` and theme.

---

## 3. Invocation reference

### 3.1 Synopsis

```
wpm [OPTIONS]
```

### 3.2 Options

| Flag | Default | Range / values | Meaning |
|---|---|---|---|
| `--rate <SECS>` | `5` | 0.25 – 60 | seconds between samples; warns below 1 (probe cost skews the reading) |
| `--x-length <SECS>` | `300` | > `--rate` | seconds of history on the x-axis. Accepts a bare number (`300`) or a duration suffix (`5m`, `90s`) |
| `--tray[=true\|false]` | `true` | boolean | show an icon alongside the interactive dashboard; `--tray=false` disables only the icon; ignored in noninteractive modes |
| `--log <PATH>` | off | path | append CSV, schema in §4.1 |
| `--json` | off | — | print one sample as JSON and exit |
| `--ndjson` | off | — | stream one JSON object per line |
| `--once` | off | — | print one human-readable row and exit |
| `--abs` | off | — | show magnitude instead of signed watts |
| `--step` | off | — | step interpolation instead of linear |
| `--all-states` | off | — | include charging and AC samples in statistics |
| `--require-battery` | off | — | exit 3 instead of degrading when no battery exists |
| `--self-test` | off | — | probe results, one sample, versions (for bug reports) |
| `--theme <NAME>` | `default` | `default` \| `high-contrast` \| `mono` | color tokens |
| `--config <PATH>` | `%APPDATA%\wpm\config.toml` | path | config file |
| `-V`, `--version`; `-h`, `--help` | | | standard |

### 3.3 Window math

`--x-length` is a **time span in seconds**, not a point count. Point count is derived:

```
samples = ceil(x_length / rate)
```

| Command | Span | Samples |
|---|---|---|
| `wpm` | 5m00s | 60 |
| `wpm --rate 5 --x-length 300` | 5m00s | 60 |
| `wpm --rate 0.25 --x-length 300` | 5m00s | 1200 |
| `wpm --rate 2 --x-length 1h` | 1h00m | 1800 |
| `wpm --rate 60 --x-length 60` | — | 1 → **exit 2** (need ≥2 samples) |
| `wpm --rate 0.25 --x-length 125000.25` | — | 500001 → **exit 2** (maximum 500000) |

The sample count is shown in band A of the TUI, so the derived value is always visible
rather than inferred.

### 3.4 Examples

```powershell
wpm                                  # dashboard + tray, 5-minute window, 5 s cadence
wpm --tray=false                     # dashboard only
wpm --rate 5 --x-length 300          # coarse sampling, same 5-minute window
wpm --rate 1 --x-length 15m          # fine sampling, 15-minute window
wpm --log C:\temp\power.csv          # also append CSV for Excel
wpm --abs                            # magnitude only
wpm --require-battery                # scripting: fail loudly instead of degrading
wpm --once                           # one row, no TUI
wpm --ndjson | Tee-Object power.jsonl  # stream for logging
```

### 3.5 Environment variables

`WPM_RATE`, `WPM_X_LENGTH`, `WPM_THEME`, `WPM_CONFIG` — same names as flags, overridden
by flags. Precedence: **CLI > env > config file > default.**

Output modes (`--json`, `--ndjson`, `--once`, `--self-test`) are mutually exclusive.
Without an explicit output mode, non-TTY stdout selects one-shot JSON, never a TUI or
tray icon. Config/error handling follows architecture §9: malformed files warn and
fall back; out-of-range effective timing and invalid effective environment values exit 2.

### 3.6 Exit codes

| Code | Meaning |
|---|---|
| 0 | success (including "no battery" without `--require-battery`) |
| 1 | unexpected internal error |
| 2 | usage error (bad flags, terminal too small, unusable config) |
| 3 | required capability missing (`--require-battery`) |
| 4 | log path unwritable at startup |

### 3.7 Terminal requirements

Windows Terminal is recommended; conhost works (crossterm enables VT processing). Minimum
usable size is 60 × 14; below that `wpm` exits 2 with an explicit message rather than
drawing garbage. Raw mode swallows `Ctrl+C`, so the TUI handles it explicitly and installs
a panic hook that restores the terminal.

---

## 4. Machine-readable output

### 4.1 CSV (`--log`)

Byte-compatible with the original PowerShell prototype:

```csv
Time,State,Watts,Volts,Amps,BatteryPercent
2025-01-14T12:04:38.221,Discharging,-12.40,15.62,0.794,81
```

Written by a single long-lived `BufWriter`, flushed every 10 samples and on exit. A write
failure appears once in the status bar; sampling continues.

### 4.2 NDJSON / JSON

```json
{"ts":"2025-01-14T12:04:38.221Z","state":"Discharging","watts":-12.4,"volts":15.62,
 "amps":0.794,"pct":81,"eta_s":8040,"source":"wmi","gap":false,"available":true}
```

No-battery machine (exit 0):

```json
{"ts":"2025-01-14T12:04:38.221Z","available":false,"reason":"no_battery",
 "probes":{"wmi":false,"pdh":false,"k32":true}}
```

When stdout is not a TTY and no mode flag is given, `wpm` behaves as `--once`. It never
starts a dashboard into a pipe.

---

## 5. Release pipeline

Tag push (`v*`) → build → smoke test → sign → release → winget submit.

```yaml
name: release
on: { push: { tags: ["v*"] } }
jobs:
  build:
    runs-on: windows-latest
    permissions: { contents: write }
    steps:
      - uses: actions/checkout@v4
      - uses: dtolnay/rust-toolchain@stable
      - run: cargo test --locked && cargo build --release --locked
      - name: smoke test (must exit 0 with no battery, no TTY)
        run: |
          .\target\release\wpm.exe --json
          .\target\release\wpm.exe --once
          .\target\release\wpm.exe --self-test
      - name: sign
        uses: azure/trusted-signing-action@v0
        with:
          endpoint: ${{ secrets.TS_ENDPOINT }}
          trusted-signing-account-name: ${{ secrets.TS_ACCOUNT }}
          certificate-profile-name: ${{ secrets.TS_PROFILE }}
          files-folder: target/release
          files-folder-filter: exe
      - name: package
        shell: pwsh
        run: |
          $v = "${{ github.ref_name }}".TrimStart('v')
          $n = "wpm-v$v-x86_64-pc-windows-msvc.exe"
          Copy-Item target/release/wpm.exe $n
          "SHA256=$((Get-FileHash $n -Algorithm SHA256).Hash.ToLower())" >> $env:GITHUB_ENV
      - uses: softprops/action-gh-release@v2
        with: { files: "wpm-*.exe" }
      - name: submit to winget
        run: |
          iwr https://aka.ms/wingetcreate/latest -OutFile wc.exe
          .\wc.exe update anevilburrito.Wpm --version ${{ github.ref_name }} `
            --urls "https://github.com/YourHandle/wpm/releases/download/${{ github.ref_name }}/wpm-${{ github.ref_name }}-x86_64-pc-windows-msvc.exe" `
            --submit --token ${{ secrets.WINGET_TOKEN }}
```

`WINGET_TOKEN` is a classic PAT with `public_repo`. Prefer `--submit` on the *second*
release and onward; the first submission should be hand-written YAML (§7) so you control
every field.

---

## 6. Manifests

Three files under `manifests/a/anevilburrito/Wpm/0.1.0/`. Use the latest supported
`ManifestVersion` — the values below (1.6.0) are a safe baseline; check the schema docs
before submitting.

### 6.1 `anevilburrito.Wpm.yaml`

```yaml
PackageIdentifier: anevilburrito.Wpm
PackageVersion: 0.1.0
DefaultLocale: en-US
ManifestType: version
ManifestVersion: 1.6.0
```

### 6.2 `anevilburrito.Wpm.installer.yaml`

```yaml
PackageIdentifier: anevilburrito.Wpm
PackageVersion: 0.1.0
ManifestType: installer
ManifestVersion: 1.6.0
Installers:
  - Architecture: x64
    InstallerType: portable
    InstallerUrl: https://github.com/YourHandle/wpm/releases/download/v0.1.0/wpm-v0.1.0-x86_64-pc-windows-msvc.exe
    InstallerSha256: 0000000000000000000000000000000000000000000000000000000000000000
    Commands:
      - wpm
```

If a future release ships extra files (LICENSE, docs), switch to a zip:

```yaml
    InstallerType: zip
    NestedInstallerType: portable
    NestedInstallerFiles:
      - RelativeFilePath: wpm.exe
        PortableCommandAlias: wpm
```

### 6.3 `anevilburrito.Wpm.locale.en-US.yaml`

```yaml
PackageIdentifier: anevilburrito.Wpm
PackageVersion: 0.1.0
PackageLocale: en-US
Publisher: anevilburrito
PublisherUrl: https://github.com/YourHandle
PublisherSupportUrl: https://github.com/YourHandle/wpm/issues
PackageName: wpm
PackageUrl: https://github.com/YourHandle/wpm
License: GPL-3.0-only
LicenseUrl: https://github.com/YourHandle/wpm/blob/main/LICENSE
ShortDescription: Live power draw, battery life and energy statistics in your terminal.
Description: |
  wpm is a terminal dashboard for Windows showing real-time power consumption, battery
  charge and estimated runtime, with rolling min/avg/max watts, session energy in
  watt-hours, drain rate and battery health.

  Sampling cadence and history window are configurable (--rate, --x-length) and output
  can be logged to CSV or streamed as NDJSON for scripting.
Moniker: wpm
Tags:
  - battery
  - power
  - energy
  - tui
  - monitoring
  - cli
ReleaseNotesUrl: https://github.com/YourHandle/wpm/releases/tag/v0.1.0
ManifestType: defaultLocale
ManifestVersion: 1.6.0
```

---

## 7. Submission runbook (first package)

1. **Local validation**
   ```powershell
   winget validate --manifest .\manifests\a\anevilburrito\Wpm\0.1.0
   winget install --manifest .\manifests\a\anevilburrito\Wpm\0.1.0
   wpm --version
   winget uninstall anevilburrito.Wpm
   ```
2. **Confirm the asset URL resolves anonymously** in a private browser window, and that
   its SHA-256 matches the manifest exactly.
3. **Fork `microsoft/winget-pkgs`**, place the three files at
   `manifests/a/anevilburrito/Wpm/0.1.0/`, open a PR. The folder path is
   `<first letter>/<Publisher>/<Package>/<Version>`.
4. Validation bots run manifest schema, URL, hash, and install checks. A **new package
   needs human moderator review** — expect days, not minutes. Subsequent version PRs are
   usually automerged.
5. After merge: `winget install anevilburrito.Wpm` works for everyone.

Alternatively `wingetcreate submit --token <PAT>` handles the fork/PR mechanics.

---

## 8. Gotchas checklist

- [ ] **`PackageIdentifier` is permanent.** Lock `anevilburrito.Wpm` before the first PR.
- [ ] **Release URLs must be immutable.** Tag-pinned `/releases/download/v0.1.0/…`, never
      `/releases/latest/…`. Never re-upload over a published asset — the manifest SHA goes
      stale and installs fail with a hash mismatch.
- [ ] **`PackageVersion` must sort.** `0.1.0` or `0.1.0.0`; a badly formed version makes
      `winget upgrade` silently never fire.
- [ ] **No TTY in validation.** `wpm` must never block waiting for a terminal: non-TTY
      output falls back to one-shot JSON, and the no-battery path exits 0.
- [ ] **Unsigned `.exe` triggers SmartScreen** on every install. Sign before 1.0 — Azure
      Trusted Signing is inexpensive and CI-integrated, and retrofitting means asking
      users to re-download.
- [ ] **No ARP entry with portable.** README must document `winget list`, `winget upgrade`,
      `winget uninstall` instead of "Settings → Apps".
- [ ] **Open shells need a restart** after install to see the `wpm` shim.
- [ ] **Moniker collision.** Check `winget search wpm` before claiming it.
- [ ] **arm64 later** = second installer entry (`Architecture: arm64`), not a new package.
- [ ] **MSIX is a trap here** — the sandbox blocks PDH counters and `root\wmi`, which is
      exactly what this tool needs.
- [ ] **Don't ship a zip until you need to.** A single portable `.exe` has the smallest
      manifest and the fewest failure modes.

---

## 9. Version bump checklist (every release)

1. Bump `Cargo.toml` `version`, update `CHANGELOG.md`.
2. `cargo test --locked`, commit, tag `vX.Y.Z`, push tag.
3. Release workflow builds, signs, uploads, computes SHA, submits manifest.
4. Verify the PR in `microsoft/winget-pkgs` merged.
5. `winget upgrade anevilburrito.Wpm` on a clean machine; run `wpm --self-test`.
6. Confirm the old release asset is still downloadable (never delete; users pin versions).
