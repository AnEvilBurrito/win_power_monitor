#!/usr/bin/env bash
# Verify a Linux candidate tarball: tarball checksum, member allowlist, inner
# binary checksum and ELF identity. Mirrors scripts/verify-package.ps1.
set -euo pipefail
cd "$(dirname "$0")/.."

tarball="${1:-dist/wpm-v0.1.2-linux-x64.tar.gz}"
if [ ! -f "$tarball" ]; then
  echo "verify-package-linux: no such archive: $tarball" >&2
  exit 1
fi
name="$(basename "$tarball" .tar.gz)"
expected="$(cut -d' ' -f1 "${tarball}.sha256")"
actual="$(sha256sum "$tarball" | cut -d' ' -f1)"
[ "$actual" = "$expected" ] || { echo "verify-package-linux: tarball checksum mismatch" >&2; exit 1; }

staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT
tar -xzf "$tarball" -C "$staging"
package="$staging/$name"

allowlist="$(printf '%s\n' LICENSE README.md SHA256SUMS.txt docs/promo.png wpm | sort)"
members="$(cd "$package" && find . -type f | sed 's|^\./||' | sort)"
[ "$members" = "$allowlist" ] || {
  echo "verify-package-linux: unexpected archive entries: $members" >&2
  exit 1
}

[ -x "$package/wpm" ] || { echo "verify-package-linux: wpm is not executable" >&2; exit 1; }
expected_inner="$(cut -d' ' -f1 "$package/SHA256SUMS.txt")"
actual_inner="$(sha256sum "$package/wpm" | cut -d' ' -f1)"
[ "$actual_inner" = "$expected_inner" ] || { echo "verify-package-linux: binary checksum mismatch" >&2; exit 1; }

magic="$(od -An -tx1 -N4 "$package/wpm" | tr -d ' \n')"
[ "$magic" = "7f454c46" ] || { echo "verify-package-linux: not an ELF binary" >&2; exit 1; }
machine="$(od -An -tu2 -j18 -N2 "$package/wpm" | tr -d ' ')"
[ "$machine" = "62" ] || { echo "verify-package-linux: not an x86-64 ELF (e_machine=$machine)" >&2; exit 1; }

echo "Verified: x86-64 ELF, archive allowlist and checksums. These assets are unsigned."
