 dist/ is just a tarball — there is no installer, no service, no package
 manager metadata. "Install" means verifying it, copying one file onto PATH,
 and smoke-testing. All commands below are verified (I ran them into a
 throwaway prefix, not your system).

 1. Verify the artifact before it touches your system

 Two independent checksums: the tarball's, then the binary's inside it.

 ```bash
   cd dist
   sha256sum -c wpm-v0.1.2-linux-x64.tar.gz.sha256        # tarball
   # wpm-v0.1.2-linux-x64.tar.gz: OK

   tar -xzf wpm-v0.1.2-linux-x64.tar.gz                    # no install yet
   cd wpm-v0.1.2-linux-x64
   sha256sum -c SHA256SUMS.txt                             # the actual binary
   # wpm: OK
 ```

 You can stop right here and run ./wpm straight out of the extracted folder —
 that's the closest equivalent to the Windows "extract the ZIP and run
 .\wpm.exe" flow, and it needs nothing else.

 2. Pre-flight: is something else already called wpm?

 ```bash
   type -a wpm          # lists every match on PATH, in order
 ```

 If that shows ~/.local/bin/wpm from pip install --user wpm (the typing-speed
 tool), step 3 would silently overwrite it. Either remove that first or use
 step 4's versioned path.

 3. Install per-user (recommended for testing)

 ```bash
   install -Dm755 wpm ~/.local/bin/wpm
 ```

 No sudo, creates ~/.local/bin if missing, and ~/.local/bin already precedes
 /usr/bin in your PATH (positions 3–7 in yours). Confirm the artifact is what
 runs:

 ```bash
   command -v wpm        # /home/dawson/.local/bin/wpm
   wpm --version         # wpm 0.1.2   ← ours; the pip tool prints its own
   sha256sum ~/.local/bin/wpm
   cat wpm-v0.1.2-linux-x64/SHA256SUMS.txt   # hashes must match
 ```

 4. Side-by-side candidates (if you're A/B testing builds)

 Handy when you want the previous release still on the box:

 ```bash
   sudo install -Dm755 wpm /opt/wpm/0.1.2/wpm
   sudo ln -sfn /opt/wpm/0.1.2/wpm /usr/local/bin/wpm
 ```

 Repoint or re-link for the next candidate; rm -r /opt/wpm/0.1.2 removes it
 cleanly.

 System-wide single copy instead: sudo install -Dm755 wpm /usr/local/bin/wpm.
 Don't write /usr/bin/wpm by hand — that's the distro's namespace and belongs
 to a future .deb/PKGBUILD.

 5. Smoke matrix against the installed binary

 ```bash
   wpm --once                              # one text sample, real sysfs
   wpm --json | jq '.state,.watts,.source' # machine-readable
   wpm --self-test | jq '.os'              # "linux" confirms the right
 provider
   wpm                                     # dashboard; q to quit
   wpm --log /tmp/t.csv --once && cat /tmp/t.csv
 ```

 All four verified from the installed copy:

 ```
   Idle: 0.00 W measured power; 11.66 V; 80.32%; native:sysfs_power_supply
   wpm 0.1.2
 ```

 Then exercise the paths your hardware can't reach, still using the installed
 binary:

 ```bash
   WPM_SYSFS_ROOT=/tmp/wpm-fix wpm --once --signed
   # Discharging: -12.00 W measured power; 12.00 V; 87.50%;
 native:sysfs_power_supply

   WPM_SYSFS_ROOT=/tmp/empty-fix wpm --json --require-battery; echo "exit=$?"
    # exit=3
 ```

 Note WPM_SYSFS_ROOT works identically on an installed copy — no rebuild, no
 dev tooling.

 6. Config file (not created for you)

 Nothing is written at install time. If you want to test config resolution:

 ```bash
   mkdir -p ~/.config/wpm && cat > ~/.config/wpm/config.toml <<'EOF'
   rate = 2.0
   x_length = 120
   tray = false
   EOF
   wpm --once            # no warnings on stderr means the file parsed
 ```

 Resolution order is --config → WPM_CONFIG → $XDG_CONFIG_HOME/wpm/config.toml
 → ~/.config/wpm/config.toml. A malformed file prints wpm: warning: ignoring
 config … rather than failing — check stderr, since stdout stays clean JSON.

 tray = false is a no-op on Linux (it already defaults off there), but
 harmless and it's what the dashboard banner reflects under ?.

 7. Rollback

 ```bash
   rm ~/.local/bin/wpm
 ```