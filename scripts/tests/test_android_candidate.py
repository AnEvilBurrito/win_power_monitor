"""Signing orchestration guards; no real private key or SDK used here."""
import base64
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

SPEC = importlib.util.spec_from_file_location("candidate", Path(__file__).resolve().parents[1] / "android-candidate.py")
candidate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(candidate)
SHA = "a" * 40
VERSION = "0.7.0-rc.1"
APP = "io.github.anevilburrito.wpm"


class CandidateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.input = self.root / "input"
        self.input.mkdir()
        (self.input / "unsigned.apk").write_bytes(b"synthetic unsigned test APK")
        with zipfile.ZipFile(self.input / "source-input.zip", "w") as z:
            z.writestr("android/app/build.gradle.kts", f'applicationId = "{APP}"\nversionName = "{VERSION}"\nversionCode = 11\n')
            z.writestr("SOURCE-BUILD.md", f"Exact Git commit: {SHA}\n")
        self.data = {"schema": 1, "commit": SHA, "version": VERSION, "version_code": 11,
                     "application_id": APP, "toolchain": candidate.TOOLCHAIN,
                     "checks": {key: True for key in candidate.BUILD_CHECKS},
                     "unsigned_apk_sha256": candidate.digest(self.input / "unsigned.apk"),
                     "source_input_sha256": candidate.digest(self.input / "source-input.zip")}
        self.save()

    def save(self):
        candidate.write_json(self.input / "build.json", self.data)

    def test_valid_input(self):
        self.assertEqual(candidate.validate_input(self.input, SHA), self.data)

    def test_wrong_run_sha(self):
        with self.assertRaisesRegex(ValueError, "SHA|mismatch"):
            candidate.validate_input(self.input, "b" * 40)

    def test_wrong_checks_or_integer_truth(self):
        for value in (False, 1, None, "true"):
            with self.subTest(value=value):
                self.data["checks"]["source_rebuild"] = value
                self.save()
                with self.assertRaisesRegex(ValueError, "checks"):
                    candidate.validate_input(self.input, SHA)

    def test_missing_check(self):
        del self.data["checks"]["source_rebuild"]
        self.save()
        with self.assertRaisesRegex(ValueError, "checks"):
            candidate.validate_input(self.input, SHA)

    def test_corrupt_unsigned_apk(self):
        (self.input / "unsigned.apk").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            candidate.validate_input(self.input, SHA)

    def test_corrupt_source(self):
        with (self.input / "source-input.zip").open("ab") as f:
            f.write(b"changed")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            candidate.validate_input(self.input, SHA)

    def test_wrong_identity(self):
        self.data["application_id"] = APP + ".debug"
        self.save()
        with self.assertRaisesRegex(ValueError, "application ID"):
            candidate.validate_input(self.input, SHA)

    def test_no_development_version(self):
        self.data["version"] = "0.7.0-dev"
        self.save()
        with self.assertRaisesRegex(ValueError, "release version"):
            candidate.validate_input(self.input, SHA)

    def test_extra_input_file(self):
        (self.input / "release.p12").write_bytes(b"not a real key")
        with self.assertRaisesRegex(ValueError, "inventory"):
            candidate.validate_input(self.input, SHA)

    def test_missing_secret_fails_without_output(self):
        with patch.dict(candidate.os.environ, {}, clear=True):
            with self.assertRaisesRegex(ValueError, "secret is missing"):
                candidate.sign(SHA, self.input, self.root / "out")
        self.assertFalse((self.root / "out").exists())

    def test_unapproved_pin_fails_without_output(self):
        env = {k: "placeholder" for k in ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")}
        env["ANDROID_SIGNING_CERT_SHA256"] = "0" * 64
        with patch.dict(candidate.os.environ, env, clear=True):
            with self.assertRaisesRegex(ValueError, "pin differs"):
                candidate.sign(SHA, self.input, self.root / "out")
        self.assertFalse((self.root / "out").exists())

    def test_key_decode_handles_windows_whitespace(self):
        encoded = base64.b64encode(b"synthetic private-key placeholder").decode()
        self.assertEqual(candidate.decode_key("\r\n" + encoded[:8] + " \n" + encoded[8:]), b"synthetic private-key placeholder")

    def test_bad_key_encoding(self):
        for value in (None, "", "!notbase64", "a" * 100001):
            with self.assertRaises(ValueError):
                candidate.decode_key(value)

    def test_reports_require_actual_results(self):
        with self.assertRaisesRegex(ValueError, "missing"):
            candidate.release_reports(self.root)
        build = self.root / "android/app/build"
        tests = build / "test-results/testReleaseUnitTest"
        tests.mkdir(parents=True)
        report = tests / "TEST-example.xml"
        report.write_text('<testsuite tests="3" failures="0" errors="0" skipped="0"/>')
        lint = build / "reports"
        lint.mkdir()
        (lint / "lint-results-release.xml").write_text('<issues><issue severity="Warning"/></issues>')
        self.assertEqual(candidate.release_reports(self.root), {"tests": 3, "lint_warnings": 1})
        report.write_text('<testsuite tests="3" failures="1"/>')
        with self.assertRaisesRegex(ValueError, "failures"):
            candidate.release_reports(self.root)
        report.write_text('<testsuite tests="3"/>')
        (lint / "lint-results-release.xml").write_text('<issues><issue severity="Error"/></issues>')
        with self.assertRaisesRegex(ValueError, "lint"):
            candidate.release_reports(self.root)

    def test_generated_source_license_is_gpl_only(self):
        packager = candidate.load("package-android-source")
        text = packager._source_build_markdown(SHA, VERSION, 11, APP, 73)
        self.assertIn("GPL-3.0-only", text)
        self.assertNotIn("version 3 or later", text)
        self.assertIn("Third-party", text)


if __name__ == "__main__":
    unittest.main()
