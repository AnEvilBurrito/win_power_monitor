#!/usr/bin/env python3
"""Package and finalize the wpm Android release source input archive.

Subcommands (used by the Android candidate workflow):

    prepare   --commit SHA --output DIR
              Build ``DIR/source-input.zip`` from the exact Git commit's tracked
              Android build/source/docs files, the root LICENSE/README, the
              Android helper scripts, the generated dependency-source export and
              the pinned full graphics-path 1.0.1 native source archive.
    extract   --archive ZIP --output DIR
              Safely extract a source ZIP into an empty directory (CI rebuild
              step), restoring only safe executable bits.
    finalize  --archive source-input.zip --record JSON --apk signedAPK --output DIR
              Add ``ANDROID-RELEASE.json`` to the source ZIP, write the two
              SHA-256 sidecars next to the signed APK, and run the download
              verifier against the record values.

Only the Python 3.11+ standard library is used. The single network access is the
pinned graphics-path source download in ``prepare``; tests inject a fake fetcher
or a local ``--native-archive`` file and never touch the network. Nothing is read
from the working tree for tracked files: every tracked byte comes from the
immutable Git commit, so uncommitted edits can never enter the archive.
"""

import argparse
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import zipfile


SCHEMA = 1
SOURCE_INPUT_NAME = "source-input.zip"
METADATA_NAME = "ANDROID-RELEASE.json"
APK_NAME_TEMPLATE = "wpm-android-v{version}.apk"
SOURCE_NAME_TEMPLATE = "wpm-android-v{version}-source.zip"
DEPENDENCY_PREFIX = "android/dependency-sources/"
SOURCE_BUILD_NAME = "SOURCE-BUILD.md"
DEFAULT_DEPENDENCIES = "android/app/build/release-dependencies"

COMMIT_RE = re.compile(r"[0-9a-f]{40}")
HASH_RE = re.compile(r"[0-9a-f]{64}")
CERTIFICATE_RE = re.compile(r"[0-9A-F]{64}")

# Pinned full native sources for the one runtime component whose Maven source
# JAR omits the C++ payload. Byte-identical re-download is enforced by SHA-256.
NATIVE_COORDINATE = "androidx.graphics:graphics-path:1.0.1"
NATIVE_PATH = "native/graphics-path-1.0.1.tar.gz"
NATIVE_URL = (
    "https://android.googlesource.com/platform/frameworks/support/+archive/"
    "8a05a22af450d589ef911d772a001a49dcb05b71/graphics/graphics-path.tar.gz"
)
NATIVE_SHA256 = "a19ec7be5315acbece6fec9e1b6b29fca2397db8f7435eae0967bd2f17c76949"
NATIVE_RELEASE_NOTES_URL = (
    "https://developer.android.com/jetpack/androidx/releases/graphics"
    "#graphics-path-1.0.1"
)
GRAPHICS_PATH_GROUP = "androidx.graphics"
GRAPHICS_PATH_NAME = "graphics-path"
GRAPHICS_PATH_VERSION = "1.0.1"
MAX_NATIVE_BYTES = 64 * 1024 * 1024

# Native libraries that may appear in the generated dependency export mapped to
# the pinned upstream source that ships them. Any other .so is refused: silently
# publishing a binary whose source is not recorded is not allowed.
KNOWN_NATIVE_SO_SOURCES: dict[str, str] = {}

# Whitelisted tracked inputs. Everything else (desktop Cargo packaging, CI
# workflows, unrelated scripts, signing material) stays out of the archive.
ROOT_FILES = frozenset({"LICENSE", "README.md"})
ANDROID_PREFIX = "android/"
SCRIPTS_PREFIX = "scripts/"
SCRIPTS_TESTS_PREFIX = "scripts/tests/"
ANDROID_HELPER_NAMES = frozenset({"build-android.ps1"})

# Mirrors verify-android-release-assets.py: nothing under these may be packaged,
# even when it is tracked, because such a path is private, cache, or build output.
FORBIDDEN_COMPONENTS = frozenset({
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
})
SECRET_FILENAMES = frozenset({"keystore.properties", "local.properties"})
SECRET_SUFFIXES = (".p12", ".jks", ".keystore", ".pem", ".key")

ZIP_DATE_TIME = (1980, 1, 1, 0, 0, 0)
FILE_TYPE = 0o100000
EXECUTABLE = 0o755
READ_ONLY = 0o644

_VERIFIER_MODULE = None


