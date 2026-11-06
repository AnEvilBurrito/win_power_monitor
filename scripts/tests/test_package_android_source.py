"""Synthetic tests for the Android release source packager (no network, no builds).

Every Git read, dependency export, native archive and APK is fabricated in a
temporary directory; the pinned graphics-path hash is monkeypatched to a
synthetic archive so nothing here touches the network.
"""

from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "package_android_source",
    Path(__file__).resolve().parents[1] / "package-android-source.py",
)
assert SPEC is not None and SPEC.loader is not None
packager = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(packager)

verifier = packager.verifier()

VERSION = "0.7.0-rc.1"
VERSION_CODE = 11
APPLICATION_ID = "io.github.anevilburrito.wpm"
CERTIFICATE = "D3D54F63925EAD1F229259CA6C711A403DDCE9D06B7B7C76CDFF699C516EF6CF"
COMMIT = "0123456789abcdef0123456789abcdef01234567"
OTHER_COMMIT = "fedcba9876543210fedcba9876543210fedcba98"
GRADLE = f"""plugins {{
    id("com.android.application")
}}

android {{
    namespace = "{APPLICATION_ID}"

    defaultConfig {{
        applicationId = "{APPLICATION_ID}"
        versionCode = {VERSION_CODE}
        versionName = "{VERSION}"
    }}

    buildTypes {{
        debug {{
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }}
        release {{
            isMinifyEnabled = false
        }}
    }}
}}
"""
PINNED_NATIVE_SHA256 = (
    "a19ec7be5315acbece6fec9e1b6b29fca2397db8f7435eae0967bd2f17c76949"
)
PINNED_NATIVE_URL = (
    "https://android.googlesource.com/platform/frameworks/support/+archive/"
    "8a05a22af450d589ef911d772a001a49dcb05b71/graphics/graphics-path.tar.gz"
)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def zip_bytes(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return buffer.getvalue()


def native_tar_bytes(entries=None):
    """Synthetic stand-in for the pinned graphics-path native source archive."""
    if entries is None:
        entries = {
            "build.gradle": "// androidx graphics-path native module\n",
            "src/main/cpp/CMakeLists.txt": "cmake_minimum_required(VERSION 3.22)\n",
            "src/main/cpp/GraphicsPath.cpp": '#include "GraphicsPath.h"\n',
            "src/main/cpp/GraphicsPath.h": "#pragma once\n",
            "src/main/cpp/math/Bezier.cpp": "// bezier math\n",
            "src/main/cpp/math/Bezier.h": "#pragma once\n",
        }
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w:gz") as archive:
        for name, content in entries.items():
            data = content.encode("utf-8")
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    return buffer.getvalue()


class FakeGit:
    """Stand-in for GitRepository over an in-memory commit tree."""

    def __init__(self, commit, files):
        self.commit = commit
        self.blobs = {}
        self.tree = []
        for path, (mode, data) in files.items():
            oid = hashlib.sha1(data).hexdigest()
            self.blobs[oid] = data
            self.tree.append(packager.TreeEntry(mode, "blob", oid, path))

    def resolve_commit(self, commit):
        return self.commit

    def list_tree(self, commit):
        return list(self.tree)

    def read_blob(self, oid):
        return self.blobs[oid]


class PinnedContractTests(unittest.TestCase):
    def test_pinned_native_constants(self):
        self.assertEqual(packager.NATIVE_SHA256, PINNED_NATIVE_SHA256)
        self.assertEqual(packager.NATIVE_URL, PINNED_NATIVE_URL)
        self.assertEqual(packager.NATIVE_COORDINATE, verifier.NATIVE_COORDINATE)
        self.assertEqual(packager.NATIVE_PATH, "native/graphics-path-1.0.1.tar.gz")
        self.assertIn(
            "#graphics-path-1.0.1", packager.NATIVE_RELEASE_NOTES_URL
        )


class PackagingTestCase(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()

        self.native = native_tar_bytes()
        self.native_sha = digest(self.native)
        for name, value in (
            ("NATIVE_SHA256", self.native_sha),
            ("NATIVE_URL", "https://example.invalid/graphics-path.tar.gz"),
        ):
            patcher = mock.patch.object(packager, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

        self.jar = zip_bytes({"example/Widget.java": "package example;\n"})
        self.pom = b"<project><name>synthetic</name></project>"
        self.dependencies_path = self.root / "release-dependencies"
        self.manifest_extra = []
        self.manifest_native = None
        self.extra_dependency_files = {}

    # -- fixtures ---------------------------------------------------------- #
    def committed_files(self):
        return {
            "LICENSE": ("100644", b"GNU GENERAL PUBLIC LICENSE Version 3 synthetic\n"),
            "README.md": ("100644", b"# wpm\n\nBattery monitor.\n"),
            "android/README.md": ("100644", b"# wpm-android\n"),
            "android/build.gradle.kts": (
                "100644",
                b'plugins { id("com.android.application") version "8.13.2" apply false }\n',
            ),
            "android/settings.gradle.kts": (
                "100644",
                b'rootProject.name = "wpm-android"\ninclude(":app")\n',
            ),
            "android/gradle.properties": ("100644", b"android.useAndroidX=true\n"),
            "android/gradlew": ("100755", b"#!/bin/sh\nexec java -jar wrapper.jar\n"),
            "android/gradlew.bat": ("100644", b"@echo off\r\n"),
            "android/gradle/wrapper/gradle-wrapper.jar": ("100644", b"wrapper jar bytes"),
            "android/gradle/wrapper/gradle-wrapper.properties": (
                "100644",
                b"distributionUrl=https\\://services.gradle.org/distributions/"
                b"gradle-8.13-bin.zip\n",
            ),
            "android/app/build.gradle.kts": ("100644", GRADLE.encode("utf-8")),
            "android/app/gradle.lockfile": ("100644", b"androidx.core:core-ktx:1.17.0\n"),
            "android/release-dependencies.gradle.kts": (
                "100644",
                b"// :app:exportReleaseDependencies\n",
            ),
            "android/app/src/main/AndroidManifest.xml": ("100644", b"<manifest/>\n"),
            "android/app/src/main/java/io/github/anevilburrito/wpm/MainActivity.kt": (
                "100644",
                b"package io.github.anevilburrito.wpm\nclass MainActivity\n",
            ),
            "android/app/src/test/java/io/github/anevilburrito/wpm/MainTest.kt": (
                "100644",
                b"class MainTest\n",
            ),
            "android/specs/release-pipeline.md": ("100644", b"# release pipeline\n"),
            "scripts/build-android.ps1": ("100644", b"# android build helper\n"),
            "scripts/collect-android-dependencies.py": ("100644", b"# collector\n"),
            "scripts/verify-android-release-assets.py": ("100644", b"# verifier\n"),
            "scripts/tests/test_collect_android_dependencies.py": ("100644", b"# test\n"),
            "scripts/tests/test_verify_android_release_assets.py": ("100644", b"# test\n"),
            "scripts/tests/test_package_android_source.py": ("100644", b"# test\n"),
            # Unrelated desktop release inputs must never enter the archive.
            "scripts/package.ps1": ("100644", b"# desktop packaging\n"),
            "scripts/package-source.ps1": ("100644", b"# desktop source\n"),
            "scripts/verify-release-assets.py": ("100644", b"# desktop verifier\n"),
            "scripts/tests/test_verify_release_assets.py": ("100644", b"# desktop test\n"),
            ".github/workflows/candidate.yml": ("100644", b"# ci\n"),
            "Cargo.toml": ("100644", b"[package]\nname = \"wpm\"\n"),
            "app/src/main.rs": ("100644", b"fn main() {}\n"),
        }

    def dependency_manifest(self):
        dependencies = [
            {
                "coordinate": "androidx.core:core-ktx:1.17.0",
                "source": "files/core-ktx-1.17.0-sources.jar",
                "source_sha256": digest(self.jar),
                "pom": "files/core-ktx-1.17.0.pom",
                "pom_sha256": digest(self.pom),
                "licenses": [
                    {
                        "name": "Apache-2.0",
                        "url": "https://www.apache.org/licenses/LICENSE-2.0.txt",
                    }
                ],
            }
        ]
        dependencies.extend(self.manifest_extra)
        manifest = {"schema": 1, "dependencies": dependencies}
        if self.manifest_native is not None:
            manifest["native_sources"] = self.manifest_native
        return manifest

    def write_dependencies(self):
        self.dependencies_path.mkdir(parents=True, exist_ok=True)
        (self.dependencies_path / "files").mkdir(exist_ok=True)
        (self.dependencies_path / "notices").mkdir(exist_ok=True)
        (self.dependencies_path / "files/core-ktx-1.17.0-sources.jar").write_bytes(self.jar)
        (self.dependencies_path / "files/core-ktx-1.17.0.pom").write_bytes(self.pom)
        (self.dependencies_path / "LICENSE-APACHE-2.0.txt").write_text(
            "Apache License Version 2.0 synthetic text\n", encoding="utf-8"
        )
        (self.dependencies_path / "notices/core-ktx__source__LICENSE").write_text(
            "notice\n", encoding="utf-8"
        )
        for relative, data in self.extra_dependency_files.items():
            path = self.dependencies_path / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        (self.dependencies_path / "manifest.json").write_text(
            json.dumps(self.dependency_manifest(), indent=2), encoding="utf-8"
        )
        return self.dependencies_path

    def prepare(
        self,
        files=None,
        commit=COMMIT,
        tree_commit=None,
        dependencies=None,
        fetcher=None,
        native_archive=None,
        output=None,
    ):
        files = self.committed_files() if files is None else files
        if dependencies is None:
            dependencies = (
                self.dependencies_path
                if self.dependencies_path.exists()
                else self.write_dependencies()
            )
        git = FakeGit(tree_commit or commit, files)
        output = self.root / "out" if output is None else output
        target = packager.prepare(
            commit,
            output,
            repo=self.repo,
            dependencies=dependencies,
            native_archive=native_archive,
            git=git,
            fetcher=fetcher,
        )
        return target, output

    def no_network(self):
        def fetcher(url):
            raise AssertionError(f"tests must not download {url}")

        return fetcher

    @staticmethod
    def read_zip(path):
        with zipfile.ZipFile(path) as archive:
            return {
                info.filename: (archive.read(info), (info.external_attr >> 16) & 0o777)
                for info in archive.infolist()
            }

    def record(self, apk_bytes, **overrides):
        record = {
            "schema": 1,
            "version": VERSION,
            "version_code": VERSION_CODE,
            "application_id": APPLICATION_ID,
            "commit": COMMIT,
            "certificate_sha256": CERTIFICATE,
            "apk_sha256": digest(apk_bytes),
            "abis": list(verifier.ABIS),
            "toolchain": dict(verifier.TOOLCHAIN),
            "checks": {name: True for name in verifier.CHECKS},
        }
        record.update(overrides)
        return record

    def write_record(self, record):
        path = self.root / "record.json"
        path.write_text(json.dumps(record, indent=2), encoding="utf-8")
        return path

    def finalize(self, archive, record, apk, output):
        with redirect_stdout(io.StringIO()):
            return packager.finalize(archive, self.write_record(record), apk, output)


# --------------------------------------------------------------------------- #
# prepare
# --------------------------------------------------------------------------- #
class PrepareTests(PackagingTestCase):
    def test_prepare_writes_source_input_zip_only(self):
        native_file = self.root / "graphics-path.tar.gz"
        native_file.write_bytes(self.native)
        target, output = self.prepare(native_archive=native_file, fetcher=self.no_network())
        self.assertEqual(target, output / "source-input.zip")
        self.assertEqual(sorted(path.name for path in output.iterdir()), ["source-input.zip"])

        entries = self.read_zip(target)
        self.assertIn("LICENSE", entries)
        self.assertIn("README.md", entries)
        self.assertIn("SOURCE-BUILD.md", entries)
        self.assertNotIn("ANDROID-RELEASE.json", entries)
        self.assertIn("android/app/build.gradle.kts", entries)
        self.assertIn("android/app/gradle.lockfile", entries)
        self.assertIn("android/gradlew", entries)
        self.assertIn("android/app/src/main/AndroidManifest.xml", entries)
        self.assertIn("android/app/src/test/java/io/github/anevilburrito/wpm/MainTest.kt", entries)
        self.assertIn("scripts/build-android.ps1", entries)
        self.assertIn("scripts/collect-android-dependencies.py", entries)
        self.assertIn("scripts/tests/test_collect_android_dependencies.py", entries)
        self.assertIn("scripts/tests/test_verify_android_release_assets.py", entries)
        self.assertIn("scripts/tests/test_package_android_source.py", entries)

        for excluded in (
            "scripts/package.ps1",
            "scripts/package-source.ps1",
            "scripts/verify-release-assets.py",
            "scripts/tests/test_verify_release_assets.py",
            ".github/workflows/candidate.yml",
            "Cargo.toml",
            "app/src/main.rs",
        ):
            self.assertNotIn(excluded, entries)

        # POSIX executable bit survives; other files are read-only-normal.
        self.assertEqual(entries["android/gradlew"][1], 0o755)
        self.assertEqual(entries["LICENSE"][1], 0o644)

        dependency_prefix = "android/dependency-sources/"
        self.assertIn(dependency_prefix + "manifest.json", entries)
        self.assertIn(dependency_prefix + "LICENSE-APACHE-2.0.txt", entries)
        self.assertIn(dependency_prefix + "files/core-ktx-1.17.0-sources.jar", entries)
        self.assertIn(dependency_prefix + "notices/core-ktx__source__LICENSE", entries)
        native_entry = dependency_prefix + packager.NATIVE_PATH
        self.assertEqual(entries[native_entry][0], self.native)

        manifest = json.loads(entries[dependency_prefix + "manifest.json"][0])
        self.assertEqual(manifest["schema"], 1)
        self.assertEqual(len(manifest["dependencies"]), 1)
        self.assertEqual(len(manifest["native_sources"]), 1)
        native = manifest["native_sources"][0]
        self.assertEqual(native["coordinate"], verifier.NATIVE_COORDINATE)
        self.assertEqual(native["path"], "native/graphics-path-1.0.1.tar.gz")
        self.assertEqual(native["sha256"], self.native_sha)
        self.assertIn(packager.NATIVE_URL, native["provenance"])
        self.assertIn(packager.NATIVE_RELEASE_NOTES_URL, native["provenance"])

        build_doc = entries["SOURCE-BUILD.md"][0].decode("utf-8")
        self.assertIn(f"Exact Git commit: {COMMIT}", build_doc)
        self.assertIn(VERSION, build_doc)
        self.assertIn("unsigned release APK", build_doc)
        self.assertIn("GNU General Public License", build_doc)
        self.assertIn("Apache License 2.0", build_doc)
        self.assertIn("no offline", build_doc)
        self.assertIn("reproducible", build_doc)
        self.assertIn(packager.NATIVE_SHA256, build_doc)
        self.assertIn("CMake", build_doc)

    def test_prepare_uses_commit_not_working_tree(self):
        files = self.committed_files()
        target, _ = self.prepare(files=files, fetcher=lambda url: self.native)
        entries = self.read_zip(target)
        self.assertEqual(
            entries["LICENSE"][0], b"GNU GENERAL PUBLIC LICENSE Version 3 synthetic\n"
        )

        # Working-tree edits and untracked files under whitelisted directories.
        (self.repo / "LICENSE").write_bytes(b"TAMPERED WORKING TREE LICENSE\n")
        (self.repo / "android").mkdir(exist_ok=True)
        (self.repo / "android/app").mkdir(exist_ok=True)
        (self.repo / "android/app/src").mkdir(parents=True, exist_ok=True)
        (self.repo / "android/app/src/Untracked.kt").write_bytes(b"class Untracked\n")
        (self.repo / "android/local.properties").write_bytes(b"sdk.dir=/private/sdk\n")

        retargeted, _ = self.prepare(
            files=files, fetcher=lambda url: self.native, output=self.root / "out2"
        )
        retargeted_entries = self.read_zip(retargeted)
        self.assertEqual(
            retargeted_entries["LICENSE"][0],
            b"GNU GENERAL PUBLIC LICENSE Version 3 synthetic\n",
        )
        self.assertNotIn("android/app/src/Untracked.kt", retargeted_entries)
        self.assertNotIn("android/local.properties", retargeted_entries)

    def test_prepare_passes_verifier_safety_and_manifest(self):
        target, _ = self.prepare(fetcher=lambda url: self.native)
        with zipfile.ZipFile(target) as archive:
            members = verifier._inspect_source(archive)
            for name in verifier.REQUIRED_FILES:
                if name == "ANDROID-RELEASE.json":
                    self.assertNotIn(name, members)
                    continue
                self.assertIn(name, members)
            verifier._dependency_manifest(archive)
        self.assertNotIn("ANDROID-RELEASE.json", self.read_zip(target))

    def test_prepare_rejects_forbidden_tracked_paths(self):
        forbidden = (
            "android/local.properties",
            "android/keystore.properties",
            "android/release-signing.p12",
            "android/app/release.jks",
            "android/app/build/outputs/apk/release/app-release-unsigned.apk",
            "android/.gradle/configuration-cache/x.bin",
            "android/app/.cxx/Debug/arm64/compile_commands.json",
            "android/app/build/intermediates/merged_manifest/x.xml",
            "android/build/reports/lint-results.html",
            "android/app/src/main/assets/generated/data.bin",
            "android/__pycache__/helper.cpython-311.pyc",
        )
        for path in forbidden:
            with self.subTest(path=path):
                files = self.committed_files()
                files[path] = ("100644", b"private or generated\n")
                with self.assertRaisesRegex(ValueError, "Private|Unsafe"):
                    self.prepare(files=files, fetcher=lambda url: self.native, output=self.root / "rejected")
                self.assertFalse((self.root / "rejected/source-input.zip").exists())

    def test_prepare_rejects_invalid_commit(self):
        for commit in ("abc1234", "A" * 40, COMMIT + "0", "", "../" + COMMIT):
            with self.subTest(commit=commit):
                with self.assertRaisesRegex(ValueError, "40-character Git commit"):
                    packager.prepare(
                        commit,
                        self.root / "never",
                        repo=self.repo,
                        dependencies=self.write_dependencies(),
                        git=FakeGit(COMMIT, self.committed_files()),
                    )
                self.assertFalse((self.root / "never").exists())

    def test_prepare_rejects_commit_mismatch(self):
        with self.assertRaisesRegex(ValueError, "differs from the requested commit"):
            self.prepare(tree_commit=OTHER_COMMIT, fetcher=lambda url: self.native)

    def test_prepare_rejects_missing_required_file(self):
        files = self.committed_files()
        del files["android/app/gradle.lockfile"]
        with self.assertRaisesRegex(ValueError, "missing required file"):
            self.prepare(files=files, fetcher=lambda url: self.native)
        self.assertFalse((self.root / "out").exists())

    def test_prepare_rejects_missing_root_license(self):
        files = self.committed_files()
        del files["LICENSE"]
        with self.assertRaisesRegex(ValueError, "missing: LICENSE"):
            self.prepare(files=files, fetcher=lambda url: self.native)

    def test_prepare_rejects_native_hash_mismatch(self):
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            self.prepare(fetcher=lambda url: b"tampered native archive")
        self.assertFalse((self.root / "out/source-input.zip").exists())

        # A local archive file with the wrong bytes is refused identically.
        tampered = self.root / "tampered.tar.gz"
        tampered.write_bytes(native_tar_bytes({"src/main/cpp/evil.cpp": "x"}))
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            self.prepare(native_archive=tampered, fetcher=self.no_network())
        self.assertFalse((self.root / "out/source-input.zip").exists())

    def test_prepare_rejects_incomplete_native_archive(self):
        incomplete = native_tar_bytes({"build.gradle": "// no native sources\n"})
        with mock.patch.object(packager, "NATIVE_SHA256", digest(incomplete)):
            with self.assertRaisesRegex(ValueError, "missing C\\+\\+ sources"):
                self.prepare(fetcher=lambda url: incomplete)

    def test_prepare_rejects_mismatched_graphics_path_version(self):
        self.manifest_extra.append(
            {
                "coordinate": "androidx.graphics:graphics-path:1.0.0",
                "source": "files/graphics-path-1.0.0-sources.jar",
                "source_sha256": digest(self.jar),
                "pom": "files/graphics-path-1.0.0.pom",
                "pom_sha256": digest(self.pom),
                "licenses": [
                    {
                        "name": "Apache-2.0",
                        "url": "https://www.apache.org/licenses/LICENSE-2.0.txt",
                    }
                ],
            }
        )
        self.write_dependencies()
        with self.assertRaisesRegex(ValueError, "only 1.0.1 has pinned"):
            self.prepare(fetcher=lambda url: self.native)

    def test_prepare_rejects_unknown_native_library(self):
        self.extra_dependency_files["files/libgraphicspath.so"] = b"\x7fELF synthetic"
        self.write_dependencies()
        with self.assertRaisesRegex(ValueError, "Unknown upstream source mapping"):
            self.prepare(fetcher=lambda url: self.native)

    def test_prepare_rejects_conflicting_existing_native_record(self):
        self.manifest_native = [
            {
                "coordinate": verifier.NATIVE_COORDINATE,
                "path": "native/graphics-path-1.0.1.tar.gz",
                "sha256": "0" * 64,
                "provenance": "stale synthetic record",
            }
        ]
        self.write_dependencies()
        with self.assertRaisesRegex(ValueError, "does not match the pinned archive"):
            self.prepare(fetcher=lambda url: self.native)

    def test_prepare_accepts_matching_existing_native_record(self):
        self.manifest_native = [
            {
                "coordinate": verifier.NATIVE_COORDINATE,
                "path": "native/graphics-path-1.0.1.tar.gz",
                "sha256": self.native_sha,
                "provenance": "pre-recorded pinned provenance",
            }
        ]
        self.write_dependencies()
        target, _ = self.prepare(fetcher=lambda url: self.native)
        entries = self.read_zip(target)
        manifest = json.loads(
            entries["android/dependency-sources/manifest.json"][0]
        )
        self.assertEqual(len(manifest["native_sources"]), 1)
        self.assertEqual(manifest["native_sources"][0]["provenance"], "pre-recorded pinned provenance")

    def test_prepare_rejects_nonempty_output(self):
        output = self.root / "out"
        output.mkdir()
        (output / "stale.txt").write_text("stale", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "must be empty"):
            self.prepare(fetcher=lambda url: self.native, output=output)

    def test_prepare_rejects_missing_dependency_export(self):
        with self.assertRaisesRegex(ValueError, "Dependency source export is missing"):
            packager.prepare(
                COMMIT,
                self.root / "out",
                repo=self.repo,
                dependencies=self.root / "absent",
                git=FakeGit(COMMIT, self.committed_files()),
                fetcher=lambda url: self.native,
            )

    def test_prepare_rejects_ambiguous_gradle_identity(self):
        files = self.committed_files()
        duplicated = GRADLE + f'\nandroid {{ defaultConfig {{ versionName = "{VERSION}" }} }}\n'
        files["android/app/build.gradle.kts"] = ("100644", duplicated.encode("utf-8"))
        with self.assertRaisesRegex(ValueError, "exactly one versionName"):
            self.prepare(files=files, fetcher=lambda url: self.native)


# --------------------------------------------------------------------------- #
# extract
# --------------------------------------------------------------------------- #
class ExtractTests(PackagingTestCase):
    def write_archive(self, path, entries):
        with zipfile.ZipFile(path, "w") as archive:
            for name, data, mode in entries:
                info = zipfile.ZipInfo(name)
                info.external_attr = mode << 16
                archive.writestr(info, data)
        return path

    def test_extract_restores_safe_executable_bits(self):
        target, _ = self.prepare(fetcher=lambda url: self.native)
        output = self.root / "extracted"
        names = packager.extract(target, output)
        self.assertIn("android/gradlew", names)
        gradlew = output / "android/gradlew"
        self.assertTrue(gradlew.is_file())
        self.assertEqual(gradlew.read_bytes(), b"#!/bin/sh\nexec java -jar wrapper.jar\n")
        # Windows cannot represent POSIX mode bits; Linux CI (the release host)
        # exercises the real executable bit.
        if os.name == "posix":
            self.assertTrue(gradlew.stat().st_mode & stat.S_IXUSR)
            self.assertFalse((output / "LICENSE").stat().st_mode & stat.S_IXUSR)
        self.assertEqual((output / "LICENSE").read_bytes(), b"GNU GENERAL PUBLIC LICENSE Version 3 synthetic\n")
        self.assertIn("android/dependency-sources/native/graphics-path-1.0.1.tar.gz", names)

    def test_extract_refuses_nonempty_output(self):
        target, _ = self.prepare(fetcher=lambda url: self.native)
        output = self.root / "extracted"
        output.mkdir()
        (output / "keep.txt").write_text("keep", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "not empty"):
            packager.extract(target, output)
        self.assertEqual((output / "keep.txt").read_text(encoding="utf-8"), "keep")

    def test_extract_refuses_unsafe_entries(self):
        cases = (
            (("../escape.txt", b"x", 0o100644), "Unsafe"),
            (("/absolute.txt", b"x", 0o100644), "Unsafe"),
            (("build/output.bin", b"x", 0o100644), "Build/cache"),
            (("keystore.properties", b"x", 0o100644), "Secret/signing"),
            (("android/app/release.p12", b"x", 0o100644), "Secret/signing"),
            (("link", b"../target", 0o120777), "Symlink"),
        )
        for index, (entry, pattern) in enumerate(cases):
            with self.subTest(entry=entry[0]):
                archive = self.write_archive(
                    self.root / f"unsafe-{index}.zip", [entry]
                )
                output = self.root / f"unsafe-out-{index}"
                with self.assertRaisesRegex(ValueError, pattern):
                    packager.extract(archive, output)
                self.assertFalse(output.exists() and any(output.iterdir()))

    def test_extract_refuses_duplicate_entries(self):
        archive = self.root / "duplicate.zip"
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            with zipfile.ZipFile(archive, "w") as source:
                source.writestr("LICENSE", b"one")
                source.writestr("LICENSE", b"two")
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            packager.extract(archive, self.root / "duplicate-out")

    def test_extract_refuses_missing_archive(self):
        with self.assertRaisesRegex(ValueError, "does not exist"):
            packager.extract(self.root / "absent.zip", self.root / "absent-out")

    def test_extract_cli(self):
        target, _ = self.prepare(fetcher=lambda url: self.native)
        output = self.root / "cli-extract"
        result = subprocess.run(
            [
                sys.executable, "-B", str(Path(packager.__file__).resolve()),
                "extract", "--archive", str(target), "--output", str(output),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Extracted", result.stdout)
        self.assertTrue((output / "android/gradlew").is_file())


# --------------------------------------------------------------------------- #
# finalize
# --------------------------------------------------------------------------- #
class FinalizeTests(PackagingTestCase):
    def published(self, apk_bytes=b"synthetic signed APK bytes"):
        target, _ = self.prepare(fetcher=lambda url: self.native)
        apk = self.root / "signed.apk"
        apk.write_bytes(apk_bytes)
        output = self.root / "release-assets"
        return target, apk, output

    def test_finalize_emits_exactly_four_public_files(self):
        apk_bytes = b"synthetic signed APK bytes"
        target, apk, output = self.published(apk_bytes)
        source_zip = self.finalize(target, self.record(apk_bytes), apk, output)

        apk_name = f"wpm-android-v{VERSION}.apk"
        source_name = f"wpm-android-v{VERSION}-source.zip"
        self.assertEqual(source_zip, output / source_name)
        self.assertEqual(
            sorted(path.name for path in output.iterdir()),
            sorted(
                [apk_name, apk_name + ".sha256", source_name, source_name + ".sha256"]
            ),
        )
        self.assertEqual((output / apk_name).read_bytes(), apk_bytes)
        for name in (apk_name, source_name):
            data = (output / name).read_bytes()
            sidecar = (output / (name + ".sha256")).read_text(encoding="ascii")
            self.assertEqual(sidecar, f"{digest(data)}  {name}\n")

        entries = self.read_zip(output / source_name)
        metadata = json.loads(entries["ANDROID-RELEASE.json"][0])
        self.assertEqual(metadata["commit"], COMMIT)
        self.assertEqual(metadata["apk_sha256"], digest(apk_bytes))
        self.assertEqual(entries["ANDROID-RELEASE.json"][1], 0o644)
        # Source entries are preserved, including the executable wrapper bit.
        self.assertIn("SOURCE-BUILD.md", entries)
        self.assertIn("android/dependency-sources/manifest.json", entries)
        self.assertEqual(entries["android/gradlew"][1], 0o755)
        self.assertIn("LICENSE", entries)

        # The download verifier accepts the published inventory.
        with redirect_stdout(io.StringIO()):
            verifier.verify(
                output,
                VERSION,
                VERSION_CODE,
                COMMIT,
                APPLICATION_ID,
                CERTIFICATE,
            )

    def test_finalize_keeps_preexisting_signed_apk(self):
        apk_bytes = b"signer output produced by the parent job"
        target, apk, output = self.published(apk_bytes)
        apk_name = f"wpm-android-v{VERSION}.apk"
        output.mkdir()
        signed_target = output / apk_name
        signed_target.write_bytes(apk_bytes)
        before = signed_target.stat().st_mtime_ns

        self.finalize(target, self.record(apk_bytes), apk, output)
        self.assertEqual(signed_target.read_bytes(), apk_bytes)
        self.assertEqual(signed_target.stat().st_mtime_ns, before)
        self.assertEqual(
            sorted(path.name for path in output.iterdir()),
            sorted([apk_name, apk_name + ".sha256",
                    f"wpm-android-v{VERSION}-source.zip",
                    f"wpm-android-v{VERSION}-source.zip.sha256"]),
        )

    def test_finalize_rejects_existing_mismatched_apk(self):
        apk_bytes = b"synthetic signed APK bytes"
        target, apk, output = self.published(apk_bytes)
        output.mkdir()
        (output / f"wpm-android-v{VERSION}.apk").write_bytes(b"a different signed APK")
        with self.assertRaisesRegex(ValueError, "refusing to overwrite"):
            self.finalize(target, self.record(apk_bytes), apk, output)
        self.assertEqual(
            (output / f"wpm-android-v{VERSION}.apk").read_bytes(),
            b"a different signed APK",
        )

    def test_finalize_rejects_unexpected_output_files(self):
        apk_bytes = b"synthetic signed APK bytes"
        target, apk, output = self.published(apk_bytes)
        output.mkdir()
        (output / "stale.zip").write_bytes(b"stale")
        with self.assertRaisesRegex(ValueError, "Unexpected files"):
            self.finalize(target, self.record(apk_bytes), apk, output)

    def test_finalize_rejects_apk_hash_mismatch(self):
        target, apk, output = self.published(b"synthetic signed APK bytes")
        record = self.record(b"some other APK")
        with self.assertRaisesRegex(ValueError, "does not match the release record"):
            self.finalize(target, record, apk, output)
        self.assertFalse(output.exists() and any(output.iterdir()))

    def test_finalize_rejects_unsatisfied_check(self):
        apk_bytes = b"synthetic signed APK bytes"
        target, apk, output = self.published(apk_bytes)
        record = self.record(apk_bytes)
        record["checks"]["source_rebuild"] = False
        with self.assertRaisesRegex(ValueError, "source_rebuild"):
            self.finalize(target, record, apk, output)

    def test_finalize_rejects_invalid_record_fields(self):
        apk_bytes = b"synthetic signed APK bytes"
        target, apk, output = self.published(apk_bytes)
        cases = (
            ({"certificate_sha256": CERTIFICATE.lower()}, "uppercase"),
            ({"apk_sha256": digest(apk_bytes).upper()}, "lowercase"),
            ({"commit": "ABC"}, "40 lowercase"),
            ({"version": "../0.7.0"}, "versionName"),
            ({"version_code": True}, "version_code"),
            ({"abis": ["arm64-v8a"]}, "abis"),
            ({"toolchain": {"java": "21"}}, "toolchain"),
            ({"schema": 2}, "schema"),
            ({"extra": "field"}, "keys mismatch"),
        )
        for overrides, pattern in cases:
            with self.subTest(overrides=overrides):
                record = self.record(apk_bytes, **overrides)
                with self.assertRaisesRegex(ValueError, pattern):
                    self.finalize(target, record, apk, output)

    def test_finalize_rejects_metadata_already_present(self):
        apk_bytes = b"synthetic signed APK bytes"
        target, apk, output = self.published(apk_bytes)
        with zipfile.ZipFile(target, "a") as archive:
            archive.writestr("ANDROID-RELEASE.json", b"{}")
        with self.assertRaisesRegex(ValueError, "already contains"):
            self.finalize(target, self.record(apk_bytes), apk, output)

    def test_finalize_rejects_record_that_fails_verification(self):
        apk_bytes = b"synthetic signed APK bytes"
        target, apk, output = self.published(apk_bytes)
        record = self.record(apk_bytes, version="0.7.0")
        with self.assertRaisesRegex(ValueError, "versionName mismatch"):
            self.finalize(target, record, apk, output)
        # Only files this run created are removed; nothing is left behind.
        self.assertFalse(output.exists() and any(output.iterdir()))

    def test_finalize_rejects_missing_signed_apk(self):
        target, _, output = self.published()
        with self.assertRaisesRegex(ValueError, "Signed APK does not exist"):
            self.finalize(target, self.record(b"x"), self.root / "absent.apk", output)

    def test_finalize_rejects_nonempty_output_path(self):
        apk_bytes = b"synthetic signed APK bytes"
        target, apk, output = self.published(apk_bytes)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(b"a file, not a directory")
        with self.assertRaisesRegex(ValueError, "not a directory"):
            self.finalize(target, self.record(apk_bytes), apk, output)


# --------------------------------------------------------------------------- #
# Real Git plumbing and CLI wiring
# --------------------------------------------------------------------------- #
class RealGitPackagingTests(PackagingTestCase):
    def git(self, *arguments):
        result = subprocess.run(
            ["git", *arguments], cwd=self.repo, capture_output=True, text=True, check=False
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def commit_fixture(self):
        if shutil.which("git") is None:
            self.skipTest("git is not installed")
        for path, (_, data) in self.committed_files().items():
            target = self.repo / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        self.git("init", "-q")
        self.git("config", "user.email", "tests@example.invalid")
        self.git("config", "user.name", "Synthetic Tests")
        self.git("config", "commit.gpgsign", "false")
        self.git("add", "-A")
        self.git("update-index", "--chmod=+x", "android/gradlew")
        self.git("commit", "-q", "-m", "synthetic release commit")
        return self.git("rev-parse", "HEAD")

    def test_prepare_reads_only_the_commit_tree(self):
        commit = self.commit_fixture()
        self.write_dependencies()
        native = self.root / "graphics-path.tar.gz"
        native.write_bytes(self.native)
        output = self.root / "real-out"
        target = packager.prepare(
            commit,
            output,
            repo=self.repo,
            dependencies=self.dependencies_path,
            native_archive=native,
            fetcher=self.no_network(),
        )
        entries = self.read_zip(target)
        self.assertEqual(entries["android/gradlew"][1], 0o755)
        self.assertIn("scripts/build-android.ps1", entries)
        self.assertNotIn("scripts/package.ps1", entries)

        # Uncommitted edits and untracked files never enter the archive.
        (self.repo / "LICENSE").write_bytes(b"UNCOMMITTED TAMPERED LICENSE\n")
        (self.repo / "android/app/src/Uncommitted.kt").write_bytes(
            b"class Uncommitted\n"
        )
        (self.repo / "android/local.properties").write_bytes(b"sdk.dir=/private\n")
        second = packager.prepare(
            commit,
            self.root / "real-out-2",
            repo=self.repo,
            dependencies=self.dependencies_path,
            native_archive=native,
            fetcher=self.no_network(),
        )
        second_entries = self.read_zip(second)
        self.assertEqual(
            second_entries["LICENSE"][0],
            b"GNU GENERAL PUBLIC LICENSE Version 3 synthetic\n",
        )
        self.assertNotIn("android/app/src/Uncommitted.kt", second_entries)
        self.assertNotIn("android/local.properties", second_entries)

    def test_finalize_cli(self):
        apk_bytes = b"synthetic signed APK bytes"
        target, apk, output = FinalizeTests.published(self, apk_bytes)
        record = write_json(self.root / "record.json", self.record(apk_bytes))
        result = subprocess.run(
            [
                sys.executable, "-B", str(Path(packager.__file__).resolve()),
                "finalize",
                "--archive", str(target),
                "--record", str(record),
                "--apk", str(apk),
                "--output", str(output),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Finalized", result.stdout)
        self.assertEqual(
            sorted(path.name for path in output.iterdir()),
            sorted([
                f"wpm-android-v{VERSION}.apk",
                f"wpm-android-v{VERSION}.apk.sha256",
                f"wpm-android-v{VERSION}-source.zip",
                f"wpm-android-v{VERSION}-source.zip.sha256",
            ]),
        )


def write_json(path, payload):
    path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    return path


if __name__ == "__main__":
    unittest.main()
