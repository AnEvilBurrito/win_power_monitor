#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

case "$(uname -m)" in
  arm64) target=aarch64-apple-darwin; arch=arm64 ;;
  x86_64) target=x86_64-apple-darwin; arch=x64 ;;
  *) echo 'Unsupported macOS architecture' >&2; exit 1 ;;
esac
[ "$(uname -s)" = Darwin ] || { echo 'Run on macOS' >&2; exit 1; }
version="$(cargo pkgid -p wpm-cli | sed 's/.*@//')"
name="wpm-v${version}-macos-${arch}"
package="dist/$name"
cargo build --locked --release --target "$target"
mkdir -p "$package/docs"
install -m 0755 "target/$target/release/wpm" "$package/wpm"
install -m 0644 README.md LICENSE "$package/"
install -m 0644 docs/promo.png "$package/docs/"
(cd "$package" && shasum -a 256 wpm > SHA256SUMS.txt)
tar -czf "dist/$name.tar.gz" -C dist "$name"
(cd dist && shasum -a 256 "$name.tar.gz" > "$name.tar.gz.sha256")
(cd dist && shasum -a 256 -c "$name.tar.gz.sha256")
(cd "$package" && shasum -a 256 -c SHA256SUMS.txt)
lipo -verify_arch "$(uname -m)" "$package/wpm"
"$package/wpm" --version
echo "Experimental macOS candidate (not Developer ID signed/notarized): dist/$name.tar.gz"
echo 'Provide matching GPLv3 Corresponding Source before distribution. Nothing published.'