#!/usr/bin/env python3
"""Verify a built Android APK with SDK Build Tools and native ELF program headers.

Python 3.11+ standard library only. This tool runs the pinned Build Tools
``35.0.0`` executables (``aapt``, ``zipalign`` and, in signed mode,
``apksigner``), inspects the ELF program headers of every ``lib/<abi>/*.so``
entry, and writes a JSON report with exactly the metadata fields it can establish
locally:

    {
      "version": "...",              as declared in the APK manifest
      "version_code": 11,
      "application_id": "...",
      "certificate_sha256": "64 uppercase hex" | null,
      "apk_sha256": "64 lowercase hex",
      "abis": ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"],
      "checks": {
        "apk_signature": true,
        "apk_manifest": true,
        "zip_alignment_16k": true,
        "native_elf_alignment_16k": true
      }
    }

Signed mode requires ``apksigner verify --verbose --print-certs`` to report
exactly one signer whose certificate SHA-256 digest equals the expected pin, and
all four checks are then ``true``. ``--unsigned`` reports ``apk_signature`` as
``false`` with a ``null`` certificate and rejects the APK outright if any signing
certificate (``META-INF/*.RSA|*.DSA|*.EC|*.SF``, ``MANIFEST.MF`` or an APK
Signing Block) is detected: an unsigned candidate is never reported as a verified
signer.

This script never signs, aligns, repackages or otherwise modifies the APK; it only
reads it. Fail closed: nothing is written when any check fails.

Usage:
    verify-android-apk.py --apk PATH --version STR --version-code INT \
        --application-id STR --certificate-sha256 HEX --output PATH \
        [--sdk PATH] [--unsigned]
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile


SCHEMA = 1
BUILD_TOOLS = "35.0.0"
MIN_SDK = 26
TARGET_SDK = 36
PAGE_SIZE = 16384
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
CHECKS = (
    "apk_signature",
    "apk_manifest",
    "zip_alignment_16k",
    "native_elf_alignment_16k",
)
VERSION_RE = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?")
APPLICATION_ID_RE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")
SHA256_RE = re.compile(r"[0-9A-Fa-f]{64}")
VERSION_CODE_MAX = 2_100_000_000
APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"
SIGNATURE_ENTRY_RE = re.compile(
    r"^META-INF/(?:MANIFEST\.MF|[^/]+\.(?:RSA|DSA|EC|SF))$", re.IGNORECASE
)
LOAD_SEGMENT = 1
MAX_NATIVE_LIBRARY = 64 * 1024 * 1024
COMMAND_TIMEOUT = 600
SIGNER_DIGEST_RE = re.compile(
    r"^Signer #(\d+)(?:\s*\([^)]*\))? certificate SHA-256 digest:\s*([0-9A-Fa-f]+)\s*$",
    re.MULTILINE,
)


class VerificationError(Exception):
    """Any condition that makes the APK fail verification."""


class ElfError(VerificationError):
    """A native library is not a structurally valid ELF file."""


# --------------------------------------------------------------------------- #
# Small helpers
# --------------------------------------------------------------------------- #


def file_sha256(path) -> str:
    """Lowercase SHA-256 hex digest of a file (read in chunks)."""
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_sha256(value: str, label: str = "certificate SHA-256") -> str:
    """Return 64 uppercase hex digits; reject anything else."""
    if not isinstance(value, str):
        raise VerificationError(f"{label} must be a string, got {type(value).__name__}")
    cleaned = re.sub(r"[\s:]", "", value.strip())
    if not SHA256_RE.fullmatch(cleaned):
        raise VerificationError(f"{label} must be 64 hex characters: {value!r}")
    return cleaned.upper()


def tool_filenames(windows: bool) -> dict:
    """Executable names inside ``<sdk>/build-tools/35.0.0``."""
    if windows:
        return {
            "aapt": "aapt.exe",
            "zipalign": "zipalign.exe",
            "apksigner": "apksigner.bat",
        }
    return {"aapt": "aapt", "zipalign": "zipalign", "apksigner": "apksigner"}


def resolve_sdk(sdk=None) -> Path:
    """Resolve the SDK root from ``--sdk`` or ``ANDROID_HOME``."""
    value = sdk or os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not value:
        raise VerificationError(
            "Android SDK not given: pass --sdk PATH or set ANDROID_HOME"
        )
    root = Path(value).expanduser()
    if not root.is_dir():
        raise VerificationError(f"Android SDK directory not found: {root}")
    return root


def resolve_tools(sdk_root: Path, windows=None) -> dict:
    """Locate the pinned Build Tools executables, failing if any is missing."""
    if windows is None:
        windows = os.name == "nt"
    directory = Path(sdk_root) / "build-tools" / BUILD_TOOLS
    if not directory.is_dir():
        raise VerificationError(f"Build Tools {BUILD_TOOLS} not found: {directory}")
    names = tool_filenames(windows)
    tools = {name: directory / filename for name, filename in names.items()}
    missing = [str(path) for path in tools.values() if not path.is_file()]
    if missing:
        raise VerificationError(f"missing Build Tools executable(s): {missing}")
    return tools


def _quote_if_needed(value: str) -> str:
    return f'"{value}"' if " " in value or "\t" in value else value


def _tool_command(tool: Path, args):
    """Build the command line for a Build Tools executable.

    Batch shims (``apksigner.bat`` on Windows) run through ``cmd.exe /d /c``.
    ``cmd`` strips one leading and one trailing quote from the text after
    ``/c`` whenever the line contains more than two quotes, so the inner command
    line is wrapped in an extra pair of quotes; that keeps tool and APK paths
    containing spaces intact. The batch case therefore returns one command-line
    string, which ``subprocess`` passes to ``CreateProcess`` verbatim.
    """
    tokens = [str(tool), *[str(argument) for argument in args]]
    if str(tool).lower().endswith((".bat", ".cmd")):
        comspec = os.environ.get("COMSPEC") or "cmd.exe"
        inner = subprocess.list2cmdline(tokens)
        return f'{_quote_if_needed(comspec)} /d /c "{inner}"'
    return tokens


def run_command(command, *, timeout=COMMAND_TIMEOUT):
    """Run a command (list, or batch command-line string) and capture output."""
    completed = subprocess.run(
        command, capture_output=True, text=True, timeout=timeout, check=False
    )
    return completed.returncode, completed.stdout or "", completed.stderr or ""


def run_tool(tool: Path, args, *, label: str):
    """Run a Build Tools executable, converting launch failures to errors."""
    command = _tool_command(tool, args)
    try:
        return run_command(command)
    except (OSError, subprocess.SubprocessError) as error:
        raise VerificationError(f"{label} could not run ({tool}): {error}") from error


def _failure(label: str, code: int, stdout: str, stderr: str) -> VerificationError:
    detail = (stderr.strip() or stdout.strip() or "no output").replace("\n", " | ")
    return VerificationError(f"{label} failed (exit {code}): {detail}")


# --------------------------------------------------------------------------- #
# aapt dump badging
# --------------------------------------------------------------------------- #


def parse_badging(text: str) -> dict:
    """Parse the subset of ``aapt dump badging`` this verifier needs."""
    package = None
    for line in text.splitlines():
        if line.startswith("package: "):
            package = dict(re.findall(r"(\w+)='([^']*)'", line))
    if package is None:
        raise VerificationError("aapt output has no 'package:' line")

    def scalar(prefix: str, label: str):
        for line in text.splitlines():
            if line.startswith(prefix):
                return line[len(prefix) :].strip().strip("'")
        return None

    result = {
        "package": package.get("name", ""),
        "version_code": package.get("versionCode", ""),
        "version_name": package.get("versionName", ""),
        "min_sdk": scalar("sdkVersion:", "minSdkVersion"),
        "target_sdk": scalar("targetSdkVersion:", "targetSdkVersion"),
        "debuggable": any(
            line.strip() == "application-debuggable" for line in text.splitlines()
        ),
    }
    return result


def check_badging(
    badging: dict, *, version: str, version_code: int, application_id: str
) -> None:
    """Compare parsed badging against the expected release identity."""
    problems = []
    if badging["package"] != application_id:
        problems.append(
            f"application id {badging['package']!r} != {application_id!r}"
        )
    if badging["version_name"] != version:
        problems.append(f"versionName {badging['version_name']!r} != {version!r}")
    try:
        actual_code = int(badging["version_code"])
    except (TypeError, ValueError):
        actual_code = None
        problems.append(f"versionCode {badging['version_code']!r} is not an integer")
    if actual_code is not None and actual_code != version_code:
        problems.append(f"versionCode {actual_code} != {version_code}")
    if badging["min_sdk"] != str(MIN_SDK):
        problems.append(f"minSdkVersion {badging['min_sdk']!r} != {MIN_SDK}")
    if badging["target_sdk"] != str(TARGET_SDK):
        problems.append(f"targetSdkVersion {badging['target_sdk']!r} != {TARGET_SDK}")
    if badging["debuggable"]:
        problems.append("APK is debuggable (application-debuggable)")
    if problems:
        raise VerificationError("APK manifest mismatch: " + "; ".join(problems))


# --------------------------------------------------------------------------- #
# apksigner verify --verbose --print-certs
# --------------------------------------------------------------------------- #


def parse_apksigner_certificates(text: str) -> list:
    """Return the normalized SHA-256 digest of every reported signer."""
    digests = [normalize_sha256(match.group(2)) for match in SIGNER_DIGEST_RE.finditer(text)]
    indexes = sorted({int(match.group(1)) for match in SIGNER_DIGEST_RE.finditer(text)})
    if indexes and indexes != list(range(1, len(indexes) + 1)):
        raise VerificationError(f"apksigner signer numbering is inconsistent: {indexes}")
    return digests


def check_apksigner_output(text: str, expected_certificate: str) -> str:
    """Require exactly one signer matching the pinned certificate digest."""
    expected = normalize_sha256(expected_certificate)
    if "DOES NOT VERIFY" in text:
        raise VerificationError("apksigner reports the APK does not verify")
    digests = parse_apksigner_certificates(text)
    if not digests:
        raise VerificationError("apksigner reported no certificate SHA-256 digest")
    if len(digests) != 1:
        raise VerificationError(
            f"expected exactly one signer, apksigner reported {len(digests)}"
        )
    if digests[0] != expected:
        raise VerificationError(
            f"signer certificate {digests[0]} != expected {expected}"
        )
    return digests[0]


# --------------------------------------------------------------------------- #
# APK contents and signing markers
# --------------------------------------------------------------------------- #


def _open_apk(apk_path: Path) -> zipfile.ZipFile:
    try:
        return zipfile.ZipFile(apk_path)
    except (zipfile.BadZipFile, OSError) as error:
        raise VerificationError(f"not a readable APK/ZIP: {error}") from error


def signature_markers(apk_path: Path) -> list:
    """Return the signing markers found in the APK (empty when unsigned)."""
    found = []
    with _open_apk(apk_path) as archive:
        for info in archive.infolist():
            if SIGNATURE_ENTRY_RE.match(info.filename):
                found.append(info.filename)
    data = Path(apk_path).read_bytes()
    if APK_SIG_BLOCK_MAGIC in data:
        found.append("APK Signing Block")
    return sorted(set(found))


def native_library_entries(apk_path: Path) -> dict:
    """Map each ``lib/<abi>/*.so`` entry name to its ABI."""
    entries = {}
    with _open_apk(apk_path) as archive:
        for info in archive.infolist():
            name = info.filename
            if info.is_dir() or not name.endswith(".so"):
                continue
            parts = name.split("/")
            if len(parts) != 3 or parts[0] != "lib":
                continue
            entries[name] = parts[1]
    return entries


def read_apk_entry(apk_path: Path, name: str, limit: int = MAX_NATIVE_LIBRARY) -> bytes:
    with _open_apk(apk_path) as archive:
        try:
            info = archive.getinfo(name)
        except KeyError as error:
            raise VerificationError(f"APK entry disappeared: {name}") from error
        if info.file_size > limit:
            raise VerificationError(
                f"APK entry {name} is too large to inspect ({info.file_size} bytes)"
            )
        with archive.open(info) as handle:
            data = handle.read(limit + 1)
    if len(data) > limit:
        raise VerificationError(f"APK entry {name} exceeds {limit} bytes")
    return data


# --------------------------------------------------------------------------- #
# ELF program headers
# --------------------------------------------------------------------------- #


def parse_elf(data: bytes) -> dict:
    """Parse an ELF header and program header table with strict bounds checks.

    Supports ELF32 and ELF64, little and big endian. Raises ``ElfError`` for any
    truncation, bad identifier, impossible offset, or out-of-file segment.
    """
    if len(data) < 16:
        raise ElfError("ELF identification is truncated")
    ident = data[:16]
    if ident[:4] != b"\x7fELF":
        raise ElfError("missing ELF magic")
    elf_class, elf_data, elf_version = ident[4], ident[5], ident[6]
    if elf_class not in (1, 2):
        raise ElfError(f"unsupported ELF class {elf_class}")
    if elf_data not in (1, 2):
        raise ElfError(f"unsupported ELF data encoding {elf_data}")
    if elf_version != 1:
        raise ElfError(f"unsupported ELF version {elf_version}")
    bits = 32 if elf_class == 1 else 64
    endian = "<" if elf_data == 1 else ">"
    header_size = 52 if bits == 32 else 64
    expected_phentsize = 32 if bits == 32 else 56
    if len(data) < header_size:
        raise ElfError("ELF header is truncated")

    if bits == 32:
        (phoff,) = struct.unpack_from(endian + "I", data, 28)
        _, phentsize, phnum = struct.unpack_from(endian + "HHH", data, 40)
    else:
        (phoff,) = struct.unpack_from(endian + "Q", data, 32)
        _, phentsize, phnum = struct.unpack_from(endian + "HHH", data, 52)

    headers = []
    if phnum:
        if phentsize < expected_phentsize:
            raise ElfError(
                f"program header entry size {phentsize} < {expected_phentsize}"
            )
        if phoff == 0:
            raise ElfError("program header offset is zero with non-zero count")
        end = phoff + phnum * phentsize
        if end > len(data):
            raise ElfError(
                f"program header table [{phoff}, {end}) exceeds file size {len(data)}"
            )
        for index in range(phnum):
            base = phoff + index * phentsize
            if bits == 32:
                (
                    p_type,
                    p_offset,
                    p_vaddr,
                    _paddr,
                    p_filesz,
                    p_memsz,
                    _flags,
                    p_align,
                ) = struct.unpack_from(endian + "IIIIIIII", data, base)
            else:
                p_type, _flags = struct.unpack_from(endian + "II", data, base)
                (
                    p_offset,
                    p_vaddr,
                    _paddr,
                    p_filesz,
                    p_memsz,
                    p_align,
                ) = struct.unpack_from(endian + "QQQQQQ", data, base + 8)
            if p_offset > len(data):
                raise ElfError(
                    f"program header {index} offset {p_offset} exceeds file size {len(data)}"
                )
            if p_type == LOAD_SEGMENT and p_filesz:
                if p_offset + p_filesz > len(data):
                    raise ElfError(
                        f"PT_LOAD segment {index} [{p_offset}, {p_offset + p_filesz}) "
                        f"exceeds file size {len(data)}"
                    )
            headers.append(
                {
                    "index": index,
                    "type": p_type,
                    "offset": p_offset,
                    "vaddr": p_vaddr,
                    "filesz": p_filesz,
                    "memsz": p_memsz,
                    "align": p_align,
                }
            )

    return {
        "bits": bits,
        "endian": "little" if elf_data == 1 else "big",
        "program_headers": headers,
    }


def load_segments(elf: dict) -> list:
    return [header for header in elf["program_headers"] if header["type"] == LOAD_SEGMENT]


def check_elf_load_alignment(elf: dict, page_size: int = PAGE_SIZE) -> list:
    """Return 16 KiB alignment problems for the PT_LOAD segments of parsed ELF."""
    problems = []
    segments = load_segments(elf)
    if not segments:
        problems.append("no PT_LOAD program header")
    for segment in segments:
        align = segment["align"]
        if align < page_size or align & (align - 1):
            problems.append(
                f"PT_LOAD[{segment['index']}] p_align {align} is not a power of "
                f"two >= {page_size}"
            )
        delta = segment["vaddr"] - segment["offset"]
        if delta < 0 or delta % page_size:
            problems.append(
                f"PT_LOAD[{segment['index']}] p_vaddr-p_offset {delta} is not "
                f"divisible by {page_size}"
            )
    return problems


def elf_alignment_problems(data: bytes, page_size: int = PAGE_SIZE) -> list:
    """Parse ``data`` and return alignment problems (raises ``ElfError``)."""
    return check_elf_load_alignment(parse_elf(data), page_size)


def inspect_native_libraries(apk_path: Path) -> dict:
    """Inspect every ``lib/<abi>/*.so`` entry; enforce the four expected ABIs.

    Returns ``{"abis": [...], "libraries": [{"entry", "abi", "bits", "endian"}]}``.
    Raises ``VerificationError`` for malformed ELFs, misaligned segments, unknown
    ABIs, or a missing expected ABI.
    """
    entries = native_library_entries(apk_path)
    if not entries:
        raise VerificationError("APK contains no lib/<abi>/*.so native libraries")
    unknown = sorted({abi for abi in entries.values()} - set(ABIS))
    if unknown:
        raise VerificationError(f"unexpected native ABI(s): {unknown}")
    present = {abi for abi in entries.values()}
    missing = [abi for abi in ABIS if abi not in present]
    if missing:
        raise VerificationError(f"missing native ABI(s): {missing}")

    libraries = []
    problems = []
    for name in sorted(entries):
        abi = entries[name]
        data = read_apk_entry(apk_path, name)
        try:
            elf = parse_elf(data)
        except ElfError as error:
            problems.append(f"{name}: {error}")
            continue
        for problem in check_elf_load_alignment(elf):
            problems.append(f"{name}: {problem}")
        libraries.append(
            {
                "entry": name,
                "abi": abi,
                "bits": elf["bits"],
                "endian": elf["endian"],
            }
        )
    if problems:
        raise VerificationError("native ELF checks failed: " + "; ".join(problems))
    return {"abis": [abi for abi in ABIS if abi in present], "libraries": libraries}


# --------------------------------------------------------------------------- #
# SDK tool checks
# --------------------------------------------------------------------------- #


def run_aapt(tools: dict, apk_path: Path) -> dict:
    code, stdout, stderr = run_tool(
        tools["aapt"], ["dump", "badging", str(apk_path)], label="aapt dump badging"
    )
    if code != 0:
        raise _failure("aapt dump badging", code, stdout, stderr)
    return parse_badging(stdout)


def run_zipalign(tools: dict, apk_path: Path) -> None:
    """Require 16 KiB page alignment (``zipalign -c -P 16 -v 4``)."""
    page_kb = PAGE_SIZE // 1024
    args = ["-c", "-P", str(page_kb), "-v", "4", str(apk_path)]
    code, stdout, stderr = run_tool(tools["zipalign"], args, label="zipalign -c")
    if code != 0:
        raise _failure(f"zipalign -c -P {page_kb} -v 4", code, stdout, stderr)


def run_apksigner(tools: dict, apk_path: Path, expected_certificate: str) -> str:
    args = ["verify", "--verbose", "--print-certs", str(apk_path)]
    code, stdout, stderr = run_tool(tools["apksigner"], args, label="apksigner verify")
    if code != 0:
        raise _failure("apksigner verify", code, stdout, stderr)
    return check_apksigner_output(stdout + "\n" + stderr, expected_certificate)


# --------------------------------------------------------------------------- #
# Verification driver and report
# --------------------------------------------------------------------------- #


def verify(
    apk,
    *,
    version: str,
    version_code: int,
    application_id: str,
    certificate_sha256=None,
    sdk=None,
    unsigned: bool = False,
) -> dict:
    """Verify the APK end to end and return the report dictionary."""
    apk_path = Path(apk)
    if not apk_path.is_file():
        raise VerificationError(f"APK not found: {apk_path}")
    if VERSION_RE.fullmatch(version) is None:
        raise VerificationError(f"invalid version {version!r}")
    if not isinstance(version_code, int) or not 0 < version_code <= VERSION_CODE_MAX:
        raise VerificationError(f"invalid version code {version_code!r}")
    if APPLICATION_ID_RE.fullmatch(application_id) is None:
        raise VerificationError(f"invalid application id {application_id!r}")

    expected_certificate = None
    if unsigned:
        if certificate_sha256:
            raise VerificationError(
                "--unsigned does not accept --certificate-sha256: no signer is verified"
            )
    else:
        if not certificate_sha256:
            raise VerificationError(
                "signed verification requires --certificate-sha256 (or pass --unsigned)"
            )
        expected_certificate = normalize_sha256(certificate_sha256)

    tools = resolve_tools(resolve_sdk(sdk))
    apk_digest = file_sha256(apk_path)

    # ZIP alignment and manifest are required in both modes.
    run_zipalign(tools, apk_path)
    badging = run_aapt(tools, apk_path)
    check_badging(
        badging,
        version=version,
        version_code=version_code,
        application_id=application_id,
    )

    certificate = None
    if unsigned:
        markers = signature_markers(apk_path)
        if markers:
            raise VerificationError(
                "unsigned mode but the APK carries signing material: "
                + ", ".join(markers)
            )
    else:
        certificate = run_apksigner(tools, apk_path, expected_certificate)

    native = inspect_native_libraries(apk_path)

    return {
        "version": badging["version_name"],
        "version_code": int(badging["version_code"]),
        "application_id": badging["package"],
        "certificate_sha256": certificate,
        "apk_sha256": apk_digest,
        "abis": list(native["abis"]),
        "checks": {
            "apk_signature": certificate is not None,
            "apk_manifest": True,
            "zip_alignment_16k": True,
            "native_elf_alignment_16k": True,
        },
    }


def check_report_shape(report: dict) -> None:
    expected = {
        "version",
        "version_code",
        "application_id",
        "certificate_sha256",
        "apk_sha256",
        "abis",
        "checks",
    }
    if set(report) != expected:
        raise VerificationError(
            f"report keys mismatch: {sorted(set(report) ^ expected)}"
        )
    if tuple(report["checks"]) != CHECKS:
        raise VerificationError(f"report check keys mismatch: {list(report['checks'])}")
    if any(not isinstance(report["checks"][name], bool) for name in CHECKS):
        raise VerificationError("report checks must be booleans")


def write_report(path, report: dict) -> Path:
    """Write the JSON report, refusing to persist a failing/unverifiable report."""
    check_report_shape(report)
    target = Path(path)
    if target.parent and not target.parent.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return target


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Verify an Android APK with Build Tools 35.0.0 and ELF program headers.",
    )
    parser.add_argument("--apk", required=True, help="path to the APK to verify")
    parser.add_argument("--version", required=True, help="expected manifest versionName")
    parser.add_argument(
        "--version-code", required=True, type=int, help="expected manifest versionCode"
    )
    parser.add_argument(
        "--application-id", required=True, help="expected applicationId"
    )
    parser.add_argument(
        "--certificate-sha256",
        default=None,
        help="expected signing certificate SHA-256 (required unless --unsigned)",
    )
    parser.add_argument("--output", required=True, help="path of the JSON report")
    parser.add_argument(
        "--sdk",
        default=None,
        help="Android SDK root (default: ANDROID_HOME, then ANDROID_SDK_ROOT)",
    )
    parser.add_argument(
        "--unsigned",
        action="store_true",
        help="verify an unsigned APK: apk_signature false, no certificate claimed",
    )
    return parser


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    try:
        report = verify(
            args.apk,
            version=args.version,
            version_code=args.version_code,
            application_id=args.application_id,
            certificate_sha256=args.certificate_sha256,
            sdk=args.sdk,
            unsigned=args.unsigned,
        )
        target = write_report(args.output, report)
    except VerificationError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    checks = " ".join(
        f"{name}={'true' if value else 'false'}"
        for name, value in report["checks"].items()
    )
    print(f"verified {Path(args.apk).name}: {checks}")
    print(f"report: {target}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
