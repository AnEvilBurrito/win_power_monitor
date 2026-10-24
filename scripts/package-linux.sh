#!/usr/bin/env bash
# Build and package the Linux x64 binary. Mirrors scripts/package.ps1, which
# stays Windows-only: this script only ever writes dist/wpm-v*-linux-x64*.
set -euo pipefail
cd "$(dirname "$0")/.."

target="${1:-x86_64-unknown-linux-gnu}"
version="$(cargo pkgid -p wpm-cli | sed 's/.*@//')"
name="wpm-v${version}-linux-x64"
package="dist/${name}"
tarball="dist/${name}.tar.gz"

cargo build --locked --release --target "$target"

if [ -d "$package" ]; then
  find "$package" -mindepth 1 -delete
fi
mkdir -p "$package/docs"
install -m 0755 "target/${target}/release/wpm" "$package/wpm"
install -m 0644 README.md LICENSE "$package/"
install -m 0644 docs/promo.png "$package/docs/"
(cd "$package" && sha256sum wpm > SHA256SUMS.txt)

# Stable ordering and timestamps so rebuilds from the same tree compare equal.
(cd dist && tar --sort=name --owner=0 --group=0 --numeric-owner \
    --mtime='2026-01-01 00:00:00Z' \
    --format=gnu -cf - "$name" | gzip -n > "${name}.tar.gz")
(cd dist && sha256sum "${name}.tar.gz" > "${name}.tar.gz.sha256")

"$(dirname "$0")/verify-package-linux.sh" "$tarball"
echo "UNSIGNED GPL-3.0-only candidate: ${tarball}"
echo 'Before distribution provide matching GPLv3 Corresponding Source. Nothing published.'
