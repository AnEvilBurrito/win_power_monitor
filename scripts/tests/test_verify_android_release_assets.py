"""Synthetic tests for Android release asset verification (no builds or SDKs)."""

from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "verify_android_release_assets",
    Path(__file__).resolve().parents[1] / "verify-android-release-assets.py",
)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)

VERSION = "0.7.0-rc.1"
VERSION_CODE = 11
COMMIT = "a" * 40
APPLICATION_ID = "io.github.anevilburrito.wpm"
CERTIFICATE = "D3D54F63925EAD1F229259CA6C711A403DDCE9D06B7B7C76CDFF699C516EF6CF"
SOURCE_NAME = f"wpm-android-v{VERSION}-source.zip"
APK_NAME = f"wpm-android-v{VERSION}.apk"
GRADLE = f"""android {{
    namespace = "{APPLICATION_ID}"
    defaultConfig {{
        applicationId = "{APPLICATION_ID}"
        versionCode = {VERSION_CODE}
        versionName = "{VERSION}"
    }}
}}
"""


class AndroidReleaseAssetsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.apk = self.directory / APK_NAME
        self.apk.write_bytes(b"synthetic APK, not a runnable package")
        self.checksum(self.apk)
        self.apk_digest = self.digest(self.apk.read_bytes())

        self.jar = b"synthetic source JAR"
        self.pom = b"<project><name>synthetic</name></project>"
        self.native = b"synthetic pinned native source archive"
        self.source_entries = {
            "LICENSE": "GNU GPL v3 synthetic license text\n",
            "SOURCE-BUILD.md": f"Exact Git commit: {COMMIT}\nVersion: {VERSION}\n",
            "android/gradlew": "#!/bin/sh\n",
            "android/gradle/wrapper/gradle-wrapper.jar": b"synthetic wrapper jar",
            "android/gradle/wrapper/gradle-wrapper.properties": (
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip\n"
            ),
            "android/settings.gradle.kts": 'rootProject.name = "wpm-android"\n',
            "android/build.gradle.kts": 'plugins { id("com.android.application") version "8.13.2" apply false }\n',
            "android/app/build.gradle.kts": GRADLE,
            "android/gradle.properties": "android.useAndroidX=true\n",
            "android/app/gradle.lockfile": "synthetic lockfile\n",
            "android/release-dependencies.gradle.kts": "// synthetic export task\n",
            "android/dependency-sources/LICENSE-APACHE-2.0.txt": "Apache License 2.0 synthetic text\n",
            "android/dependency-sources/files/core-ktx-1.17.0-sources.jar": self.jar,
            "android/dependency-sources/files/core-ktx-1.17.0.pom": self.pom,
            "android/dependency-sources/native/graphics-path-1.0.1.tar.gz": self.native,
        }
        self.manifest = {
            "schema": 1,
            "dependencies": [
                {
                    "coordinate": "androidx.core:core-ktx:1.17.0",
                    "source": "files/core-ktx-1.17.0-sources.jar",
                    "source_sha256": self.digest(self.jar),
                    "pom": "files/core-ktx-1.17.0.pom",
                    "pom_sha256": self.digest(self.pom),
                    "licenses": [
                        {"name": "Apache-2.0", "url": "https://www.apache.org/licenses/LICENSE-2.0.txt"}
                    ],
                }
            ],
            "native_sources": [
                {
                    "coordinate": verifier.NATIVE_COORDINATE,
                    "path": "native/graphics-path-1.0.1.tar.gz",
                    "sha256": self.digest(self.native),
                    "provenance": "pinned source/build inputs from the Maven release",
                }
            ],
        }
        self.metadata = {
            "schema": 1,
            "version": VERSION,
            "version_code": VERSION_CODE,
            "application_id": APPLICATION_ID,
            "commit": COMMIT,
            "certificate_sha256": CERTIFICATE,
            "apk_sha256": self.apk_digest,
            "abis": list(verifier.ABIS),
            "toolchain": dict(verifier.TOOLCHAIN),
            "checks": {name: True for name in verifier.CHECKS},
        }
        self.publish()

    @staticmethod
    def digest(data):
        return hashlib.sha256(data).hexdigest()

    def checksum(self, path):
        data = path.read_bytes()
        path.with_name(path.name + ".sha256").write_text(
            f"{self.digest(data)}  {path.name}\n", encoding="ascii"
        )

    def publish(self):
        self.source_entries["ANDROID-RELEASE.json"] = json.dumps(self.metadata)
        self.source_entries["android/dependency-sources/manifest.json"] = json.dumps(self.manifest)
        self.write_source()

    def write_source(self, entries=None, modes=None):
        path = self.directory / SOURCE_NAME
        path.unlink(missing_ok=True)
        selected = self.source_entries if entries is None else entries
        with zipfile.ZipFile(path, "w") as archive:
            for name, contents in sorted(selected.items()):
                entry = zipfile.ZipInfo(name)
                entry.filename = name
                if modes and name in modes:
                    entry.external_attr = modes[name] << 16
                archive.writestr(entry, contents)
        self.checksum(path)

    def append_duplicate(self, name, contents="duplicate entry"):
        path = self.directory / SOURCE_NAME
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            with zipfile.ZipFile(path, "a") as archive:
                archive.writestr(name, contents)
        self.checksum(path)

    def set_gradle(self, text):
        self.source_entries["android/app/build.gradle.kts"] = text

    def verify(self, **overrides):
        arguments = {
            "directory": self.directory,
            "version": VERSION,
            "version_code": VERSION_CODE,
            "commit": COMMIT,
            "application_id": APPLICATION_ID,
            "certificate_sha256": CERTIFICATE,
        }
        arguments.update(overrides)
        with redirect_stdout(io.StringIO()):
            return verifier.verify(**arguments)

    def assert_fails(self, pattern, **overrides):
        with self.assertRaisesRegex(ValueError, pattern):
            self.verify(**overrides)

    # --- Complete candidate and CLI -------------------------------------

    def test_complete_candidate(self):
        self.verify()

    def test_cli_accepts_complete_candidate(self):
        script = Path(verifier.__file__).resolve()
        result = subprocess.run(
            [
                sys.executable, "-B", str(script), str(self.directory),
                "--version", VERSION,
                "--version-code", str(VERSION_CODE),
                "--commit", COMMIT,
                "--application-id", APPLICATION_ID,
                "--certificate-sha256", CERTIFICATE,
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Verified 4 assets", result.stdout)
        self.assertIn("not re-performed", result.stdout)

    def test_cli_reports_failure(self):
        self.apk.write_bytes(b"tampered")
        script = Path(verifier.__file__).resolve()
        result = subprocess.run(
            [
                sys.executable, "-B", str(script), str(self.directory),
                "--version", VERSION,
                "--version-code", str(VERSION_CODE),
                "--commit", COMMIT,
                "--application-id", APPLICATION_ID,
                "--certificate-sha256", CERTIFICATE,
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("SHA-256 mismatch", result.stderr)

    # --- Arguments and inventory ----------------------------------------

    def test_invalid_arguments(self):
        cases = (
            ({"version": "../escape"}, "Android versionName"),
            ({"version": "1.0"}, "Android versionName"),
            ({"version_code": 0}, "versionCode between"),
            ({"version_code": verifier.VERSION_CODE_MAX + 1}, "versionCode between"),
            ({"version_code": True}, "versionCode between"),
            ({"commit": "abcd123"}, "lowercase Git commit SHA"),
            ({"commit": "A" * 40}, "lowercase Git commit SHA"),
            ({"application_id": "nodots"}, "reverse-DNS"),
            ({"application_id": "io..wpm"}, "reverse-DNS"),
            ({"certificate_sha256": "abc"}, "certificate SHA-256"),
            ({"certificate_sha256": "G" * 64}, "certificate SHA-256"),
        )
        for overrides, pattern in cases:
            with self.subTest(overrides=overrides):
                self.assert_fails(pattern, **overrides)

    def test_missing_asset(self):
        (self.directory / (APK_NAME + ".sha256")).unlink()
        self.assert_fails("inventory mismatch")

    def test_unexpected_asset(self):
        (self.directory / "stale.apk").write_bytes(b"stale")
        self.assert_fails("unexpected")

    def test_asset_must_be_regular_file(self):
        self.apk.unlink()
        self.apk.mkdir()
        self.assert_fails("regular file")

    def test_empty_asset(self):
        self.apk.write_bytes(b"")
        self.checksum(self.apk)
        self.assert_fails("must not be empty")

    def test_checksum_mismatch(self):
        self.apk.write_bytes(b"tampered APK")
        self.assert_fails("SHA-256 mismatch")

    def test_sidecar_requires_exact_basename(self):
        path = self.directory / (SOURCE_NAME + ".sha256")
        path.write_text(f"{self.digest(path.with_name(SOURCE_NAME).read_bytes())}  other.zip\n",
                        encoding="ascii")
        self.assert_fails("Invalid checksum sidecar")

    def test_sidecar_requires_lowercase_hash(self):
        path = self.directory / (APK_NAME + ".sha256")
        path.write_text(f"{self.apk_digest.upper()}  {APK_NAME}\n", encoding="ascii")
        self.assert_fails("Invalid checksum sidecar")

    def test_sidecar_requires_two_spaces(self):
        path = self.directory / (APK_NAME + ".sha256")
        path.write_text(f"{self.apk_digest} {APK_NAME}\n", encoding="ascii")
        self.assert_fails("Invalid checksum sidecar")

    # --- Source ZIP safety ----------------------------------------------

    def test_unsafe_source_paths(self):
        for name in (
            "../escape",
            "/absolute",
            "C:/escape",
            "android\\escape",
            "android/./dot",
            "android//empty",
            "android/../up",
        ):
            with self.subTest(name=name):
                self.source_entries[name] = "bad"
                self.write_source()
                self.assert_fails("Unsafe source ZIP entry")
                del self.source_entries[name]
                self.publish()

    def test_duplicate_source_entry(self):
        self.append_duplicate("LICENSE")
        self.assert_fails("Duplicate source ZIP entry")

    def test_casefold_duplicate_source_entry(self):
        self.source_entries["license"] = "duplicate"
        self.write_source()
        self.assert_fails("Case-insensitive duplicate")

    def test_symlink_source_entry(self):
        self.source_entries["android/link"] = "target"
        self.write_source(modes={"android/link": 0o120777})
        self.assert_fails("Symlink source ZIP entry")

    def test_secret_source_entries(self):
        for name in (
            "android/local.properties",
            "LOCAL.PROPERTIES",
            "android/keystore.properties",
            "android/app/release.p12",
            "android/upload.jks",
            "android/certs/release.keystore",
        ):
            with self.subTest(name=name):
                self.source_entries[name] = "secret"
                self.write_source()
                self.assert_fails("Secret/signing file")
                del self.source_entries[name]
                self.publish()

    def test_build_cache_source_entries(self):
        for name in (
            "android/app/build/outputs/apk/release/app-release.apk",
            "android/app/.externalNativeBuild/cmake/release/x.json",
            "android/app/build/generated/source/buildConfig/release/x.java",
            ".gradle/caches/modules.bin",
            "android/.kotlin/sessions/x",
            ".git/config",
            "android/app/__pycache__/x.pyc",
        ):
            with self.subTest(name=name):
                self.source_entries[name] = "generated"
                self.write_source()
                self.assert_fails("Build/cache directory")
                del self.source_entries[name]
                self.publish()

    def test_inflated_size_bounds(self):
        self.source_entries["android/large.bin"] = b"x" * 64
        self.write_source()
        with mock.patch.object(verifier, "MAX_ENTRY_UNCOMPRESSED", 32):
            self.assert_fails("Oversized source ZIP entry")
        with mock.patch.object(verifier, "MAX_TOTAL_UNCOMPRESSED", 32):
            self.assert_fails("inflated size")

    def test_invalid_source_zip(self):
        path = self.directory / SOURCE_NAME
        path.write_bytes(b"not a zip archive")
        self.checksum(path)
        with self.assertRaises(zipfile.BadZipFile):
            self.verify()

    # --- Required source/build files ------------------------------------

    def test_missing_required_files(self):
        for name in verifier.REQUIRED_FILES:
            with self.subTest(name=name):
                contents = self.source_entries.pop(name)
                self.write_source()
                self.assert_fails("missing required file")
                self.source_entries[name] = contents
                self.publish()

    def test_empty_required_file(self):
        self.source_entries["android/gradlew"] = ""
        self.write_source()
        self.assert_fails("required file is empty")

    # --- ANDROID-RELEASE.json -------------------------------------------

    def test_metadata_schema(self):
        self.metadata["schema"] = 2
        self.publish()
        self.assert_fails("schema must be 1")

    def test_metadata_identity(self):
        cases = (
            ("version", "9.9.9", "version mismatch"),
            ("version_code", 12, "version_code mismatch"),
            ("application_id", "io.github.example.wpm", "application_id mismatch"),
            ("commit", "b" * 40, "commit mismatch"),
        )
        for key, value, pattern in cases:
            with self.subTest(key=key):
                original = self.metadata[key]
                self.metadata[key] = value
                self.publish()
                self.assert_fails(pattern)
                self.metadata[key] = original
                self.publish()

    def test_metadata_certificate(self):
        self.metadata["certificate_sha256"] = CERTIFICATE.lower()
        self.publish()
        self.assert_fails("uppercase hex")
        self.metadata["certificate_sha256"] = "A" * 64
        self.publish()
        self.assert_fails("certificate_sha256 mismatch")

    def test_metadata_apk_hash(self):
        self.metadata["apk_sha256"] = self.apk_digest.upper()
        self.publish()
        self.assert_fails("lowercase hex")
        self.metadata["apk_sha256"] = "b" * 64
        self.publish()
        self.assert_fails("does not match the APK")

    def test_metadata_abis(self):
        for abis in (
            list(verifier.ABIS)[:-1],
            list(reversed(verifier.ABIS)),
            list(verifier.ABIS) + ["riscv64"],
        ):
            with self.subTest(abis=abis):
                self.metadata["abis"] = abis
                self.publish()
                self.assert_fails("abis mismatch")
        self.metadata["abis"] = list(verifier.ABIS)
        self.publish()

    def test_metadata_checks(self):
        self.metadata["checks"]["zip_alignment_16k"] = False
        self.publish()
        self.assert_fails("check not satisfied: zip_alignment_16k")
        self.metadata["checks"]["zip_alignment_16k"] = True
        del self.metadata["checks"]["apk_signature"]
        self.publish()
        self.assert_fails("keys mismatch")

    def test_metadata_toolchain(self):
        self.metadata["toolchain"]["gradle"] = "8.12"
        self.publish()
        self.assert_fails("toolchain.gradle mismatch")
        self.metadata["toolchain"] = dict(verifier.TOOLCHAIN)
        del self.metadata["toolchain"]["compile_sdk"]
        self.publish()
        self.assert_fails("keys mismatch")

    def test_metadata_unknown_key(self):
        self.metadata["extra"] = True
        self.publish()
        self.assert_fails("keys mismatch")

    def test_metadata_invalid_json(self):
        self.source_entries["ANDROID-RELEASE.json"] = "{not json"
        self.write_source()
        self.assert_fails("Invalid JSON")

    # --- Gradle identity ------------------------------------------------

    def test_gradle_version_name(self):
        self.set_gradle(GRADLE.replace(f'versionName = "{VERSION}"', 'versionName = "9.9.9"'))
        self.write_source()
        self.assert_fails("Gradle versionName mismatch")

    def test_gradle_version_code(self):
        self.set_gradle(GRADLE.replace(f"versionCode = {VERSION_CODE}", "versionCode = 12"))
        self.write_source()
        self.assert_fails("Gradle versionCode mismatch")

    def test_gradle_application_id(self):
        self.set_gradle(GRADLE.replace(
            f'applicationId = "{APPLICATION_ID}"', 'applicationId = "io.github.example.wpm"'
        ))
        self.write_source()
        self.assert_fails("Gradle applicationId mismatch")

    def test_gradle_requires_single_identity(self):
        self.set_gradle(GRADLE + f'versionName = "{VERSION}"\n')
        self.write_source()
        self.assert_fails("exactly one versionName")
        self.set_gradle(GRADLE.replace(f'versionName = "{VERSION}"\n', ""))
        self.write_source()
        self.assert_fails("exactly one versionName")

    # --- Dependency manifest --------------------------------------------

    def test_dependency_manifest_schema_and_entries(self):
        self.manifest["schema"] = 2
        self.publish()
        self.assert_fails("manifest schema must be 1")
        self.manifest["schema"] = 1
        self.manifest["dependencies"] = []
        self.publish()
        self.assert_fails("at least one dependency")

    def test_dependency_coordinates(self):
        duplicate = dict(self.manifest["dependencies"][0])
        self.manifest["dependencies"].append(duplicate)
        self.publish()
        self.assert_fails("Duplicate dependency coordinate")
        self.manifest["dependencies"][1]["coordinate"] = "androidx.core:CORE-KTX:1.17.0"
        self.publish()
        self.assert_fails("Duplicate dependency coordinate")
        self.manifest["dependencies"][1]["coordinate"] = "nocolon"
        self.publish()
        self.assert_fails("group:name:version")

    def test_dependency_paths(self):
        for path in ("../escape.jar", "/absolute.jar", "files\\win.jar"):
            with self.subTest(path=path):
                self.manifest["dependencies"][0]["source"] = path
                self.publish()
                self.assert_fails("Unsafe")
        self.manifest["dependencies"][0]["source"] = "files/not-a-jar.zip"
        self.publish()
        self.assert_fails("must be a .jar")

    def test_dependency_archives_must_exist(self):
        self.manifest["dependencies"][0]["source"] = "files/missing.jar"
        self.publish()
        self.assert_fails("missing from the source ZIP")
        self.manifest["dependencies"][0]["source"] = "files/core-ktx-1.17.0-sources.jar"
        self.manifest["dependencies"][0]["pom"] = "files/missing.pom"
        self.publish()
        self.assert_fails("missing from the source ZIP")

    def test_dependency_hashes(self):
        self.manifest["dependencies"][0]["source_sha256"] = "b" * 64
        self.publish()
        self.assert_fails("SHA-256 mismatch")
        self.manifest["dependencies"][0]["source_sha256"] = self.digest(self.jar)
        self.manifest["dependencies"][0]["pom_sha256"] = "C" * 64
        self.publish()
        self.assert_fails("lowercase hex")

    def test_dependency_licenses(self):
        self.manifest["dependencies"][0]["licenses"] = []
        self.publish()
        self.assert_fails("licenses must be nonempty")
        self.manifest["dependencies"][0]["licenses"] = [{"name": "", "url": "https://x"}]
        self.publish()
        self.assert_fails("nonempty string")

    def test_dependency_unknown_key(self):
        self.manifest["dependencies"][0]["extra"] = 1
        self.publish()
        self.assert_fails("keys mismatch")

    def test_native_sources_required(self):
        self.manifest["native_sources"] = []
        self.publish()
        self.assert_fails("native_sources must be a nonempty list")
        del self.manifest["native_sources"]
        self.publish()
        self.assert_fails("keys mismatch")

    def test_native_source_coordinate_required(self):
        self.manifest["native_sources"][0]["coordinate"] = "androidx.graphics:graphics-path:1.0.0"
        self.publish()
        self.assert_fails("must record native sources for")
        self.manifest["native_sources"][0]["coordinate"] = "invalid"
        self.publish()
        self.assert_fails("group:name:version")

    def test_native_source_archive_validation(self):
        item = self.manifest["native_sources"][0]
        item["path"] = "../escape.tar.gz"
        self.publish()
        self.assert_fails("Unsafe")
        item["path"] = "native/missing.tar.gz"
        self.publish()
        self.assert_fails("missing from the source ZIP")
        item["path"] = "native/graphics-path-1.0.1.tar.gz"
        item["sha256"] = "b" * 64
        self.publish()
        self.assert_fails("Native source archive SHA-256 mismatch")
        item["sha256"] = self.digest(self.native)
        item["provenance"] = ""
        self.publish()
        self.assert_fails("provenance must be a nonempty string")

    def test_dependency_level_native_sources(self):
        self.manifest["dependencies"][0]["native_sources"] = [
            {
                "path": "native/graphics-path-1.0.1.tar.gz",
                "sha256": self.digest(self.native),
                "provenance": "dependency record native inputs",
            }
        ]
        self.publish()
        self.verify()
        self.manifest["dependencies"][0]["native_sources"][0]["sha256"] = "b" * 64
        self.publish()
        self.assert_fails("Native source archive SHA-256 mismatch")

    def test_dependency_level_native_sources_must_be_list(self):
        self.manifest["dependencies"][0]["native_sources"] = {}
        self.publish()
        self.assert_fails("native_sources must be a list")

    def test_dependency_manifest_invalid_json(self):
        self.source_entries["android/dependency-sources/manifest.json"] = "[]"
        self.write_source()
        self.assert_fails("must be a JSON object")


if __name__ == "__main__":
    unittest.main()
