# macOS experimental testing

Scope: MacBook battery flow, not system/wall power. No menu-bar icon. Apple
Silicon and Intel are intended targets, not yet hardware-validated support claims.

## Build and run

These commands run on macOS, not Windows. Install Rust stable and Apple's Xcode
Command Line Tools, then from the checkout:

```bash
cargo test --locked
cargo build --locked --release
./target/release/wpm --once
./target/release/wpm --self-test
./target/release/wpm
bash scripts/package-macos.sh
```

## Build with GitHub Actions

No local Mac is needed to obtain a test build. Push the branch or open a pull
request to trigger the `CI` workflow. Its macOS jobs run tests and build packages
on Apple Silicon (`macos-15`) and Intel (`macos-15-intel`) runners.

After a successful run, open the repository's **Actions** tab, select the **CI**
run for your commit, and download the corresponding `wpm-macos-<runner>-<commit>`
artifact from **Artifacts**. Each artifact contains a tarball and its SHA-256
sidecar; extract the Actions artifact ZIP first, then verify and extract the
tarball. Choose `macos-arm64` for Apple Silicon or `macos-x64` for Intel.
Artifacts expire after 14 days. They are testing candidates, not published releases.

The workflow also supports **Run workflow** once the workflow_dispatch definition
is present on the repository's default branch; select the branch to build there.
CI can validate native builds and fixture behavior but does not validate real
MacBook battery telemetry. Running the resulting executable still requires macOS.

## Runtime behavior

The provider invokes `/usr/sbin/ioreg` three times per collection. No sudo,
service, private entitlement, SMC access, or powermetrics is needed. Start with
the default five-second interval; subprocess overhead has not been benchmarked.
Config uses `$XDG_CONFIG_HOME/wpm/config.toml`, else `~/.config/wpm/config.toml`.
Use `--config` or `WPM_CONFIG` to override. Tray defaults to false.

## Check real readings

Check unplugged discharge, charging, plugged-in charge-limited idle, charger
reconnection, and sleep/wake. Compare current and voltage with:

```bash
/usr/sbin/ioreg -a -r -c AppleSmartBattery > battery.plist
WPM_IOREG_FIXTURE="$PWD/battery.plist" ./target/release/wpm --json
```

The fixture override freezes telemetry; never use it to assess live power.
Fixtures may contain serial numbers or other identifiers. Inspect and redact
before sharing. Keep relevant integer types and signed/wrapped current values.
Record Mac model, CPU architecture, macOS version, and charger state with results.

Watts are signed battery flow: negative discharge, positive charge. AC connection
does not imply charging. Missing current, voltage, state, AC connection, or identity
does not produce guessed watts. Percentage can use a valid charge-capacity ratio;
charge values are not treated as measured Wh. Session integrated Wh remains
available from valid power samples; it is distinct from remaining battery energy.

## Packages

`bash scripts/package-macos.sh` creates an architecture-specific tarball and
SHA-256 sidecar under `dist/`. Check the sidecar from that directory with
`shasum -a 256 -c <archive>.sha256` before extraction. Candidates are not Developer
ID signed/notarized and may be restricted by Gatekeeper or organizational policy.
Do not disable security protections to run them. Build from source when appropriate.
Provide matching GPLv3 Corresponding Source with binary distribution.