# --------------------------------------------------------------------------- #
# Verifier import (single source of truth for the published contract)
# --------------------------------------------------------------------------- #
def verifier():
    """Load verify-android-release-assets.py as a module (dash in the name)."""
    global _VERIFIER_MODULE
    if _VERIFIER_MODULE is None:
        path = Path(__file__).resolve().parent / "verify-android-release-assets.py"
        spec = importlib.util.spec_from_file_location(
            "verify_android_release_assets", path
        )
        if spec is None or spec.loader is None:
            raise ValueError(f"Cannot load the release verifier from {path}")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _VERIFIER_MODULE = module
    return _VERIFIER_MODULE


# --------------------------------------------------------------------------- #
# Small helpers
# --------------------------------------------------------------------------- #
def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _permissions(mode: str) -> int:
    return EXECUTABLE if mode == "100755" else READ_ONLY


def _check_relative_path(path: object, label: str) -> str:
    """Validate a POSIX archive path and reject private/cache/build locations."""
    if not isinstance(path, str) or not path:
        raise ValueError(f"{label} must be a nonempty relative path")
    if (
        path.startswith("/")
        or path.startswith("./")
        or "\\" in path
        or ":" in path
        or re.match(r"[A-Za-z]:", path)
    ):
        raise ValueError(f"Unsafe {label}: {path}")
    parts = path.split("/")
    if any(part in ("", ".", "..") for part in parts):
        raise ValueError(f"Unsafe {label}: {path}")
    for part in parts:
        folded = part.casefold()
        if folded in FORBIDDEN_COMPONENTS:
            raise ValueError(f"Private, cache, or build path in {label}: {path}")
        if folded in SECRET_FILENAMES or folded.endswith(SECRET_SUFFIXES):
            raise ValueError(f"Private signing or credential path in {label}: {path}")
    return path


def _is_selected(path: str) -> bool:
    """True for the whitelisted tracked inputs of the Android release."""
    if path in ROOT_FILES:
        return True
    if path.startswith(ANDROID_PREFIX):
        return True
    if not path.startswith(SCRIPTS_PREFIX) or "\\" in path:
        return False
    if path.startswith(SCRIPTS_TESTS_PREFIX):
        # scripts/tests/<name>.py only, one level deep.
        relative = path[len(SCRIPTS_TESTS_PREFIX):]
        if "/" in relative:
            return False
        return relative.endswith(".py") and "android" in relative.casefold()
    relative = path[len(SCRIPTS_PREFIX):]
    if "/" in relative:
        return False
    if relative in ANDROID_HELPER_NAMES:
        return True
    # android*.py and *android*.py helpers (not the unrelated desktop scripts).
    return relative.endswith(".py") and "android" in relative.casefold()


def _zip_info(name: str, permissions: int, date_time=ZIP_DATE_TIME) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, date_time=date_time)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 3
    info.external_attr = (FILE_TYPE | permissions) << 16
    return info


