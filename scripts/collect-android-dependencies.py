#!/usr/bin/env python3
"""Export Android release dependency sources, POMs, licenses, and notices.

Consumes the JSON inventory written by ``:app:exportReleaseDependencies``
(``android/release-dependencies.gradle.kts``) and produces a deterministic
directory that is copied into the release source ZIP as
``android/dependency-sources/``:

    manifest.json                     exact schema from specs/release-pipeline.md
    LICENSE-APACHE-2.0.txt            full standard Apache-2.0 text
    files/<g>-<n>-<v>-sources.jar     byte-identical Maven source JARs
    files/<g>-<n>-<v>.pom             byte-identical Maven POMs
    notices/<coord>__<role>__<entry>  licenses/notices captured from the archives

Runtime artifacts are read only to capture licenses/notices (including
``classes.jar`` inside AARs); they are never copied into the export. Standard
library only: no network access, no package installation, no remote actions.

Usage: collect-android-dependencies.py --input PATH --output PATH
"""

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import sys
import xml.etree.ElementTree as ElementTree
import zipfile


SCHEMA = 1
MANIFEST_NAME = "manifest.json"
FILES_DIR = "files"
NOTICES_DIR = "notices"
APACHE_LICENSE_NAME = "LICENSE-APACHE-2.0.txt"

APACHE_2_0 = {
    "name": "Apache-2.0",
    "url": "https://www.apache.org/licenses/LICENSE-2.0.txt",
}

# Normalized (lowercase, non-alphanumerics collapsed to spaces) POM license names
# that are Apache-2.0. Extend only after reviewing the actual license text; the
# release pipeline fails closed on anything not listed here.
ALLOWED_LICENSES = {
    key: APACHE_2_0
    for key in (
        "apache 2",
        "apache 2 0",
        "apache license 2 0",
        "apache license v 2 0",
        "apache license v2 0",
        "apache license version 2",
        "apache license version 2 0",
        "apache software license 2 0",
        "apache software license version 2 0",
        "the apache software license 2 0",
        "the apache software license version 2 0",
        "the apache license version 2 0",
        "asl 2 0",
    )
}

# --------------------------------------------------------------------------- #
# Reviewed parent-POM license inheritance
# --------------------------------------------------------------------------- #
# Generic parent-POM license inheritance is deliberately NOT implemented: a POM
# without <licenses> fails closed instead of borrowing a license from upstream.
# A few published POMs declare no <licenses> at all and are covered by their
# parent POM only, so each accepted inheritance is recorded here after the
# parent POM was fetched by hand and reviewed. A record is used ONLY when the
# child coordinate and the POM <parent> coordinate both match the reviewed pair
# exactly (bit-for-bit strings), so a different child artifact/version or a
# different parent artifact/group/version can never pick up the claim.
REVIEWED_PARENT_LICENSES = {
    (
        "com.google.guava:listenablefuture:1.0",
        "com.google.guava:guava-parent:26.0-android",
    ): {
        "license": APACHE_2_0,
        # Provenance: parent POM manually downloaded and reviewed; it stays
        # outside the export because only the child POM is redistributed.
        "provenance": (
            "Parent POM manually fetched and reviewed: "
            "https://repo.maven.apache.org/maven2/com/google/guava/guava-parent/"
            "26.0-android/guava-parent-26.0-android.pom "
            "SHA-256 f8698ab46ca996ce889c1afc8ca4f25eb8ac6b034dc898d4583742360016cc04. "
            "Its single <licenses>/<license> entry names 'The Apache License, "
            "Version 2.0' with url http://www.apache.org/licenses/LICENSE-2.0.txt, "
            "so the child POM com.google.guava:listenablefuture:1.0 (no "
            "<licenses> of its own) is Apache-2.0."
        ),
    },
}

