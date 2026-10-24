#!/usr/bin/env python3
"""Verify a downloaded candidate's exact inventory, checksums, and source identity.

Uses only Python 3.11+ standard-library modules; does not build or run binaries.
"""

import argparse
import hashlib
from pathlib import Path
import re
import tomllib
import zipfile


ARCHIVES = (
    "windows-x64.zip",
    "linux-x64.tar.gz",
    "macos-arm64.tar.gz",
    "macos-x64.tar.gz",
    "source.zip",
)


def verify(directory: Path, version: str, commit: str, source_only: bool = False) -> None:
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?", version):
        raise ValueError("Expected a plain or pre-release Cargo version")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Expected the full lowercase Git commit SHA")
    archives = [f"wpm-v{version}-{name}" for name in (
        ("source.zip",) if source_only else ARCHIVES
    )]
    expected = {name for archive in archives for name in (archive, archive + ".sha256")}
    actual = {path.name for path in directory.iterdir()}
    if actual != expected:
        raise ValueError(
            f"Asset inventory mismatch: missing={sorted(expected - actual)}, "
            f"unexpected={sorted(actual - expected)}"
        )
    for name in sorted(expected):
        path = directory / name
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"Asset must be a regular file: {name}")
    for name in archives:
        fields = (directory / (name + ".sha256")).read_text(encoding="ascii").split()
        if len(fields) != 2 or fields[1] != name:
            raise ValueError(f"Invalid checksum sidecar for {name}")
        with (directory / name).open("rb") as archive_file:
            digest = hashlib.file_digest(archive_file, "sha256").hexdigest()
        if fields[0].lower() != digest:
            raise ValueError(f"SHA-256 mismatch: {name}")
        print(f"SHA256 {digest}  {name}")
    with zipfile.ZipFile(directory / f"wpm-v{version}-source.zip") as source:
        # Guard the subsequent CI extraction as well as validating identity.
        names = source.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate source ZIP entries")
        for entry in source.infolist():
            parts = entry.filename.split("/")
            if (
                entry.orig_filename != entry.filename
                or entry.filename.startswith("/")
                or "\\" in entry.filename
                or any(part in ("..", ".") or ":" in part for part in parts)
                or (entry.external_attr >> 16) & 0o170000 == 0o120000
            ):
                raise ValueError(f"Unsafe source ZIP entry: {entry.filename}")
        manifest = tomllib.loads(source.read("Cargo.toml").decode("utf-8"))
        if manifest["package"]["name"] != "wpm-cli" or manifest["package"]["version"] != version:
            raise ValueError("Source manifest package/version mismatch")
        lock = tomllib.loads(source.read("Cargo.lock").decode("utf-8"))
        packages = [p for p in lock["package"] if p["name"] == "wpm-cli"]
        if len(packages) != 1 or packages[0]["version"] != version:
            raise ValueError("Source lockfile package/version mismatch")
        record = source.read("SOURCE-BUILD.md").decode("utf-8-sig").splitlines()
        if f"Exact Git commit: {commit}" not in record or f"Package: wpm-cli {version}" not in record:
            raise ValueError("Source build record commit/version mismatch")
        if ".cargo/config.toml" not in names or not any(n.startswith("vendor/") for n in names):
            raise ValueError("Source ZIP is missing vendored dependencies/configuration")
    print(f"Verified {len(expected)} assets for wpm {version} at {commit}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--source-only", action="store_true", help="Check just the source ZIP and sidecar")
    args = parser.parse_args()
    try:
        verify(args.directory, args.version, args.commit, args.source_only)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Release asset verification failed: {error}\n")


if __name__ == "__main__":
    main()
