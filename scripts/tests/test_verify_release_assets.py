"""Synthetic tests for candidate inventory/provenance verification (no builds)."""

from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "verify_release_assets", Path(__file__).resolve().parents[1] / "verify-release-assets.py"
)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)
VERSION = "0.1.3-rc.1"
COMMIT = "a" * 40


class ReleaseAssetsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.source_name = f"wpm-v{VERSION}-source.zip"
        for suffix in verifier.ARCHIVES:
            path = self.directory / f"wpm-v{VERSION}-{suffix}"
            path.write_bytes(b"synthetic package, not a runnable binary")
            self.checksum(path)
        self.source_entries = {
            "Cargo.toml": f'[package]\nname = "wpm-cli"\nversion = "{VERSION}"\n',
            "Cargo.lock": f'[[package]]\nname = "wpm-cli"\nversion = "{VERSION}"\n',
            "SOURCE-BUILD.md": f"Exact Git commit: {COMMIT}\nPackage: wpm-cli {VERSION}\n",
            ".cargo/config.toml": '[source.vendored-sources]\ndirectory = "vendor"\n',
            "vendor/example/Cargo.toml": "# synthetic dependency\n",
        }
        self.write_source()

    def checksum(self, path):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        path.with_name(path.name + ".sha256").write_text(f"{digest}  {path.name}\n", encoding="ascii")

    def write_source(self):
        path = self.directory / self.source_name
        with zipfile.ZipFile(path, "w") as archive:
            for name, contents in self.source_entries.items():
                entry = zipfile.ZipInfo(name)
                # Preserve intentionally unsafe separators even on Windows,
                # where ZipInfo's constructor otherwise normalizes them.
                entry.filename = name
                archive.writestr(entry, contents)
        self.checksum(path)

    def verify(self, **kwargs):
        with redirect_stdout(io.StringIO()):
            verifier.verify(self.directory, VERSION, COMMIT, **kwargs)

    def test_complete_candidate(self):
        self.verify()

    def test_missing_platform(self):
        (self.directory / f"wpm-v{VERSION}-linux-x64.tar.gz").unlink()
        with self.assertRaisesRegex(ValueError, "inventory mismatch"):
            self.verify()

    def test_unexpected_asset(self):
        (self.directory / "old-package.zip").write_bytes(b"stale")
        with self.assertRaisesRegex(ValueError, "unexpected"):
            self.verify()

    def test_corrupt_archive(self):
        (self.directory / f"wpm-v{VERSION}-macos-x64.tar.gz").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            self.verify()

    def test_sidecar_cannot_name_another_file(self):
        path = self.directory / (self.source_name + ".sha256")
        path.write_text("a" * 64 + "  different.zip\n", encoding="ascii")
        with self.assertRaisesRegex(ValueError, "Invalid checksum sidecar"):
            self.verify()

    def test_source_commit_must_match(self):
        self.source_entries["SOURCE-BUILD.md"] = "Exact Git commit: " + "b" * 40
        self.write_source()
        with self.assertRaisesRegex(ValueError, "commit/version mismatch"):
            self.verify()

    def test_source_manifest_version_must_match(self):
        self.source_entries["Cargo.toml"] = '[package]\nname = "wpm-cli"\nversion = "9.9.9"\n'
        self.write_source()
        with self.assertRaisesRegex(ValueError, "manifest package/version mismatch"):
            self.verify()

    def test_source_lock_version_must_match(self):
        self.source_entries["Cargo.lock"] = '[[package]]\nname = "wpm-cli"\nversion = "9.9.9"\n'
        self.write_source()
        with self.assertRaisesRegex(ValueError, "lockfile package/version mismatch"):
            self.verify()

    def test_source_requires_vendor(self):
        del self.source_entries["vendor/example/Cargo.toml"]
        self.write_source()
        with self.assertRaisesRegex(ValueError, "missing vendored"):
            self.verify()

    def test_unsafe_source_path(self):
        for name in ("../escape", "/absolute", "C:/escape", "vendor\\escape"):
            with self.subTest(name=name):
                self.source_entries[name] = "bad"
                self.write_source()
                with self.assertRaisesRegex(ValueError, "Unsafe source ZIP entry"):
                    self.verify()
                del self.source_entries[name]

    def test_source_only_mode(self):
        for path in self.directory.iterdir():
            if path.name not in (self.source_name, self.source_name + ".sha256"):
                path.unlink()
        self.verify(source_only=True)
        with self.assertRaisesRegex(ValueError, "inventory mismatch"):
            self.verify()

    def test_reject_invalid_identity_arguments(self):
        with self.assertRaisesRegex(ValueError, "Cargo version"):
            verifier.verify(self.directory, "../escape", COMMIT)
        with self.assertRaisesRegex(ValueError, "full lowercase Git commit SHA"):
            verifier.verify(self.directory, VERSION, "abcd123")


if __name__ == "__main__":
    unittest.main()