# Minimum size of android/licenses/Apache-2.0.txt to reject stubs.
MIN_APACHE_LICENSE_BYTES = 4096
MAX_NOTICE_BYTES = 1024 * 1024
MAX_NESTED_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_EXPORTED_NAME_LENGTH = 180

NESTED_CLASSES_ENTRY = "classes.jar"
COORDINATE_RE = re.compile(r"[A-Za-z0-9._+-]+:[A-Za-z0-9._+-]+:[A-Za-z0-9._+-]+")
_UNSAFE_TOKEN_RE = re.compile(r"[^A-Za-z0-9._-]+")
_NOTICE_NAME_RE = re.compile(
    r"(?:notice|licence|license|copying)(?:[._-][A-Za-z0-9.-]+)?", re.IGNORECASE
)
_APACHE_URL_RE = re.compile(r"apache\.org/licenses/license-2\.0", re.IGNORECASE)


# --------------------------------------------------------------------------- #
# License handling
# --------------------------------------------------------------------------- #
def _normalize_license_name(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", value.lower()).strip()


def resolve_license(name: str, url: str, coordinate: str) -> dict:
    """Map a POM <license> to the canonical record, or fail for manual review."""
    canonical = ALLOWED_LICENSES.get(_normalize_license_name(name))
    if canonical is None and _APACHE_URL_RE.search(url):
        canonical = APACHE_2_0
    if canonical is None:
        raise ValueError(
            f"Unrecognized license for {coordinate}: name={name!r} url={url!r}. "
            "Review the license text, then add it to ALLOWED_LICENSES in "
            "scripts/collect-android-dependencies.py before publishing."
        )
    return dict(canonical)


def _pom_parent_coordinate(root: ElementTree.Element):
    """Return the POM <parent> as ``group:name:version``, or None when absent."""
    for child in root:
        if _local_name(child.tag) != "parent":
            continue
        fields = {_local_name(field.tag): (field.text or "").strip() for field in child}
        group = fields.get("groupId")
        artifact = fields.get("artifactId")
        version = fields.get("version")
        if group and artifact and version:
            return f"{group}:{artifact}:{version}"
    return None


def _pom_parent_description(root: ElementTree.Element) -> str:
    parent = _pom_parent_coordinate(root)
    if parent is None:
        return "no exported parent POM"
    return f"parent POM {parent}"


def _reviewed_parent_license(coordinate: str, parent: str):
    """Return the reviewed license for an exact (child, parent) pair, else None."""
    reviewed = REVIEWED_PARENT_LICENSES.get((coordinate, parent))
    if reviewed is None:
        return None
    return dict(reviewed["license"])


def parse_pom_licenses(pom: bytes, coordinate: str) -> list:
    """Return the canonical license records declared by a Maven POM."""
    if pom.startswith(b"\xef\xbb\xbf"):
        pom = pom[3:]
    try:
        root = ElementTree.fromstring(pom)
    except ElementTree.ParseError as error:
        raise ValueError(f"Malformed POM for {coordinate}: {error}") from error
    if _local_name(root.tag) != "project":
        raise ValueError(f"POM for {coordinate} has no <project> root element")
    declared = []
    for container in root:
        if _local_name(container.tag) != "licenses":
            continue
        for element in container:
            if _local_name(element.tag) != "license":
                continue
            declared.append(
                {_local_name(field.tag): (field.text or "").strip() for field in element}
            )
    if not declared:
        reviewed = _reviewed_parent_license(coordinate, _pom_parent_coordinate(root))
        if reviewed is not None:
            return [reviewed]
        raise ValueError(
            f"POM for {coordinate} declares no <licenses> ({_pom_parent_description(root)}); "
            "a license must be recorded explicitly before publishing, or the exact "
            "child/parent pair added to REVIEWED_PARENT_LICENSES after reviewing the "
            "parent POM."
        )
    licenses = []
    for fields in declared:
        record = resolve_license(fields.get("name", ""), fields.get("url", ""), coordinate)
        if record not in licenses:
            licenses.append(record)
    return licenses


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


# --------------------------------------------------------------------------- #
# License/notice capture
# --------------------------------------------------------------------------- #
def _file_stem(coordinate: str) -> str:
    return "-".join(_token(part) for part in coordinate.split(":"))


def _token(value: str) -> str:
    token = _UNSAFE_TOKEN_RE.sub("-", value).strip("-")
    if not token or set(token) <= {"."}:
        return "_"
    return token


def _notice_relative_path(coordinate: str, role: str, entry_name: str) -> str:
    token = _UNSAFE_TOKEN_RE.sub("_", entry_name).strip("_") or "notice"
    name = f"{_file_stem(coordinate)}__{role}__{token}"
    if len(name) > MAX_EXPORTED_NAME_LENGTH:
        digest = hashlib.sha256(
            f"{coordinate}\0{role}\0{entry_name}".encode("utf-8")
        ).hexdigest()[:16]
        name = f"{_file_stem(coordinate)}__{role}__{digest}"
    return f"{NOTICES_DIR}/{name}"


def collect_notices(archive: bytes, coordinate: str, role: str) -> dict:
    """Return {relative path: bytes} for licenses/notices inside an archive.

    Recurses into ``classes.jar`` (AAR payload) up to two levels so the notices of
    Android libraries are captured from the bytecode archive as well.
    """
    notices = {}
    _scan_archive(archive, coordinate, role, 0, notices)
    return notices


def _scan_archive(archive_bytes, coordinate, role, depth, notices):
    try:
        with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
            for info in sorted(archive.infolist(), key=lambda entry: entry.filename):
                if info.is_dir():
                    continue
                name = info.filename
                basename = name.rsplit("/", 1)[-1]
                if basename == NESTED_CLASSES_ENTRY and depth < 2:
                    nested = archive.read(info)
                    if len(nested) > MAX_NESTED_ARCHIVE_BYTES:
                        raise ValueError(
                            f"Oversized nested archive in {coordinate} ({role}): "
                            f"{name} is {len(nested)} bytes"
                        )
                    _scan_archive(nested, coordinate, f"{role}-classes", depth + 1, notices)
                elif _NOTICE_NAME_RE.fullmatch(basename):
                    content = archive.read(info)
                    if len(content) > MAX_NOTICE_BYTES:
                        raise ValueError(
                            f"Oversized license/notice in {coordinate} ({role}): "
                            f"{name} is {len(content)} bytes"
                        )
                    if b"\x00" in content[:1024]:
                        # Binary payload that only happens to look like a notice file.
                        continue
                    notices[_notice_relative_path(coordinate, role, name)] = content
    except zipfile.BadZipFile as error:
        raise ValueError(
            f"Not a readable ZIP archive for {coordinate} ({role}): {error}"
        ) from error


# --------------------------------------------------------------------------- #
# Input parsing helpers
# --------------------------------------------------------------------------- #
def _object(value, label: str) -> dict:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a JSON object")
    return value


def _text(value, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a nonempty string")
    return value


def _relative_path(value, label: str) -> str:
    text = _text(value, label).replace("\\", "/")
    if text.startswith("/") or re.match(r"[A-Za-z]:", text):
        raise ValueError(f"{label} must be relative: {text}")
    parts = [part for part in text.split("/") if part not in ("", ".")]
    if not parts or ".." in parts:
        raise ValueError(f"{label} must stay inside the export directory: {text}")
    return "/".join(parts)


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _zip_bytes(path: Path, kind: str, coordinate: str) -> bytes:
    data = path.read_bytes()
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            if not archive.namelist():
                raise ValueError(
                    f"{kind} for {coordinate} is an empty ZIP archive: {path}"
                )
    except zipfile.BadZipFile as error:
        raise ValueError(
            f"{kind} for {coordinate} is not a readable ZIP archive: {path} ({error})"
        ) from error
    return data


def _existing_file(value, coordinate: str, kind: str, label: str) -> Path:
    path = Path(_text(value, label))
    if not path.is_file():
        raise ValueError(f"Missing {kind} for {coordinate}: {path}")
    return path


def _apache_license_text(path) -> bytes:
    if not isinstance(path, Path) or not path.is_file():
        raise ValueError(f"Missing Apache-2.0 license text: {path}")
    data = path.read_bytes()
    text = data.decode("utf-8", "replace")
    if "Apache License" not in text or "Version 2.0" not in text:
        raise ValueError(
            f"{path} does not contain the Apache License, Version 2.0 text"
        )
    if len(data) < MIN_APACHE_LICENSE_BYTES:
        raise ValueError(
            f"{path} does not contain the full Apache License, Version 2.0 text "
            f"({len(data)} bytes)"
        )
    return data


def _prepare_output(output: Path) -> None:
    if output.is_symlink():
        raise ValueError(f"Export directory must not be a symbolic link: {output}")
    if output.exists():
        if not output.is_dir():
            raise ValueError(f"Export path is not a directory: {output}")
        for child in sorted(output.iterdir()):
            if child.is_dir() and not child.is_symlink():
                shutil.rmtree(child)
            else:
                child.unlink()
    output.mkdir(parents=True, exist_ok=True)


def _write(output: Path, relative: str, data: bytes) -> None:
    target = output / relative
    try:
        target.resolve().relative_to(output)
    except ValueError:
        raise ValueError(
            f"Refusing to write outside the export directory: {relative}"
        ) from None
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)


# --------------------------------------------------------------------------- #
# Export
# --------------------------------------------------------------------------- #
def _dependency_entries(dependencies) -> list:
    if not isinstance(dependencies, list) or not dependencies:
        raise ValueError("Dependency input must list at least one dependency")
    entries = []
    seen = set()
    for index, value in enumerate(dependencies):
        label = f"dependencies[{index}]"
        record = _object(value, label)
        coordinate = _text(record.get("coordinate"), f"{label}.coordinate").strip()
        if not COORDINATE_RE.fullmatch(coordinate):
            raise ValueError(f"{label}.coordinate must be group:name:version: {coordinate!r}")
        if coordinate in seen:
            raise ValueError(f"Duplicate dependency coordinate: {coordinate}")
        seen.add(coordinate)
        entries.append(
            {
                "coordinate": coordinate,
                "source": _existing_file(
                    record.get("source"), coordinate, "source JAR", f"{label}.source"
                ),
                "pom": _existing_file(record.get("pom"), coordinate, "POM", f"{label}.pom"),
                "runtime": _existing_file(
                    record.get("runtime"), coordinate, "runtime artifact", f"{label}.runtime"
                ),
            }
        )
    return sorted(entries, key=lambda entry: entry["coordinate"])


def _native_sources(payload, output: Path) -> list:
    """Copy optional pinned native source archives recorded with provenance."""
    value = payload.get("native_sources")
    if value is None:
        return []
    if not isinstance(value, list) or not value:
        raise ValueError("native_sources must be a nonempty list when present")
    records = []
    seen = set()
    for index, item in enumerate(value):
        label = f"native_sources[{index}]"
        record = _object(item, label)
        coordinate = _text(record.get("coordinate"), f"{label}.coordinate").strip()
        if not COORDINATE_RE.fullmatch(coordinate):
            raise ValueError(f"{label}.coordinate must be group:name:version: {coordinate!r}")
        relative = _relative_path(record.get("path"), f"{label}.path")
        if relative in seen:
            raise ValueError(f"Duplicate native source path: {relative}")
        seen.add(relative)
        provenance = _text(record.get("provenance"), f"{label}.provenance")
        source = Path(_text(record.get("source"), f"{label}.source"))
        if not source.is_file():
            raise ValueError(f"Missing native source archive: {source}")
        data = source.read_bytes()
        if not data:
            raise ValueError(f"Empty native source archive: {source}")
        _write(output, relative, data)
        records.append(
            {
                "coordinate": coordinate,
                "path": relative,
                "sha256": _sha256(data),
                "provenance": provenance,
            }
        )
    return sorted(records, key=lambda record: record["path"])


def collect(input_path, output_path) -> dict:
    """Export a dependency source directory and return the written manifest."""
    input_path = Path(input_path)
    try:
        payload = json.loads(input_path.read_text(encoding="utf-8"))
    except FileNotFoundError as error:
        raise ValueError(f"Missing dependency input inventory: {input_path}") from error
    except json.JSONDecodeError as error:
        raise ValueError(
            f"Malformed dependency input inventory {input_path}: {error}"
        ) from error
    payload = _object(payload, "Dependency input")
    if payload.get("schema") != SCHEMA:
        raise ValueError(f"Dependency input schema must be {SCHEMA}")

    license_path = Path(_text(payload.get("license_file"), "license_file"))
    license_bytes = _apache_license_text(license_path)
    entries = _dependency_entries(payload.get("dependencies"))

    output = Path(output_path).resolve()
    _prepare_output(output)
    _write(output, APACHE_LICENSE_NAME, license_bytes)

    notices = {}
    dependencies = []
    exported_stems = {}
    for entry in entries:
        coordinate = entry["coordinate"]
        source_bytes = _zip_bytes(entry["source"], "Source JAR", coordinate)
        pom_bytes = entry["pom"].read_bytes()
        runtime_bytes = _zip_bytes(entry["runtime"], "Runtime artifact", coordinate)
        licenses = parse_pom_licenses(pom_bytes, coordinate)

        stem = _file_stem(coordinate)
        if stem in exported_stems:
            raise ValueError(
                f"Dependency coordinates {exported_stems[stem]} and {coordinate} map to the "
                f"same export file name ({stem})"
            )
        exported_stems[stem] = coordinate
        source_relative = f"{FILES_DIR}/{stem}-sources.jar"
        pom_relative = f"{FILES_DIR}/{stem}.pom"
        _write(output, source_relative, source_bytes)
        _write(output, pom_relative, pom_bytes)
        notices.update(collect_notices(source_bytes, coordinate, "source"))
        notices.update(collect_notices(runtime_bytes, coordinate, "runtime"))

        dependencies.append(
            {
                "coordinate": coordinate,
                "source": source_relative,
                "source_sha256": _sha256(source_bytes),
                "pom": pom_relative,
                "pom_sha256": _sha256(pom_bytes),
                "licenses": licenses,
            }
        )

    for relative in sorted(notices):
        _write(output, relative, notices[relative])

    native_sources = _native_sources(payload, output)

    manifest = {"schema": SCHEMA, "dependencies": dependencies}
    if native_sources:
        manifest["native_sources"] = native_sources
    _write(
        output,
        MANIFEST_NAME,
        json.dumps(manifest, indent=2, ensure_ascii=True).encode("utf-8") + b"\n",
    )
    return manifest


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument(
        "--input", type=Path, required=True, help="JSON inventory from exportReleaseDependencies"
    )
    parser.add_argument(
        "--output", type=Path, required=True, help="Export directory (recreated on each run)"
    )
    args = parser.parse_args(argv)
    try:
        manifest = collect(args.input, args.output)
    except (OSError, ValueError, KeyError, TypeError, ElementTree.ParseError) as error:
        print(f"Dependency export failed: {error}", file=sys.stderr)
        return 1
    output = Path(args.output).resolve()
    print(f"Exported {len(manifest['dependencies'])} dependency source archives")
    print(f"Manifest: {output / MANIFEST_NAME}")
    print(f"Apache license: {output / APACHE_LICENSE_NAME}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
