#!/usr/bin/env python3
"""Verify a downloaded Android candidate's inventory, hashes, and source identity.

Uses only Python 3.11+ standard-library modules; does not build, sign, or run
binaries. This verifier checks inventory, SHA-256 sidecars, source ZIP safety,
required source/build files, Gradle app identity, the dependency-source export,
native source archives, and ANDROID-RELEASE.json against explicit expected
values. It does NOT perform SDK/apksigner cryptographic signature verification:
that evidence must come from the trusted CI run recorded in the metadata.
"""

import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


VERSION_RE = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
COMMIT_RE = re.compile(r"[0-9a-f]{40}")
APPLICATION_ID_RE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")
CERTIFICATE_RE = re.compile(r"[0-9A-Fa-f]{64}")
HASH_RE = re.compile(r"[0-9a-f]{64}")
COORDINATE_RE = re.compile(
    r"[A-Za-z0-9][A-Za-z0-9_.+-]*:[A-Za-z0-9][A-Za-z0-9_.+-]*:[A-Za-z0-9][A-Za-z0-9_.+-]*"
)
# Android's documented maximum for versionCode (aapt rejects larger values).
VERSION_CODE_MAX = 2_100_000_000
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
TOOLCHAIN = {
    "java": "21",
    "gradle": "8.13",
    "compile_sdk": 36,
    "build_tools": "35.0.0",
}
CHECKS = (
    "release_unit_tests",
    "release_lint",
    "source_rebuild",
    "apk_signature",
    "apk_manifest",
    "zip_alignment_16k",
    "native_elf_alignment_16k",
)
METADATA_KEYS = {
    "schema",
    "version",
    "version_code",
    "application_id",
    "commit",
    "certificate_sha256",
    "apk_sha256",
    "abis",
    "toolchain",
    "checks",
}
REQUIRED_FILES = (
    "LICENSE",
    "SOURCE-BUILD.md",
    "ANDROID-RELEASE.json",
    "android/gradlew",
    "android/gradle/wrapper/gradle-wrapper.jar",
    "android/gradle/wrapper/gradle-wrapper.properties",
    "android/settings.gradle.kts",
    "android/build.gradle.kts",
    "android/app/build.gradle.kts",
    "android/gradle.properties",
    "android/app/gradle.lockfile",
    "android/release-dependencies.gradle.kts",
    "android/dependency-sources/manifest.json",
    "android/dependency-sources/LICENSE-APACHE-2.0.txt",
)
DEPENDENCY_PREFIX = "android/dependency-sources/"
NATIVE_COORDINATE = "androidx.graphics:graphics-path:1.0.1"
SECRET_FILENAMES = {"keystore.properties", "local.properties"}
SECRET_SUFFIXES = (".p12", ".jks", ".keystore")
# Casefolded components; the ZIP scan compares casefolded path parts.
FORBIDDEN_DIRECTORIES = {
    ".android",
    ".cxx",
    ".externalnativebuild",
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    "__pycache__",
    "build",
    "captures",
    "generated",
    "intermediates",
    "outputs",
    "reports",
    "test-results",
}
MAX_ENTRIES = 20_000
MAX_ENTRY_UNCOMPRESSED = 64 * 1024 * 1024
MAX_TOTAL_UNCOMPRESSED = 256 * 1024 * 1024


def _digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _object(value: object, label: str) -> dict:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    return value


def _exact_keys(value: dict, expected: set[str], label: str) -> None:
    missing = sorted(expected - set(value))
    unexpected = sorted(set(value) - expected)
    if missing or unexpected:
        raise ValueError(f"{label} keys mismatch: missing={missing}, unexpected={unexpected}")


def _keys(value: dict, required: set[str], allowed: set[str], label: str) -> None:
    missing = sorted(required - set(value))
    unexpected = sorted(set(value) - allowed)
    if missing or unexpected:
        raise ValueError(f"{label} keys mismatch: missing={missing}, unexpected={unexpected}")


