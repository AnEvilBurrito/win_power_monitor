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

The provider invokes `/usr/sbin/ioreg` three times per collection and tries
read-only AppleSMC battery voltage/current keys once per collection. SMC is
experimental and may be unavailable or require privileges; WPM falls back to
ASB readings when it fails or disagrees with ASB direction. No sudo, service,
or powermetrics is required for the fallback. Start with the default five-second
interval; overhead has not been benchmarked.
Config uses `$XDG_CONFIG_HOME/wpm/config.toml`, else `~/.config/wpm/config.toml`.
Use `--config` or `WPM_CONFIG` to override. Tray defaults to false.

## Experimental SMC and capacity test

Run WPM normally, without sudo, for several minutes on battery, including a short
period of heavier activity. Take a screenshot and press `e` to explicitly export
retained history as CSV and JSON in the starting directory. Inspect the JSON before
sharing, since exported diagnostics may contain device identifiers. The JSON
includes the ASB reference or an SMC fallback reason in sample diagnostics.
Fixture runs do not access the SMC.

Version 0.1.7 attempted to create a separate diagnostic JSON in the home directory
automatically. Current development builds no longer create it.

SMC keys `B0AV` and `B0AC` are undocumented and their availability, encoding,
measurement meaning, and update cadence need real-hardware validation. The
JSON identifies SMC-backed readings separately from ASB fallback readings.
Design capacity remains experimental: where plausible DesignCapacity and
DesignVoltage are present, WPM assumes mAh and mV respectively and estimates
Wh; these assumptions need validation. Values remain unavailable otherwise.

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

## User

On the Mac, download the matching **Actions artifact**: `arm64` for Apple Silicon or `x64` for Intel. Unzip the downloaded artifact ZIP to get the `.tar.gz` package and its `.sha256` file.

**Verify and Extract**
Open Terminal in that folder. For Apple Silicon:
```bash
shasum -a 256 -c wpm-v0.1.2-macos-arm64.tar.gz.sha256
tar -xzf wpm-v0.1.2-macos-arm64.tar.gz
```
The checksum command should report `OK`. For Intel, replace `arm64` with `x64`.

**Run Without Installing**
```bash
./wpm-v0.1.2-macos-arm64/wpm --once
./wpm-v0.1.2-macos-arm64/wpm
```
The second command opens the dashboard; press `q` to exit.

**Install for Your User**
No administrator access is needed:
```bash
mkdir -p "$HOME/.local/bin"
install -m 755 wpm-v0.1.2-macos-arm64/wpm "$HOME/.local/bin/wpm"
```

If `~/.local/bin` is not already on your `PATH`, add it for the current session:
```bash
export PATH="$HOME/.local/bin:$PATH"
```
To make that persistent with macOS’s default zsh shell, add that same line to `~/.zshrc`.

Then run:
```bash
wpm --version
wpm --self-test
wpm
```