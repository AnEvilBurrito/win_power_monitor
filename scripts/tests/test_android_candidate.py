"""Signing orchestration guards; no real private key or SDK used here."""
import base64
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

SPEC = importlib.util.spec_from_file_location("candidate", Path(__file__).resolve().parents[1] / "android-candidate.py")
assert SPEC is not None and SPEC.loader is not None
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
        self.data: dict = {"schema": 1, "commit": SHA, "version": VERSION, "version_code": 11,
                     "application_id": APP, "toolchain": candidate.TOOLCHAIN,
                     "checks": {key: True for key in candidate.BUILD_CHECKS},
                     "unsigned_apk_sha256": candidate.digest(self.input / "unsigned.apk")}
        self.save()
        identity = patch.object(candidate, "identity", return_value={
            "version": VERSION, "version_code": 11, "application_id": APP,
        })
        identity.start()
        self.addCleanup(identity.stop)

    def save(self):
        candidate.write_json(self.input / "build.json", self.data)

    def test_valid_input(self):
        self.assertEqual(candidate.validate_input(self.input, SHA), self.data)

    def test_record_build_requires_no_source_archive(self):
        destination = self.root / "built-input"
        apk = Mock()
        java = Mock(stdout='openjdk version "21.0.1"', stderr="")
        reports = {"tests": 3, "lint_warnings": 1}
        with patch.object(candidate.subprocess, "check_output", return_value=SHA + "\n"), \
             patch.object(candidate.subprocess, "run", return_value=java), \
             patch.object(candidate, "release_reports", return_value=reports), \
             patch.object(candidate, "load", return_value=apk) as load, \
             patch.object(candidate.shutil, "copyfile", side_effect=lambda _src, dst: Path(dst).write_bytes(b"unsigned test APK")):
            record = candidate.record_build(SHA, destination)
        self.assertEqual({p.name for p in destination.iterdir()}, {"unsigned.apk", "build.json"})
        self.assertEqual(record["reports"], reports)
        self.assertEqual(record["unsigned_apk_sha256"], candidate.digest(destination / "unsigned.apk"))
        self.assertEqual(record["checks"], {"release_unit_tests": True, "release_lint": True})
        load.assert_called_once_with("verify-android-apk")
        self.assertTrue(apk.verify.call_args.kwargs["unsigned"])

    def test_wrong_run_sha(self):
        with self.assertRaisesRegex(ValueError, "SHA|mismatch"):
            candidate.validate_input(self.input, "b" * 40)

    def test_wrong_checks_or_integer_truth(self):
        for value in (False, 1, None, "true"):
            with self.subTest(value=value):
                self.data["checks"]["release_unit_tests"] = value
                self.save()
                with self.assertRaisesRegex(ValueError, "checks"):
                    candidate.validate_input(self.input, SHA)

    def test_missing_check(self):
        del self.data["checks"]["release_lint"]
        self.save()
        with self.assertRaisesRegex(ValueError, "checks"):
            candidate.validate_input(self.input, SHA)

    def test_corrupt_unsigned_apk(self):
        (self.input / "unsigned.apk").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "hash mismatch"):
            candidate.validate_input(self.input, SHA)

    def test_legacy_source_input_is_rejected(self):
        (self.input / "source-input.zip").write_bytes(b"unused legacy source archive")
        with self.assertRaisesRegex(ValueError, "inventory"):
            candidate.validate_input(self.input, SHA)

    def test_metadata_must_match_release_checkout(self):
        self.data["version_code"] = 12
        self.save()
        with self.assertRaisesRegex(ValueError, "identity differs"):
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

    def fake_signing(self, *, signing_fails=False, verification_fails=False):
        runner = self.root / "runner-temp"
        runner.mkdir()
        env = {
            "ANDROID_KEYSTORE_BASE64": base64.b64encode(b"synthetic test key").decode(),
            "ANDROID_KEYSTORE_PASSWORD": "test-only-store-password",
            "ANDROID_KEY_ALIAS": "wpm-release",
            "ANDROID_KEY_PASSWORD": "test-only-key-password",
            "ANDROID_SIGNING_CERT_SHA256": candidate.PIN,
            "RUNNER_TEMP": str(runner),
        }
        tool = Mock()
        tool.resolve_tools.return_value = {"zipalign": "zipalign", "apksigner": "apksigner"}
        keys = []

        def run_tool(_executable, arguments, *, label):
            if label == "apksigner sign":
                key = Path(arguments[arguments.index("--ks") + 1])
                keys.append(key)
                self.assertTrue(key.is_file())
                self.assertEqual(key.parent.parent, runner)
                self.assertIn("env:ANDROID_KEYSTORE_PASSWORD", arguments)
                self.assertIn("env:ANDROID_KEY_PASSWORD", arguments)
                self.assertNotIn(env["ANDROID_KEY_PASSWORD"], arguments)
                if signing_fails:
                    return 1, "", "synthetic failure"
                Path(arguments[arguments.index("--out") + 1]).write_bytes(b"synthetic signed APK")
            return 0, "", ""

        def verify(path, **kwargs):
            self.assertTrue(keys)
            self.assertTrue(all(not key.exists() for key in keys))
            self.assertEqual(kwargs["certificate_sha256"], candidate.PIN)
            if verification_fails:
                raise ValueError("Synthetic APK verification failure")
            return {"certificate_sha256": candidate.PIN, "apk_sha256": candidate.digest(path),
                    "abis": [], "checks": {"apk_signature": True}}

        tool.run_tool.side_effect = run_tool
        tool.verify.side_effect = verify
        return env, tool, runner

    def test_sign_outputs_only_verified_apk_and_checksum(self):
        env, tool, runner = self.fake_signing()
        output = self.root / "public"
        with patch.dict(candidate.os.environ, env, clear=True), patch.object(candidate, "load", return_value=tool) as load:
            record = candidate.sign(SHA, self.input, output)
        self.assertEqual([call.args[0] for call in load.call_args_list], ["verify-android-apk"])
        name = f"wpm-android-v{VERSION}.apk"
        self.assertEqual({p.name for p in output.iterdir()}, {name, name + ".sha256"})
        expected = f"{candidate.digest(output / name)}  {name}\n"
        self.assertEqual((output / (name + ".sha256")).read_bytes(), expected.encode("ascii"))
        self.assertEqual(record["commit"], SHA)
        self.assertFalse(list(runner.iterdir()))

    def test_signing_failure_cleans_key_and_produces_no_checksum(self):
        env, tool, runner = self.fake_signing(signing_fails=True)
        output = self.root / "public"
        with patch.dict(candidate.os.environ, env, clear=True), patch.object(candidate, "load", return_value=tool):
            with self.assertRaisesRegex(ValueError, "signing failed"):
                candidate.sign(SHA, self.input, output)
        self.assertFalse(list(runner.iterdir()))
        self.assertFalse(list(output.glob("*.sha256")))
        tool.verify.assert_not_called()

    def test_apk_verification_failure_produces_no_checksum(self):
        env, tool, runner = self.fake_signing(verification_fails=True)
        output = self.root / "public"
        with patch.dict(candidate.os.environ, env, clear=True), patch.object(candidate, "load", return_value=tool):
            with self.assertRaisesRegex(ValueError, "verification failure"):
                candidate.sign(SHA, self.input, output)
        self.assertFalse(list(runner.iterdir()))
        self.assertFalse(list(output.glob("*.sha256")))


class WorkflowTests(unittest.TestCase):
    def test_workflow_uses_apk_only_path(self):
        root = Path(__file__).resolve().parents[2]
        text = (root / ".github/workflows/android-candidate.yml").read_text(encoding="utf-8")
        for legacy in ("package-android-source", "exportReleaseDependencies", "source-input.zip", "android-source-check", "--rebuilt", "*-source.zip"):
            with self.subTest(legacy=legacy):
                self.assertNotIn(legacy, text)
        self.assertIn("-Tasks ':app:testReleaseUnitTest', ':app:lintRelease', ':app:assembleRelease'", text)
        self.assertIn("environment: android-release", text)
        self.assertIn("needs: build", text)
        self.assertEqual(text.count("            dist/android-release/"), 2)


if __name__ == "__main__":
    unittest.main()