def _write_zip(path: Path, entries: dict[str, tuple[int, bytes]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as archive:
        for name in sorted(entries):
            permissions, data = entries[name]
            archive.writestr(_zip_info(name, permissions), data)


def _exact_keys(value: dict, expected: set[str], label: str) -> None:
    missing = sorted(expected - set(value))
    unexpected = sorted(set(value) - expected)
    if missing or unexpected:
        raise ValueError(f"{label} keys mismatch: missing={missing}, unexpected={unexpected}")


def _require_pre_metadata_files(members, module) -> None:
    """Required source files excluding ANDROID-RELEASE.json (added at finalize)."""
    for name in module.REQUIRED_FILES:
        if name == METADATA_NAME:
            continue
        entry = members.get(name)
        if entry is None or entry.is_dir():
            raise ValueError(f"Source archive is missing required file: {name}")
        if entry.file_size == 0:
            raise ValueError(f"Source archive required file is empty: {name}")


# --------------------------------------------------------------------------- #
# Git access
# --------------------------------------------------------------------------- #
class TreeEntry:
    """One ``git ls-tree -r`` record."""

    __slots__ = ("mode", "type", "oid", "path")

    def __init__(self, mode: str, entry_type: str, oid: str, path: str) -> None:
        self.mode = mode
        self.type = entry_type
        self.oid = oid
        self.path = path


class GitRepository:
    """Read-only Git plumbing access; never uses the working tree."""

    def __init__(self, root) -> None:
        self.root = Path(root)

    def _run(self, *arguments: str) -> bytes:
        command = ["git", "-C", str(self.root), *arguments]
        result = subprocess.run(command, capture_output=True, check=False)
        if result.returncode != 0:
            detail = result.stderr.decode("utf-8", "replace").strip()
            raise ValueError(f"git {arguments[0]} failed: {detail or result.returncode}")
        return result.stdout

    def resolve_commit(self, commit: str) -> str:
        return self._run(
            "rev-parse", "--verify", "--end-of-options", f"{commit}^{{commit}}"
        ).decode("ascii", "replace").strip()

    def list_tree(self, commit: str) -> list[TreeEntry]:
        entries = []
        for record in self._run("ls-tree", "-r", "-z", commit).split(b"\0"):
            if not record:
                continue
            header, _, raw_path = record.partition(b"\t")
            mode, entry_type, oid = header.decode("ascii").split(" ")
            entries.append(TreeEntry(mode, entry_type, oid, raw_path.decode("utf-8")))
        return entries

    def read_blob(self, oid: str) -> bytes:
        return self._run("cat-file", "blob", oid)


def _committed_entries(git, commit: str) -> dict[str, tuple[int, bytes]]:
    """Whitelisted tracked files taken byte-for-byte from the commit tree."""
    entries: dict[str, tuple[int, bytes]] = {}
    folded: set[str] = set()
    for item in git.list_tree(commit):
        path = item.path
        if not _is_selected(path):
            continue
        _check_relative_path(path, "tracked path")
        if item.type != "blob":
            raise ValueError(f"Whitelisted path is not a regular file: {path}")
        if item.mode not in ("100644", "100755"):
            raise ValueError(f"Unsupported file mode {item.mode} for tracked path: {path}")
        if path in entries or path.casefold() in folded:
            raise ValueError(f"Duplicate tracked path in commit: {path}")
        entries[path] = (_permissions(item.mode), git.read_blob(item.oid))
        folded.add(path.casefold())
    if not entries:
        raise ValueError(f"No whitelisted Android source files at commit {commit}")
    for name in ROOT_FILES:
        if name not in entries:
            raise ValueError(f"Tracked release input is missing: {name}")
    return entries


# --------------------------------------------------------------------------- #
# Generated dependency sources
# --------------------------------------------------------------------------- #
def _dependency_files(directory: Path) -> tuple[dict[str, bytes], dict]:
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError(f"Dependency source export is missing: {directory}")
    files: dict[str, bytes] = {}
    for root, directories, names in os.walk(directory):
        directories.sort()
        for name in sorted(names):
            path = Path(root) / name
            if path.is_symlink():
                raise ValueError(f"Dependency export contains a symbolic link: {path}")
            if not path.is_file():
                raise ValueError(f"Dependency export contains a special file: {path}")
            relative = path.relative_to(directory).as_posix()
            _check_relative_path(relative, "dependency export path")
            files[relative] = path.read_bytes()
    if "manifest.json" not in files:
        raise ValueError(f"Dependency export has no manifest.json: {directory}")
    if "LICENSE-APACHE-2.0.txt" not in files:
        raise ValueError(
            f"Dependency export has no LICENSE-APACHE-2.0.txt: {directory}"
        )
    try:
        manifest = json.loads(files["manifest.json"].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"Dependency manifest is not valid JSON: {error}") from error
    if not isinstance(manifest, dict):
        raise ValueError("Dependency manifest must be a JSON object")
    return files, manifest


def _check_graphics_path(dependencies: object) -> None:
    """Refuse a resolved graphics-path version other than the pinned 1.0.1."""
    if not isinstance(dependencies, list):
        raise ValueError("Dependency manifest dependencies must be a list")
    for record in dependencies:
        if not isinstance(record, dict):
            continue
        coordinate = record.get("coordinate")
        if not isinstance(coordinate, str):
            continue
        group, _, rest = coordinate.partition(":")
        name, _, version = rest.partition(":")
        if group == GRAPHICS_PATH_GROUP and name == GRAPHICS_PATH_NAME:
            if version != GRAPHICS_PATH_VERSION:
                raise ValueError(
                    f"Resolved {GRAPHICS_PATH_GROUP}:{GRAPHICS_PATH_NAME} is version "
                    f"{version!r}, but only {GRAPHICS_PATH_VERSION} has pinned native "
                    "sources; refusing to package mismatched native provenance"
                )


def _check_native_objects(files: dict[str, bytes]) -> None:
    """Refuse any native library whose upstream source is not pinned here."""
    for relative in sorted(files):
        basename = relative.rsplit("/", 1)[-1].casefold()
        if not (basename.endswith(".so") or ".so." in basename):
            continue
        if basename not in KNOWN_NATIVE_SO_SOURCES:
            raise ValueError(
                f"Unknown upstream source mapping for native library {relative}; "
                "record its pinned source and hash before packaging"
            )


# --------------------------------------------------------------------------- #
# Pinned native sources
# --------------------------------------------------------------------------- #
def _download(url: str) -> bytes:
    import urllib.request

    request = urllib.request.Request(
        url, headers={"User-Agent": "wpm-android-source-packager"}
    )
    with urllib.request.urlopen(request, timeout=120) as response:  # noqa: S310
        data = response.read(MAX_NATIVE_BYTES + 1)
    if len(data) > MAX_NATIVE_BYTES:
        raise ValueError("Pinned native source archive download exceeds the size bound")
    return data


def _native_provenance() -> str:
    return (
        f"Pinned full upstream native source archive for {NATIVE_COORDINATE}, "
        f"downloaded from {NATIVE_URL} and verified against SHA-256 {NATIVE_SHA256}. "
        f"Release notes: {NATIVE_RELEASE_NOTES_URL}. "
        "Includes the module's C++/math sources plus its CMake and build.gradle "
        "build inputs; no separately vendored third-party native dependency is "
        "linked. Apache-2.0 text is shipped separately at "
        f"{DEPENDENCY_PREFIX}LICENSE-APACHE-2.0.txt."
    )


def _validate_native_archive(data: bytes) -> None:
    """Require a safe, complete native source tree (sources, headers, build)."""
    try:
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            members = archive.getmembers()
    except (tarfile.TarError, OSError) as error:
        raise ValueError(f"Pinned native source archive is not a readable .tar.gz: {error}") from error
    if not members:
        raise ValueError("Pinned native source archive is empty")
    sources = headers = build_inputs = 0
    names: set[str] = set()
    for member in members:
        raw_name = member.name.rstrip("/")
        if member.issym() or member.islnk():
            raise ValueError(f"Pinned native source archive contains a link: {raw_name}")
        if not (member.isfile() or member.isdir()):
            raise ValueError(f"Unsupported entry in pinned native source archive: {raw_name}")
        # Tolerate a leading "./" (some tar writers emit it); reject anything else.
        name = raw_name[2:] if raw_name.startswith("./") else raw_name
        _check_relative_path(name, "pinned native source entry")
        if name in names:
            raise ValueError(f"Duplicate entry in pinned native source archive: {name}")
        names.add(name)
        if not member.isfile():
            continue
        basename = name.rsplit("/", 1)[-1]
        if basename.endswith((".cpp", ".cc", ".cxx")):
            sources += 1
        elif basename.endswith((".h", ".hpp")):
            headers += 1
        if basename in ("CMakeLists.txt", "build.gradle", "build.gradle.kts") or basename.endswith(".cmake"):
            build_inputs += 1
    if not sources or not headers or not build_inputs:
        raise ValueError(
            "Pinned native source archive is missing C++ sources, headers, or "
            "CMake/build.gradle inputs"
        )


def _native_archive(manifest: dict, files: dict[str, bytes], data: bytes) -> None:
    """Record the pinned archive in the dependency manifest and export."""
    if _sha256(data) != NATIVE_SHA256:
        raise ValueError(
            f"Pinned native source archive SHA-256 mismatch: got {_sha256(data)}, "
            f"expected {NATIVE_SHA256}"
        )
    _validate_native_archive(data)

    existing = manifest.get("native_sources")
    if existing is None or existing == []:
        items = []
    elif isinstance(existing, list):
        items = list(existing)
    else:
        raise ValueError("Dependency manifest native_sources must be a list")
    matches = [
        item
        for item in items
        if isinstance(item, dict)
        and (item.get("path") == NATIVE_PATH or item.get("coordinate") == NATIVE_COORDINATE)
    ]
    if matches:
        if len(matches) != 1:
            raise ValueError(f"Duplicate native source records for {NATIVE_COORDINATE}")
        item = matches[0]
        provenance = item.get("provenance")
        if (
            item.get("coordinate") != NATIVE_COORDINATE
            or item.get("path") != NATIVE_PATH
            or item.get("sha256") != NATIVE_SHA256
            or not isinstance(provenance, str)
            or not provenance.strip()
        ):
            raise ValueError(
                f"Existing native source record for {NATIVE_COORDINATE} does not "
                "match the pinned archive"
            )
    else:
        items.append(
            {
                "coordinate": NATIVE_COORDINATE,
                "path": NATIVE_PATH,
                "sha256": NATIVE_SHA256,
                "provenance": _native_provenance(),
            }
        )
    manifest["native_sources"] = items
    if NATIVE_PATH in files:
        raise ValueError(f"Duplicate native source path in dependency export: {NATIVE_PATH}")
    files[NATIVE_PATH] = data


# --------------------------------------------------------------------------- #
# SOURCE-BUILD.md
# --------------------------------------------------------------------------- #
def _single(pattern: str, text: str, label: str) -> str:
    values = re.findall(pattern, text)
    if len(values) != 1:
        raise ValueError(f"Expected exactly one {label} in android/app/build.gradle.kts")
    return values[0]


def _gradle_identity(text: str) -> tuple[str, int, str]:
    version = _single(r'(?<![\w.])versionName\s*=\s*"([^"]*)"', text, "versionName")
    code = _single(r"(?<![\w.])versionCode\s*=\s*([0-9]+)", text, "versionCode")
    application_id = _single(
        r'(?<![\w.])applicationId\s*=\s*"([^"]*)"', text, "applicationId"
    )
    module = verifier()
    if not module.VERSION_RE.fullmatch(version):
        raise ValueError(f"Committed versionName is not an Android release version: {version!r}")
    if module.APPLICATION_ID_RE.fullmatch(application_id) is None:
        raise ValueError(f"Committed applicationId is not reverse-DNS: {application_id!r}")
    return version, int(code), application_id


def _source_build_markdown(
    commit: str,
    version: str,
    version_code: int,
    application_id: str,
    dependency_count: int,
) -> str:
    return f"""# Building wpm Android from source

Exact Git commit: {commit}

Version: {version} (versionCode {version_code})
Application ID: {application_id}

Every tracked file in this archive is copied byte-for-byte from the Git tree of
the commit above; uncommitted working-tree edits are never packaged. The
dependency-source export and the pinned native source archive were added by
`scripts/package-android-source.py` during packaging.

## Licensing

- The wpm application source (Kotlin, resources, Gradle build scripts, docs) is
  licensed under the GNU General Public License, version 3 only
  (`GPL-3.0-only`); see the root `LICENSE`. It is not relicensed by anything
  bundled here.
- Third-party AndroidX/Kotlin libraries remain under the Apache License 2.0 and
  are consumed as unmodified build dependencies; the GPL text above does not
  cover them. The full Apache-2.0 text ships separately at
  `{DEPENDENCY_PREFIX}LICENSE-APACHE-2.0.txt`.
- `{DEPENDENCY_PREFIX}` records, for every resolved external release runtime
  component, the Maven source JAR, the POM, and the license/notice files
  captured from the source and runtime archives ({dependency_count} recorded
  components). Hashes are recorded in
  `{DEPENDENCY_PREFIX}manifest.json`. Unknown licenses are refused at export
  time rather than silently omitted.

## Toolchain

- JDK 21
- Gradle wrapper {verifier().TOOLCHAIN["gradle"]} (`android/gradlew`)
- Android compile SDK {verifier().TOOLCHAIN["compile_sdk"]}, Build Tools
  {verifier().TOOLCHAIN["build_tools"]}
## Build the unsigned release APK

```sh
cd android
./gradlew --no-daemon --no-configuration-cache \\
    :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease \\
    :app:exportReleaseDependencies
```

The unsigned release APK is written to
`android/app/build/outputs/apk/release/app-release-unsigned.apk`; the dependency
export is regenerated at `android/app/build/release-dependencies/`.

No production signing key is needed: the release build type is intentionally
unsigned, and no keystore, `keystore.properties`, or `local.properties` is part
of this archive. The published APK is signed separately by protected CI after
the unsigned build is verified; that key never enters the source archive.

## Dependency locking and exported sources

The release runtime classpath is pinned by `android/app/gradle.lockfile`; the
build resolves the locked versions, and `:app:exportReleaseDependencies` fails
if a runtime component's source JAR or POM cannot be resolved. The export is
copied here unchanged apart from the added `native_sources` record.

Dependency resolution downloads from Google Maven and Maven Central, so network
access to those repositories is required. This archive makes no offline and no
bit-for-bit reproducible build claim.

## Native sources (graphics-path 1.0.1)

`{DEPENDENCY_PREFIX}{NATIVE_PATH}` is the pinned full upstream native
source archive for `{NATIVE_COORDINATE}` (C++/math sources plus CMake and
build.gradle build inputs):

- URL: {NATIVE_URL}
- SHA-256: {NATIVE_SHA256}
- Release notes: {NATIVE_RELEASE_NOTES_URL}
- Recorded as a top-level `native_sources` entry in
  `{DEPENDENCY_PREFIX}manifest.json`.

The Maven source JAR for this component omits its native payload, so the pinned
archive is the authoritative source; no unrelated native binary is shipped
without a recorded upstream source. To rebuild it, unpack the archive and
configure its CMake build (see the archive's `src/main/cpp/CMakeLists.txt` and
`build.gradle`) with the Android NDK against the four release ABIs, using no
external linked native dependency sources beyond the NDK and its C++ runtime.

The published source ZIP additionally contains `ANDROID-RELEASE.json`, which
records the Git commit, the signing certificate fingerprint, the APK SHA-256, and
the CI check evidence for the signed candidate.
"""


# --------------------------------------------------------------------------- #
# prepare
# --------------------------------------------------------------------------- #
def prepare(
    commit,
    output,
    *,
    repo=None,
    dependencies=None,
    native_archive=None,
    git=None,
    fetcher=None,
):
    """Write ``output/source-input.zip`` for the exact commit; returns its path."""
    if not isinstance(commit, str) or not COMMIT_RE.fullmatch(commit):
        raise ValueError("Prepare requires the full lowercase 40-character Git commit SHA")
    repo_path = Path(repo) if repo is not None else Path.cwd()
    if not repo_path.is_dir():
        raise ValueError(f"Repository root does not exist: {repo_path}")
    git = git if git is not None else GitRepository(repo_path)
    dependencies_path = (
        Path(dependencies) if dependencies is not None else repo_path / DEFAULT_DEPENDENCIES
    )
    output = Path(output)
    if output.is_symlink():
        raise ValueError(f"Prepare output must not be a symbolic link: {output}")
    if output.exists():
        if not output.is_dir():
            raise ValueError(f"Prepare output is not a directory: {output}")
        if any(output.iterdir()):
            raise ValueError(f"Prepare output directory must be empty: {output}")

    resolved = git.resolve_commit(commit)
    if resolved != commit:
        raise ValueError(f"Resolved commit {resolved!r} differs from the requested commit {commit!r}")

    module = verifier()
    entries = _committed_entries(git, commit)

    gradle = entries["android/app/build.gradle.kts"][1]
    try:
        gradle_text = gradle.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise ValueError("Committed android/app/build.gradle.kts is not valid UTF-8") from error
    version, version_code, application_id = _gradle_identity(gradle_text)

    dependency_files, manifest = _dependency_files(dependencies_path)
    dependencies_list = manifest.get("dependencies")
    _check_graphics_path(dependencies_list)
    _check_native_objects(dependency_files)

    if native_archive is not None:
        native_path = Path(native_archive)
        if native_path.is_symlink() or not native_path.is_file():
            raise ValueError(f"Pinned native source archive is missing: {native_path}")
        native_bytes = native_path.read_bytes()
    else:
        source = fetcher if fetcher is not None else _download
        native_bytes = source(NATIVE_URL)
        if not isinstance(native_bytes, bytes):
            raise ValueError("Native source fetcher must return bytes")
    _native_archive(manifest, dependency_files, native_bytes)

    for relative in sorted(dependency_files):
        entries[DEPENDENCY_PREFIX + relative] = (READ_ONLY, dependency_files[relative])
    manifest_bytes = json.dumps(manifest, indent=2, ensure_ascii=True).encode("utf-8") + b"\n"
    entries[DEPENDENCY_PREFIX + "manifest.json"] = (READ_ONLY, manifest_bytes)

    if SOURCE_BUILD_NAME in entries:
        raise ValueError(f"Tracked release input already contains {SOURCE_BUILD_NAME}")
    entries[SOURCE_BUILD_NAME] = (
        READ_ONLY,
        _source_build_markdown(
            commit, version, version_code, application_id, len(dependencies_list)
        ).encode("utf-8"),
    )

    output.mkdir(parents=True, exist_ok=True)
    target = output / SOURCE_INPUT_NAME
    _write_zip(target, entries)
    try:
        with zipfile.ZipFile(target) as archive:
            members = module._inspect_source(archive)
            _require_pre_metadata_files(members, module)
            if module.NATIVE_COORDINATE not in [
                item.get("coordinate") for item in manifest["native_sources"]
            ]:
                raise ValueError("Dependency manifest is missing the pinned native source record")
            module._dependency_manifest(archive)
    except Exception:
        target.unlink(missing_ok=True)
        if output.is_dir() and not any(output.iterdir()):
            output.rmdir()
        raise
    return target


# --------------------------------------------------------------------------- #
# extract
# --------------------------------------------------------------------------- #
def _extract_permissions(entry: zipfile.ZipInfo) -> int:
    mode = (entry.external_attr >> 16) & 0o777
    return EXECUTABLE if mode & 0o111 else READ_ONLY


def extract(archive, output):
    """Safely extract a source ZIP into an empty directory; returns entry names."""
    archive = Path(archive)
    output = Path(output)
    if archive.is_symlink() or not archive.is_file():
        raise ValueError(f"Source archive does not exist: {archive}")
    if output.is_symlink():
        raise ValueError(f"Extraction output must not be a symbolic link: {output}")
    if output.exists():
        if not output.is_dir():
            raise ValueError(f"Extraction output is not a directory: {output}")
        if any(output.iterdir()):
            raise ValueError(f"Extraction output directory is not empty: {output}")

    module = verifier()
    written: list[str] = []
    try:
        with zipfile.ZipFile(archive) as source:
            members = module._inspect_source(source)
            root = output.resolve()
            output.mkdir(parents=True, exist_ok=True)
            for name, entry in sorted(members.items()):
                if entry.is_dir():
                    continue
                data = source.read(entry)
                target = output / name
                resolved_target = target.resolve()
                if resolved_target != root and root not in resolved_target.parents:
                    raise ValueError(f"Refusing to extract outside the output: {name}")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                os.chmod(target, _extract_permissions(entry))
                written.append(name)
    except Exception:
        for name in written:
            (output / name).unlink(missing_ok=True)
        raise
    return written


# --------------------------------------------------------------------------- #
# finalize
# --------------------------------------------------------------------------- #
def _load_record(path) -> dict:
    path = Path(path)
    try:
        record = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ValueError(f"Missing release record: {path}") from error
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"Malformed release record {path}: {error}") from error
    if not isinstance(record, dict):
        raise ValueError("Release record must be a JSON object")
    return record


def _validate_record(record: dict) -> dict:
    module = verifier()
    _exact_keys(record, set(module.METADATA_KEYS), "Release record")
    if type(record["schema"]) is not int or record["schema"] != SCHEMA:
        raise ValueError("Release record schema must be 1")
    version = record["version"]
    if not isinstance(version, str) or not module.VERSION_RE.fullmatch(version):
        raise ValueError("Release record version must be a plain or pre-release versionName")
    version_code = record["version_code"]
    if (
        type(version_code) is not int
        or not 1 <= version_code <= module.VERSION_CODE_MAX
    ):
        raise ValueError(
            f"Release record version_code must be an integer between 1 and "
            f"{module.VERSION_CODE_MAX}"
        )
    application_id = record["application_id"]
    if not isinstance(application_id, str) or not module.APPLICATION_ID_RE.fullmatch(application_id):
        raise ValueError("Release record application_id must be a reverse-DNS Android ID")
    if not isinstance(record["commit"], str) or not COMMIT_RE.fullmatch(record["commit"]):
        raise ValueError("Release record commit must be 40 lowercase hex characters")
    certificate = record["certificate_sha256"]
    if not isinstance(certificate, str) or not CERTIFICATE_RE.fullmatch(certificate):
        raise ValueError(
            "Release record certificate_sha256 must be 64 uppercase hex characters"
        )
    apk_sha256 = record["apk_sha256"]
    if not isinstance(apk_sha256, str) or not HASH_RE.fullmatch(apk_sha256):
        raise ValueError("Release record apk_sha256 must be 64 lowercase hex characters")
    if record["abis"] != list(module.ABIS):
        raise ValueError(f"Release record abis must be {list(module.ABIS)}")
    toolchain = record["toolchain"]
    if not isinstance(toolchain, dict):
        raise ValueError("Release record toolchain must be a JSON object")
    _exact_keys(toolchain, set(module.TOOLCHAIN), "Release record toolchain")
    for key, expected in module.TOOLCHAIN.items():
        if type(toolchain[key]) is not type(expected) or toolchain[key] != expected:
            raise ValueError(
                f"Release record toolchain.{key} must be {expected!r}, got {toolchain[key]!r}"
            )
    checks = record["checks"]
    if not isinstance(checks, dict):
        raise ValueError("Release record checks must be a JSON object")
    _exact_keys(checks, set(module.CHECKS), "Release record checks")
    for name in module.CHECKS:
        if checks[name] is not True:
            raise ValueError(f"Release record check is not satisfied: {name}={checks[name]!r}")
    return record


def _write_sidecar(path: Path, name: str) -> str:
    with path.open("rb") as asset:
        digest = hashlib.file_digest(asset, "sha256").hexdigest()
    (path.parent / (name + ".sha256")).write_text(
        f"{digest}  {name}\n", encoding="ascii", newline="\n"
    )
    return digest


def finalize(archive, record, apk, output):
    """Add the release record and sidecars, then verify the four public assets."""
    archive = Path(archive)
    apk = Path(apk)
    output = Path(output)
    if archive.is_symlink() or not archive.is_file():
        raise ValueError(f"Source input archive does not exist: {archive}")
    if apk.is_symlink() or not apk.is_file():
        raise ValueError(f"Signed APK does not exist: {apk}")

    metadata = _validate_record(_load_record(record))
    module = verifier()
    version = metadata["version"]
    apk_name = APK_NAME_TEMPLATE.format(version=version)
    source_name = SOURCE_NAME_TEMPLATE.format(version=version)
    apk_sha256 = metadata["apk_sha256"]
    with apk.open("rb") as signed:
        if hashlib.file_digest(signed, "sha256").hexdigest() != apk_sha256:
            raise ValueError("Signed APK SHA-256 does not match the release record")

    with zipfile.ZipFile(archive) as source:
        members = module._inspect_source(source)
        if METADATA_NAME in members:
            raise ValueError(f"Source input archive already contains {METADATA_NAME}")
        _require_pre_metadata_files(members, module)
        source_build = source.read(SOURCE_BUILD_NAME).decode("utf-8", "replace")
        if metadata["commit"] not in source_build:
            raise ValueError(
                "SOURCE-BUILD.md does not record the release record commit; "
                "the source archive and record do not match"
            )
        preserved = [
            (member, source.read(member))
            for member in members.values()
            if not member.is_dir()
        ]

    if output.is_symlink():
        raise ValueError(f"Release output must not be a symbolic link: {output}")
    if output.exists():
        if not output.is_dir():
            raise ValueError(f"Release output is not a directory: {output}")
        unexpected = sorted(
            path.name for path in output.iterdir() if path.name != apk_name
        )
        if unexpected:
            raise ValueError(f"Unexpected files in the release output: {unexpected}")
    else:
        output.mkdir(parents=True)

    created: list[Path] = []
    apk_target = output / apk_name
    if apk_target.exists():
        if apk_target.is_symlink() or not apk_target.is_file():
            raise ValueError(f"Existing release APK is not a regular file: {apk_target}")
        with apk_target.open("rb") as existing:
            if hashlib.file_digest(existing, "sha256").hexdigest() != apk_sha256:
                raise ValueError(
                    "Existing release APK does not match the release record; "
                    "refusing to overwrite the signed APK"
                )
    else:
        apk_target.write_bytes(apk.read_bytes())
        created.append(apk_target)

    source_target = output / source_name
    metadata_bytes = json.dumps(metadata, indent=2, ensure_ascii=True).encode("utf-8") + b"\n"
    try:
        with zipfile.ZipFile(source_target, "w") as target:
            for member, data in preserved:
                info = zipfile.ZipInfo(member.filename, date_time=member.date_time)
                info.compress_type = (
                    member.compress_type
                    if member.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED)
                    else zipfile.ZIP_DEFLATED
                )
                info.create_system = member.create_system
                info.external_attr = member.external_attr
                target.writestr(info, data)
            target.writestr(_zip_info(METADATA_NAME, READ_ONLY), metadata_bytes)
        created.append(source_target)
        _write_sidecar(source_target, source_name)
        created.append(output / (source_name + ".sha256"))
        _write_sidecar(apk_target, apk_name)
        created.append(output / (apk_name + ".sha256"))
        module.verify(
            output,
            version,
            metadata["version_code"],
            metadata["commit"],
            metadata["application_id"],
            metadata["certificate_sha256"],
        )
    except Exception:
        for path in created:
            path.unlink(missing_ok=True)
        raise
    return source_target


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def _shared_output(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--output", type=Path, required=True, help="Output directory")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    commands = parser.add_subparsers(dest="command", required=True)

    prepare_parser = commands.add_parser("prepare", help="Build source-input.zip from a commit")
    prepare_parser.add_argument("--commit", required=True)
    _shared_output(prepare_parser)
    prepare_parser.add_argument(
        "--repo", type=Path, default=None, help="Git repository root (default: cwd)"
    )
    prepare_parser.add_argument(
        "--dependencies",
        type=Path,
        default=None,
        help=f"Generated dependency export (default: <repo>/{DEFAULT_DEPENDENCIES})",
    )
    prepare_parser.add_argument(
        "--native-archive",
        type=Path,
        default=None,
        help="Pre-downloaded pinned graphics-path archive instead of downloading",
    )

    extract_parser = commands.add_parser("extract", help="Safely extract a source ZIP")
    extract_parser.add_argument("--archive", type=Path, required=True)
    _shared_output(extract_parser)

    finalize_parser = commands.add_parser("finalize", help="Add the record and sidecars")
    finalize_parser.add_argument("--archive", type=Path, required=True)
    finalize_parser.add_argument("--record", type=Path, required=True)
    finalize_parser.add_argument("--apk", type=Path, required=True)
    _shared_output(finalize_parser)

    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            target = prepare(
                args.commit,
                args.output,
                repo=args.repo,
                dependencies=args.dependencies,
                native_archive=args.native_archive,
            )
            print(f"Wrote {target}")
        elif args.command == "extract":
            names = extract(args.archive, args.output)
            print(f"Extracted {len(names)} source files to {args.output}")
        else:
            target = finalize(args.archive, args.record, args.apk, args.output)
            print(f"Finalized {target}")
    except (OSError, ValueError, KeyError, TypeError, zipfile.BadZipFile, tarfile.TarError) as error:
        print(f"Android source packaging failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