def _text(value: object, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a nonempty string")
    return value


def _hash_field(value: object, label: str) -> str:
    if not isinstance(value, str) or not HASH_RE.fullmatch(value):
        raise ValueError(f"{label} must be 64 lowercase hex characters")
    return value


def _safe_relative(path: object, label: str) -> str:
    """Require a normalized relative POSIX path rooted at the export directory."""
    if not isinstance(path, str) or not path:
        raise ValueError(f"{label} must be a nonempty relative path")
    if (
        path.startswith("/")
        or "\\" in path
        or ":" in path
        or re.match(r"[A-Za-z]:", path)
        or any(part in ("", ".", "..") for part in path.split("/"))
    ):
        raise ValueError(f"Unsafe {label}: {path}")
    return path


def _zip_text(source: zipfile.ZipFile, name: str) -> str:
    try:
        return source.read(name).decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise ValueError(f"Source file is not valid UTF-8: {name}") from error


def _zip_json(source: zipfile.ZipFile, name: str) -> object:
    try:
        return json.loads(_zip_text(source, name))
    except json.JSONDecodeError as error:
        raise ValueError(f"Invalid JSON in source file {name}: {error}") from error


def _inspect_source(source: zipfile.ZipFile) -> dict[str, zipfile.ZipInfo]:
    entries = source.infolist()
    if not entries:
        raise ValueError("Source ZIP is empty")
    if len(entries) > MAX_ENTRIES:
        raise ValueError(f"Source ZIP has too many entries: {len(entries)}")
    members: dict[str, zipfile.ZipInfo] = {}
    folded: set[str] = set()
    total = 0
    for entry in entries:
        if entry.orig_filename != entry.filename:
            raise ValueError(f"Unsafe source ZIP entry: {entry.filename}")
        name = entry.filename
        parts = (name[:-1] if name.endswith("/") else name).split("/")
        if (
            not name
            or name.startswith("/")
            or "\\" in name
            or ":" in name
            or re.match(r"[A-Za-z]:", name)
            or any(part in ("", ".", "..") for part in parts)
        ):
            raise ValueError(f"Unsafe source ZIP entry: {name}")
        if (entry.external_attr >> 16) & 0o170000 == 0o120000:
            raise ValueError(f"Symlink source ZIP entry: {name}")
        if entry.flag_bits & 0x1:
            raise ValueError(f"Encrypted source ZIP entry: {name}")
        for part in parts:
            if part.casefold() in FORBIDDEN_DIRECTORIES:
                raise ValueError(f"Build/cache directory in source ZIP: {name}")
        basename = parts[-1].casefold()
        if basename in SECRET_FILENAMES or basename.endswith(SECRET_SUFFIXES):
            raise ValueError(f"Secret/signing file in source ZIP: {name}")
        if name in members:
            raise ValueError(f"Duplicate source ZIP entry: {name}")
        if name.casefold() in folded:
            raise ValueError(f"Case-insensitive duplicate source ZIP entry: {name}")
        members[name] = entry
        folded.add(name.casefold())
        if not entry.is_dir():
            if entry.file_size > MAX_ENTRY_UNCOMPRESSED:
                raise ValueError(f"Oversized source ZIP entry: {name}")
            total += entry.file_size
    if total > MAX_TOTAL_UNCOMPRESSED:
        raise ValueError("Source ZIP inflated size exceeds the allowed bound")
    return members


def _require_files(members: dict[str, zipfile.ZipInfo]) -> None:
    for name in REQUIRED_FILES:
        entry = members.get(name)
        if entry is None or entry.is_dir():
            raise ValueError(f"Source ZIP is missing required file: {name}")
        if entry.file_size == 0:
            raise ValueError(f"Source ZIP required file is empty: {name}")


def _read_sidecar(path: Path, name: str) -> str:
    try:
        text = path.read_text(encoding="ascii")
    except UnicodeDecodeError as error:
        raise ValueError(f"Checksum sidecar must be ASCII: {name}.sha256") from error
    match = re.fullmatch(rf"([0-9a-f]{{64}})  {re.escape(name)}\n", text)
    if match is None:
        raise ValueError(f"Invalid checksum sidecar: {name}.sha256")
    return match.group(1)


def _single(pattern: str, text: str, label: str) -> str:
    values = re.findall(pattern, text)
    if len(values) != 1:
        raise ValueError(f"Expected exactly one {label} in android/app/build.gradle.kts")
    return values[0]


def _gradle_identity(source: zipfile.ZipFile, version: str, version_code: int,
                     application_id: str) -> None:
    text = _zip_text(source, "android/app/build.gradle.kts")
    version_name = _single(r'(?<![\w.])versionName\s*=\s*"([^"]*)"', text, "versionName")
    gradle_code = _single(r"(?<![\w.])versionCode\s*=\s*([0-9]+)", text, "versionCode")
    gradle_id = _single(r'(?<![\w.])applicationId\s*=\s*"([^"]*)"', text, "applicationId")
    if version_name != version:
        raise ValueError(f"Gradle versionName mismatch: {version_name!r} != {version!r}")
    if gradle_code != str(version_code):
        raise ValueError(f"Gradle versionCode mismatch: {gradle_code} != {version_code}")
    if gradle_id != application_id:
        raise ValueError(f"Gradle applicationId mismatch: {gradle_id!r} != {application_id!r}")


def _metadata(source: zipfile.ZipFile, version: str, version_code: int, commit: str,
              application_id: str, certificate_sha256: str, apk_sha256: str) -> None:
    record = _object(_zip_json(source, "ANDROID-RELEASE.json"), "ANDROID-RELEASE.json")
    _exact_keys(record, METADATA_KEYS, "ANDROID-RELEASE.json")
    if type(record["schema"]) is not int or record["schema"] != 1:
        raise ValueError("Metadata schema must be 1")
    if record["version"] != version:
        raise ValueError(f"Metadata version mismatch: {record['version']!r} != {version!r}")
    if type(record["version_code"]) is not int or record["version_code"] != version_code:
        raise ValueError(f"Metadata version_code mismatch: {record['version_code']!r}")
    if record["application_id"] != application_id:
        raise ValueError(f"Metadata application_id mismatch: {record['application_id']!r}")
    if record["commit"] != commit:
        raise ValueError(f"Metadata commit mismatch: {record['commit']!r}")
    certificate = record["certificate_sha256"]
    if not isinstance(certificate, str) or not re.fullmatch(r"[0-9A-F]{64}", certificate):
        raise ValueError("Metadata certificate_sha256 must be 64 uppercase hex characters")
    if certificate != certificate_sha256.upper():
        raise ValueError(f"Metadata certificate_sha256 mismatch: {certificate}")
    _hash_field(record["apk_sha256"], "Metadata apk_sha256")
    if record["apk_sha256"] != apk_sha256:
        raise ValueError("Metadata apk_sha256 does not match the APK")
    abis = record["abis"]
    if not isinstance(abis, list) or abis != list(ABIS):
        raise ValueError(f"Metadata abis mismatch: expected {list(ABIS)}, got {abis!r}")
    toolchain = _object(record["toolchain"], "Metadata toolchain")
    _exact_keys(toolchain, set(TOOLCHAIN), "Metadata toolchain")
    for key, expected in TOOLCHAIN.items():
        if type(toolchain[key]) is not type(expected) or toolchain[key] != expected:
            raise ValueError(f"Metadata toolchain.{key} mismatch: {toolchain[key]!r} != {expected!r}")
    checks = _object(record["checks"], "Metadata checks")
    _exact_keys(checks, set(CHECKS), "Metadata checks")
    for key in CHECKS:
        if checks[key] is not True:
            raise ValueError(f"Metadata check not satisfied: {key}={checks[key]!r}")


def _validate_native_item(source: zipfile.ZipFile, item: object, label: str,
                          expected_keys: set[str]) -> tuple[str, str]:
    record = _object(item, label)
    _exact_keys(record, expected_keys, label)
    if "coordinate" in expected_keys:
        coordinate = record["coordinate"]
        if not isinstance(coordinate, str) or not COORDINATE_RE.fullmatch(coordinate):
            raise ValueError(f"{label}.coordinate must be group:name:version")
    path = _safe_relative(record["path"], f"{label}.path")
    digest = _hash_field(record["sha256"], f"{label}.sha256")
    _text(record["provenance"], f"{label}.provenance")
    archive_path = DEPENDENCY_PREFIX + path
    entry = source.getinfo(archive_path) if archive_path in source.namelist() else None
    if entry is None or entry.is_dir() or entry.file_size == 0:
        raise ValueError(f"Native source archive is missing from the source ZIP: {path}")
    if _digest(source.read(archive_path)) != digest:
        raise ValueError(f"Native source archive SHA-256 mismatch: {path}")
    return record.get("coordinate", ""), digest


def _dependency_manifest(source: zipfile.ZipFile) -> None:
    manifest = _object(
        _zip_json(source, DEPENDENCY_PREFIX + "manifest.json"),
        "Dependency manifest",
    )
    _exact_keys(manifest, {"schema", "dependencies", "native_sources"}, "Dependency manifest")
    if type(manifest["schema"]) is not int or manifest["schema"] != 1:
        raise ValueError("Dependency manifest schema must be 1")
    dependencies = manifest["dependencies"]
    if not isinstance(dependencies, list) or not dependencies:
        raise ValueError("Dependency manifest must list at least one dependency")
    coordinates: set[str] = set()
    folded_coordinates: set[str] = set()
    for index, value in enumerate(dependencies):
        label = f"Dependency manifest dependencies[{index}]"
        record = _object(value, label)
        _keys(
            record,
            {"coordinate", "source", "source_sha256", "pom", "pom_sha256", "licenses"},
            {"coordinate", "source", "source_sha256", "pom", "pom_sha256", "licenses",
             "native_sources"},
            label,
        )
        coordinate = record["coordinate"]
        if not isinstance(coordinate, str) or not COORDINATE_RE.fullmatch(coordinate):
            raise ValueError(f"{label}.coordinate must be group:name:version")
        if coordinate in coordinates or coordinate.casefold() in folded_coordinates:
            raise ValueError(f"Duplicate dependency coordinate: {coordinate}")
        coordinates.add(coordinate)
        folded_coordinates.add(coordinate.casefold())
        for path_key, hash_key, suffix in (
            ("source", "source_sha256", ".jar"),
            ("pom", "pom_sha256", ".pom"),
        ):
            path = _safe_relative(record[path_key], f"{label}.{path_key}")
            if not path.endswith(suffix):
                raise ValueError(f"{label}.{path_key} must be a {suffix} file: {path}")
            digest = _hash_field(record[hash_key], f"{label}.{hash_key}")
            archive_path = DEPENDENCY_PREFIX + path
            entry = source.getinfo(archive_path) if archive_path in source.namelist() else None
            if entry is None or entry.is_dir() or entry.file_size == 0:
                raise ValueError(f"Dependency archive is missing from the source ZIP: {path}")
            if _digest(source.read(archive_path)) != digest:
                raise ValueError(f"Dependency archive SHA-256 mismatch: {path}")
        licenses = record["licenses"]
        if not isinstance(licenses, list) or not licenses:
            raise ValueError(f"{label}.licenses must be nonempty")
        for license_index, license_value in enumerate(licenses):
            license_label = f"{label}.licenses[{license_index}]"
            license_record = _object(license_value, license_label)
            _exact_keys(license_record, {"name", "url"}, license_label)
            _text(license_record["name"], f"{license_label}.name")
            _text(license_record["url"], f"{license_label}.url")
        native_items = record.get("native_sources")
        if native_items is not None:
            if not isinstance(native_items, list):
                raise ValueError(f"{label}.native_sources must be a list")
            for native_index, item in enumerate(native_items):
                _validate_native_item(
                    source,
                    item,
                    f"{label}.native_sources[{native_index}]",
                    {"path", "sha256", "provenance"},
                )
    top_level_native = manifest["native_sources"]
    if not isinstance(top_level_native, list) or not top_level_native:
        raise ValueError("Dependency manifest native_sources must be a nonempty list")
    recorded_coordinates: list[str] = []
    for index, item in enumerate(top_level_native):
        recorded_coordinates.append(
            _validate_native_item(
                source,
                item,
                f"Dependency manifest native_sources[{index}]",
                {"coordinate", "path", "sha256", "provenance"},
            )[0]
        )
    if NATIVE_COORDINATE not in recorded_coordinates:
        raise ValueError(
            f"Dependency manifest must record native sources for {NATIVE_COORDINATE}"
        )


def verify(directory: Path, version: str, version_code: int, commit: str,
           application_id: str, certificate_sha256: str) -> None:
    """Fail closed unless the four downloaded assets match every expected value."""
    directory = Path(directory)
    if not VERSION_RE.fullmatch(version):
        raise ValueError("Expected a plain or pre-release Android versionName")
    if type(version_code) is not int or not 1 <= version_code <= VERSION_CODE_MAX:
        raise ValueError(f"Expected an Android versionCode between 1 and {VERSION_CODE_MAX}")
    if not COMMIT_RE.fullmatch(commit):
        raise ValueError("Expected the full lowercase Git commit SHA")
    if not APPLICATION_ID_RE.fullmatch(application_id):
        raise ValueError("Expected a reverse-DNS Android applicationId")
    if not CERTIFICATE_RE.fullmatch(certificate_sha256):
        raise ValueError("Expected the signing certificate SHA-256 fingerprint")
    if not directory.is_dir():
        raise ValueError(f"Asset directory does not exist: {directory}")

    apk_name = f"wpm-android-v{version}.apk"
    source_name = f"wpm-android-v{version}-source.zip"
    expected = {
        apk_name,
        apk_name + ".sha256",
        source_name,
        source_name + ".sha256",
    }
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

    digests: dict[str, str] = {}
    for name in (apk_name, source_name):
        if (directory / name).stat().st_size == 0:
            raise ValueError(f"Asset must not be empty: {name}")
        sidecar_digest = _read_sidecar(directory / (name + ".sha256"), name)
        with (directory / name).open("rb") as asset_file:
            digest = hashlib.file_digest(asset_file, "sha256").hexdigest()
        if digest != sidecar_digest:
            raise ValueError(f"SHA-256 mismatch: {name}")
        digests[name] = digest
        print(f"SHA256 {digest}  {name}")

    with zipfile.ZipFile(directory / source_name) as source:
        members = _inspect_source(source)
        _require_files(members)
        _metadata(
            source,
            version,
            version_code,
            commit,
            application_id,
            certificate_sha256,
            digests[apk_name],
        )
        _gradle_identity(source, version, version_code, application_id)
        _dependency_manifest(source)

    print(
        f"Verified {len(expected)} assets for wpm-android {version} "
        f"({application_id}) at {commit}; APK signature cryptography is not re-performed."
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--application-id", required=True)
    parser.add_argument(
        "--certificate-sha256",
        required=True,
        help="Expected signing certificate SHA-256 fingerprint",
    )
    args = parser.parse_args()
    try:
        verify(
            args.directory,
            args.version,
            args.version_code,
            args.commit,
            args.application_id,
            args.certificate_sha256,
        )
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Android release asset verification failed: {error}\n")


if __name__ == "__main__":
    main()
