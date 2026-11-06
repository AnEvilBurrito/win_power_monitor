"""Synthetic tests for the Android APK verifier.

Covers the ELF program-header parser with hand-built ELF32/ELF64 little/big
endian images, ZIP/APK native-library inspection, aapt/apksigner output parsing,
and the CLI end to end with a mocked SDK (no SDK, APK or network required).
"""

from contextlib import redirect_stderr, redirect_stdout
import importlib.util
import io
import json
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest import mock
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "verify_android_apk",
    Path(__file__).resolve().parents[1] / "verify-android-apk.py",
)
assert SPEC is not None and SPEC.loader is not None
verifier = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verifier)

VERSION = "0.7.0-rc.1"
VERSION_CODE = 11
APPLICATION_ID = "io.github.anevilburrito.wpm"
CERTIFICATE = "D3D54F63925EAD1F229259CA6C711A403DDCE9D06B7B7C76CDFF699C516EF6CF"
CERTIFICATE_LOWER = CERTIFICATE.lower()
ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

BADGING = (
    f"package: name='{APPLICATION_ID}' versionCode='{VERSION_CODE}' "
    f"versionName='{VERSION}' compileSdkVersion='36' compileSdkVersionCodename='16'\n"
    "sdkVersion:'26'\n"
    "targetSdkVersion:'36'\n"
    "application-label:'WPM'\n"
    "native-code: 'arm64-v8a' 'armeabi-v7a' 'x86' 'x86_64'\n"
)
DEBUGGABLE_BADGING = BADGING + "application-debuggable\n"
APKSIGNER_OK = (
    "Verifies\n"
    "Verified using v1 scheme (JAR signing): false\n"
    "Verified using v2 scheme (APK Signature Scheme v2): true\n"
    "Number of signers: 1\n"
    "Signer #1 certificate DN: CN=win_power_monitor\n"
    f"Signer #1 certificate SHA-256 digest: {CERTIFICATE_LOWER}\n"
    "Signer #1 certificate SHA-1 digest: 0123456789abcdef0123456789abcdef01234567\n"
)


# --------------------------------------------------------------------------- #
# Synthetic ELF images
# --------------------------------------------------------------------------- #


def build_elf(
    *,
    bits=64,
    endian="<",
    align=16384,
    vaddr_delta=0,
    filesz=8,
    phnum=1,
    ph_type=1,
    magic=b"\x7fELF",
    segment_offset=0x1000,
    elf_class=None,
    elf_data=None,
):
    """Build a minimal but structurally valid ELF image with program headers."""
    header_size, phentsize = (64, 56) if bits == 64 else (52, 32)
    phoff = header_size
    total = max(segment_offset + filesz, phoff + phnum * phentsize, header_size)
    buffer = bytearray(total)
    ident = bytearray(16)
    ident[:4] = magic
    ident[4] = elf_class if elf_class is not None else (2 if bits == 64 else 1)
    ident[5] = elf_data if elf_data is not None else (1 if endian == "<" else 2)
    ident[6] = 1
    buffer[:16] = ident
    vaddr = segment_offset + vaddr_delta
    if bits == 64:
        struct.pack_into(
            endian + "HHIQQQIHHHHHH",
            buffer,
            16,
            2,
            183,
            1,
            0,
            phoff,
            0,
            0,
            header_size,
            phentsize,
            phnum,
            64,
            0,
            0,
        )
        for index in range(phnum):
            struct.pack_into(
                endian + "IIQQQQQQ",
                buffer,
                phoff + index * phentsize,
                ph_type,
                0,
                segment_offset,
                vaddr,
                vaddr,
                filesz,
                filesz,
                align,
            )
    else:
        struct.pack_into(
            endian + "HHIIIIIHHHHHH",
            buffer,
            16,
            2,
            40,
            1,
            0,
            phoff,
            0,
            0,
            header_size,
            phentsize,
            phnum,
            40,
            0,
            0,
        )
        for index in range(phnum):
            struct.pack_into(
                endian + "IIIIIIII",
                buffer,
                phoff + index * phentsize,
                ph_type,
                segment_offset,
                vaddr,
                vaddr,
                filesz,
                filesz,
                0,
                align,
            )
    return bytes(buffer)


def patch_elf(data, offset, fmt, value, endian="<"):
    buffer = bytearray(data)
    struct.pack_into(endian + fmt, buffer, offset, value)
    return bytes(buffer)


def build_apk(path, *, abis=ABIS, elf_factory=None, extra_entries=None, comment=None):
    """Write a synthetic APK (ZIP) containing one native library per ABI."""
    factory = elf_factory or (lambda abi: build_elf())
    with zipfile.ZipFile(path, "w", zipfile.ZIP_STORED) as archive:
        archive.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00binary manifest")
        archive.writestr("classes.dex", b"dex\n035\x00")
        archive.writestr("res/layout/main.xml", b"<layout/>")
        for abi in abis:
            archive.writestr(f"lib/{abi}/libwpm.so", factory(abi))
        for name, data in (extra_entries or {}).items():
            archive.writestr(name, data)
        if comment is not None:
            archive.comment = comment
    return Path(path)


def make_sdk(root, *, windows=None):
    """Create a fake SDK tree containing the Build Tools executables."""
    if windows is None:
        windows = os.name == "nt"
    directory = Path(root) / "build-tools" / verifier.BUILD_TOOLS
    directory.mkdir(parents=True, exist_ok=True)
    for filename in verifier.tool_filenames(windows).values():
        (directory / filename).write_text("", encoding="utf-8")
    return Path(root)


class FakeSdkRunner:
    """Stands in for ``verify_android_apk.run_command``."""

    def __init__(
        self,
        *,
        badging=BADGING,
        zipalign=(0, "", ""),
        apksigner=(0, APKSIGNER_OK, ""),
        aapt=(0, None, ""),
    ):
        self.commands = []
        self.badging = badging
        self.aapt = aapt
        self.zipalign = zipalign
        self.apksigner = apksigner

    @staticmethod
    def tokens_of(command):
        """Split a list command or a batch command-line string into bare tokens."""
        if isinstance(command, str):
            return [token.strip('"') for token in command.split()]
        return [str(token).strip('"') for token in command]

    @classmethod
    def tool_of(cls, command):
        for token in cls.tokens_of(command):
            name = Path(token).name.lower()
            for tool in ("aapt", "zipalign", "apksigner"):
                if name == tool or name.startswith(f"{tool}."):
                    return tool
        raise AssertionError(f"unexpected command: {command}")

    def __call__(self, command, *, timeout=None):
        self.commands.append(self.tokens_of(command))
        tool = self.tool_of(command)
        if tool == "aapt":
            code, stdout, stderr = self.aapt
            return code, self.badging if stdout is None else stdout, stderr
        if tool == "zipalign":
            return self.zipalign
        return self.apksigner

    def args_for(self, tool):
        for command in self.commands:
            if self.tool_of(command) == tool:
                return command
        raise AssertionError(f"{tool} was not executed")


# --------------------------------------------------------------------------- #
# ELF parser
# --------------------------------------------------------------------------- #


class ElfParserTests(unittest.TestCase):
    def test_elf64_little_endian(self):
        elf = verifier.parse_elf(build_elf())
        self.assertEqual(elf["bits"], 64)
        self.assertEqual(elf["endian"], "little")
        segments = verifier.load_segments(elf)
        self.assertEqual(len(segments), 1)
        self.assertEqual(segments[0]["align"], 16384)
        self.assertEqual(verifier.check_elf_load_alignment(elf), [])

    def test_elf32_big_endian(self):
        data = build_elf(bits=32, endian=">")
        elf = verifier.parse_elf(data)
        self.assertEqual(elf["bits"], 32)
        self.assertEqual(elf["endian"], "big")
        self.assertEqual(verifier.elf_alignment_problems(data), [])

    def test_elf64_big_endian_and_elf32_little_endian(self):
        for bits, endian in ((64, ">"), (32, "<")):
            data = build_elf(bits=bits, endian=endian)
            elf = verifier.parse_elf(data)
            self.assertEqual(elf["bits"], bits)
            self.assertEqual(elf["endian"], "little" if endian == "<" else "big")
            self.assertEqual(verifier.elf_alignment_problems(data), [])

    def test_accepts_larger_page_alignment(self):
        data = build_elf(align=65536)
        self.assertEqual(verifier.elf_alignment_problems(data), [])

    def test_rejects_4k_alignment(self):
        data = build_elf(align=4096)
        problems = verifier.elf_alignment_problems(data)
        self.assertEqual(len(problems), 1)
        self.assertIn("p_align 4096", problems[0])

    def test_rejects_non_power_of_two_alignment(self):
        problems = verifier.elf_alignment_problems(build_elf(align=20000))
        self.assertEqual(len(problems), 1)
        self.assertIn("power of two", problems[0])

    def test_rejects_zero_alignment(self):
        problems = verifier.elf_alignment_problems(build_elf(align=0))
        self.assertTrue(any("p_align 0" in problem for problem in problems))

    def test_rejects_vaddr_offset_delta(self):
        problems = verifier.elf_alignment_problems(build_elf(vaddr_delta=4096))
        self.assertEqual(len(problems), 1)
        self.assertIn("p_vaddr-p_offset", problems[0])

    def test_accepts_large_vaddr_offset_multiple(self):
        problems = verifier.elf_alignment_problems(
            build_elf(segment_offset=0x1000, vaddr_delta=0x30000000)
        )
        self.assertEqual(problems, [])

    def test_rejects_missing_load_segment(self):
        problems = verifier.elf_alignment_problems(build_elf(ph_type=4))  # PT_NOTE
        self.assertEqual(problems, ["no PT_LOAD program header"])

    def test_rejects_no_program_headers(self):
        problems = verifier.elf_alignment_problems(build_elf(phnum=0))
        self.assertEqual(problems, ["no PT_LOAD program header"])

    def test_rejects_bad_magic(self):
        with self.assertRaises(verifier.ElfError) as context:
            verifier.parse_elf(build_elf(magic=b"\x7fELG"))
        self.assertIn("ELF magic", str(context.exception))

    def test_rejects_bad_class_and_data_encoding(self):
        with self.assertRaises(verifier.ElfError):
            verifier.parse_elf(build_elf(elf_class=3))
        with self.assertRaises(verifier.ElfError):
            verifier.parse_elf(build_elf(elf_data=3))

    def test_rejects_truncated_identification_and_header(self):
        with self.assertRaises(verifier.ElfError):
            verifier.parse_elf(b"\x7fELF")
        with self.assertRaises(verifier.ElfError):
            verifier.parse_elf(build_elf()[:40])

    def test_rejects_program_header_table_beyond_file(self):
        data = build_elf(phnum=2)
        data = data[:64 + 56]  # second header entry is now out of bounds
        with self.assertRaises(verifier.ElfError) as context:
            verifier.parse_elf(data)
        self.assertIn("program header table", str(context.exception))

    def test_rejects_huge_phnum(self):
        data = patch_elf(build_elf(), 56, "H", 60000)
        with self.assertRaises(verifier.ElfError):
            verifier.parse_elf(data)

    def test_rejects_zero_phoff_with_headers(self):
        data = patch_elf(build_elf(), 32, "Q", 0)
        with self.assertRaises(verifier.ElfError) as context:
            verifier.parse_elf(data)
        self.assertIn("offset is zero", str(context.exception))

    def test_rejects_undersized_program_header_entry(self):
        data = patch_elf(build_elf(), 54, "H", 32)
        with self.assertRaises(verifier.ElfError) as context:
            verifier.parse_elf(data)
        self.assertIn("entry size", str(context.exception))

    def test_rejects_load_segment_past_end_of_file(self):
        data = build_elf(filesz=8)
        data = data[:0x1000]  # drop the segment payload
        with self.assertRaises(verifier.ElfError) as context:
            verifier.parse_elf(data)
        self.assertIn("exceeds file size", str(context.exception))

    def test_rejects_segment_offset_past_end_of_file(self):
        data = patch_elf(build_elf(filesz=0), 0x40 + 8, "Q", 0x900000)
        with self.assertRaises(verifier.ElfError):
            verifier.parse_elf(data)

    def test_rejects_garbage(self):
        with self.assertRaises(verifier.ElfError):
            verifier.parse_elf(b"not an ELF file at all" * 4)


# --------------------------------------------------------------------------- #
# APK native-library inspection
# --------------------------------------------------------------------------- #


class NativeLibraryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def test_reports_all_four_abis_in_order(self):
        apk = build_apk(self.directory / "app.apk")
        result = verifier.inspect_native_libraries(apk)
        self.assertEqual(result["abis"], list(ABIS))
        self.assertEqual(len(result["libraries"]), 4)
        self.assertEqual(
            sorted({library["abi"] for library in result["libraries"]}), sorted(ABIS)
        )
        self.assertTrue(all(library["bits"] == 64 for library in result["libraries"]))

    def test_accepts_mixed_elf_classes(self):
        def factory(abi):
            return build_elf(bits=32) if abi in ("armeabi-v7a", "x86") else build_elf()

        apk = build_apk(self.directory / "app.apk", elf_factory=factory)
        result = verifier.inspect_native_libraries(apk)
        bits = {library["abi"]: library["bits"] for library in result["libraries"]}
        self.assertEqual(bits["armeabi-v7a"], 32)
        self.assertEqual(bits["arm64-v8a"], 64)

    def test_rejects_missing_abi(self):
        apk = build_apk(self.directory / "app.apk", abis=ABIS[:3])
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.inspect_native_libraries(apk)
        self.assertIn("missing native ABI", str(context.exception))
        self.assertIn("x86_64", str(context.exception))

    def test_rejects_unknown_abi(self):
        apk = build_apk(self.directory / "app.apk", abis=ABIS + ("riscv64",))
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.inspect_native_libraries(apk)
        self.assertIn("unexpected native ABI", str(context.exception))

    def test_rejects_apk_without_native_libraries(self):
        apk = build_apk(self.directory / "app.apk", abis=())
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.inspect_native_libraries(apk)
        self.assertIn("no lib/", str(context.exception))

    def test_rejects_malformed_native_library(self):
        apk = build_apk(
            self.directory / "app.apk",
            elf_factory=lambda abi: (
                b"PK\x03\x04 not an ELF file at all" if abi == "x86" else build_elf()
            ),
        )
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.inspect_native_libraries(apk)
        message = str(context.exception)
        self.assertIn("lib/x86/libwpm.so", message)
        self.assertIn("ELF magic", message)

    def test_rejects_misaligned_native_library(self):
        apk = build_apk(
            self.directory / "app.apk",
            elf_factory=lambda abi: build_elf(align=4096) if abi == "x86_64" else build_elf(),
        )
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.inspect_native_libraries(apk)
        self.assertIn("lib/x86_64/libwpm.so", str(context.exception))
        self.assertIn("p_align 4096", str(context.exception))

    def test_rejects_truncated_native_library_entry(self):
        apk = build_apk(
            self.directory / "app.apk",
            elf_factory=lambda abi: build_elf()[:20],
        )
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.inspect_native_libraries(apk)
        self.assertIn("truncated", str(context.exception))

    def test_ignores_non_library_entries(self):
        apk = build_apk(
            self.directory / "app.apk",
            extra_entries={"lib/arm64-v8a/README.txt": b"not a library"},
        )
        result = verifier.inspect_native_libraries(apk)
        self.assertEqual(result["abis"], list(ABIS))

    def test_signature_markers(self):
        apk = build_apk(self.directory / "clean.apk")
        self.assertEqual(verifier.signature_markers(apk), [])
        signed = build_apk(
            self.directory / "signed.apk",
            extra_entries={
                "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\n",
                "META-INF/CERT.RSA": b"fake pkcs7",
            },
        )
        markers = verifier.signature_markers(signed)
        self.assertIn("META-INF/CERT.RSA", markers)
        self.assertIn("META-INF/MANIFEST.MF", markers)

    def test_signature_markers_detect_apk_signing_block(self):
        apk = build_apk(
            self.directory / "block.apk", comment=b"APK Sig Block 42 synthetic"
        )
        self.assertIn("APK Signing Block", verifier.signature_markers(apk))

    def test_rejects_unreadable_apk(self):
        broken = self.directory / "broken.apk"
        broken.write_bytes(b"definitely not a zip archive")
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.inspect_native_libraries(broken)
        self.assertIn("not a readable APK", str(context.exception))


# --------------------------------------------------------------------------- #
# aapt / apksigner parsing
# --------------------------------------------------------------------------- #


class BadgingTests(unittest.TestCase):
    def test_parses_expected_fields(self):
        badging = verifier.parse_badging(BADGING)
        self.assertEqual(badging["package"], APPLICATION_ID)
        self.assertEqual(badging["version_name"], VERSION)
        self.assertEqual(badging["version_code"], str(VERSION_CODE))
        self.assertEqual(badging["min_sdk"], "26")
        self.assertEqual(badging["target_sdk"], "36")
        self.assertFalse(badging["debuggable"])

    def test_check_accepts_expected_identity(self):
        verifier.check_badging(
            verifier.parse_badging(BADGING),
            version=VERSION,
            version_code=VERSION_CODE,
            application_id=APPLICATION_ID,
        )

    def test_rejects_debuggable(self):
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.check_badging(
                verifier.parse_badging(DEBUGGABLE_BADGING),
                version=VERSION,
                version_code=VERSION_CODE,
                application_id=APPLICATION_ID,
            )
        self.assertIn("application-debuggable", str(context.exception))

    def test_rejects_identity_mismatches(self):
        badging = verifier.parse_badging(BADGING)
        cases = (
            ({"version": "0.7.0"}, "versionName"),
            ({"version_code": 12}, "versionCode"),
            ({"application_id": "io.github.other.app"}, "application id"),
        )
        base = {
            "version": VERSION,
            "version_code": VERSION_CODE,
            "application_id": APPLICATION_ID,
        }
        for override, expected in cases:
            with self.subTest(override=override):
                with self.assertRaises(verifier.VerificationError) as context:
                    verifier.check_badging(
                        badging, **{**base, **override}
                    )
                self.assertIn(expected, str(context.exception))

    def test_rejects_wrong_sdk_levels(self):
        for prefix, value, expected in (
            ("sdkVersion:", "21", f"minSdkVersion '21' != {verifier.MIN_SDK}"),
            ("targetSdkVersion:", "35", f"targetSdkVersion '35' != {verifier.TARGET_SDK}"),
        ):
            text = BADGING.replace(
                [line for line in BADGING.splitlines() if line.startswith(prefix)][0],
                f"{prefix}'{value}'",
            )
            with self.subTest(prefix=prefix):
                with self.assertRaises(verifier.VerificationError) as context:
                    verifier.check_badging(
                        verifier.parse_badging(text),
                        version=VERSION,
                        version_code=VERSION_CODE,
                        application_id=APPLICATION_ID,
                    )
                self.assertIn(expected, str(context.exception))

    def test_rejects_missing_package_line(self):
        with self.assertRaises(verifier.VerificationError):
            verifier.parse_badging("sdkVersion:'26'\n")


class ApksignerTests(unittest.TestCase):
    def test_accepts_single_matching_signer(self):
        digest = verifier.check_apksigner_output(APKSIGNER_OK, CERTIFICATE)
        self.assertEqual(digest, CERTIFICATE)

    def test_accepts_colon_separated_expected_pin(self):
        colon = ":".join(CERTIFICATE_LOWER[index : index + 2] for index in range(0, 64, 2))
        self.assertEqual(
            verifier.check_apksigner_output(APKSIGNER_OK, colon), CERTIFICATE
        )

    def test_accepts_signer_with_sdk_range_annotation(self):
        text = APKSIGNER_OK.replace(
            "Signer #1 certificate SHA-256",
            "Signer #1 (minSdkVersion: 24, maxSdkVersion: 33) certificate SHA-256",
        )
        self.assertEqual(verifier.check_apksigner_output(text, CERTIFICATE), CERTIFICATE)

    def test_rejects_multiple_signers(self):
        extra = APKSIGNER_OK + (
            "Signer #2 certificate SHA-256 digest: " + "a" * 64 + "\n"
        )
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.check_apksigner_output(extra, CERTIFICATE)
        self.assertIn("exactly one signer", str(context.exception))

    def test_rejects_certificate_mismatch(self):
        text = APKSIGNER_OK.replace(CERTIFICATE_LOWER, "b" * 64)
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.check_apksigner_output(text, CERTIFICATE)
        self.assertIn("!= expected", str(context.exception))

    def test_rejects_missing_certificate(self):
        text = "Verifies\nNumber of signers: 1\n"
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.check_apksigner_output(text, CERTIFICATE)
        self.assertIn("no certificate SHA-256 digest", str(context.exception))

    def test_rejects_explicit_non_verification(self):
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.check_apksigner_output(
                APKSIGNER_OK + "DOES NOT VERIFY\n", CERTIFICATE
            )
        self.assertIn("does not verify", str(context.exception))

    def test_rejects_inconsistent_signer_numbering(self):
        text = APKSIGNER_OK.replace("Signer #1", "Signer #3")
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.check_apksigner_output(text, CERTIFICATE)
        self.assertIn("numbering", str(context.exception))

    def test_rejects_non_hex_pin(self):
        with self.assertRaises(verifier.VerificationError):
            verifier.normalize_sha256("not-a-digest")


# --------------------------------------------------------------------------- #
# SDK discovery
# --------------------------------------------------------------------------- #


class SdkTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)

    def test_tool_filenames(self):
        windows = verifier.tool_filenames(True)
        self.assertEqual(windows["aapt"], "aapt.exe")
        self.assertEqual(windows["zipalign"], "zipalign.exe")
        self.assertEqual(windows["apksigner"], "apksigner.bat")
        linux = verifier.tool_filenames(False)
        self.assertEqual(
            linux, {"aapt": "aapt", "zipalign": "zipalign", "apksigner": "apksigner"}
        )

    def test_resolve_tools_windows_and_linux(self):
        for windows in (True, False):
            with self.subTest(windows=windows):
                root = make_sdk(self.directory / f"sdk-{windows}", windows=windows)
                tools = verifier.resolve_tools(root, windows=windows)
                self.assertTrue(tools["apksigner"].is_file())
                self.assertEqual(tools["apksigner"].parent.name, verifier.BUILD_TOOLS)

    def test_resolve_tools_reports_missing_build_tools(self):
        root = self.directory / "empty-sdk"
        root.mkdir()
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.resolve_tools(root, windows=False)
        self.assertIn("Build Tools", str(context.exception))

    def test_resolve_tools_reports_missing_executable(self):
        root = make_sdk(self.directory / "sdk", windows=False)
        (root / "build-tools" / verifier.BUILD_TOOLS / "zipalign").unlink()
        with self.assertRaises(verifier.VerificationError) as context:
            verifier.resolve_tools(root, windows=False)
        self.assertIn("missing Build Tools executable", str(context.exception))

    def test_resolve_sdk_from_argument_and_environment(self):
        root = make_sdk(self.directory / "sdk", windows=False)
        self.assertEqual(verifier.resolve_sdk(str(root)), root)
        with mock.patch.dict(
            os.environ, {"ANDROID_HOME": str(root)}, clear=False
        ):
            self.assertEqual(verifier.resolve_sdk(None), root)

    def test_resolve_sdk_requires_value(self):
        with mock.patch.dict(
            os.environ, {"ANDROID_HOME": "", "ANDROID_SDK_ROOT": ""}, clear=False
        ):
            with self.assertRaises(verifier.VerificationError) as context:
                verifier.resolve_sdk(None)
        self.assertIn("--sdk", str(context.exception))

    def test_bat_tool_uses_command_processor(self):
        command = verifier._tool_command(
            Path("C:/sdk dir/apksigner.bat"),
            ["verify", "--print-certs", "D:/a b/app.apk"],
        )
        self.assertIsInstance(command, str)
        comspec = os.environ.get("COMSPEC") or "cmd.exe"
        self.assertTrue(command.startswith(comspec), command)
        self.assertIn(' /d /c "', command)
        # Exactly one wrapping quote pair around the space-bearing inner line.
        self.assertIn('""C:\\sdk dir\\apksigner.bat"', command)
        self.assertIn('"D:/a b/app.apk"', command)
        self.assertTrue(command.endswith('"'), command)
        self.assertEqual(command.count('"'), 6, command)
        plain = verifier._tool_command(Path("/sdk/aapt"), ["dump"])
        self.assertEqual(len(plain), 2)
        self.assertEqual(Path(plain[0]).name, "aapt")
        self.assertEqual(plain[1], "dump")


# --------------------------------------------------------------------------- #
# CLI end to end with a mocked SDK
# --------------------------------------------------------------------------- #


class CliTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.sdk = make_sdk(self.directory / "sdk")
        self.apk = build_apk(self.directory / "app-release.apk")
        self.output = self.directory / "report" / "android-apk.json"

    def run_main(self, *extra, apk=None, sdk=None, output=None, certificate=None):
        argv = [
            "--apk",
            str(apk or self.apk),
            "--version",
            VERSION,
            "--version-code",
            str(VERSION_CODE),
            "--application-id",
            APPLICATION_ID,
            "--output",
            str(output or self.output),
            "--sdk",
            str(sdk or self.sdk),
        ]
        if certificate is not False:
            argv += ["--certificate-sha256", certificate or CERTIFICATE]
        argv += list(extra)
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr):
            code = verifier.main(argv)
        return code, stdout.getvalue(), stderr.getvalue()

    def test_signed_success_writes_expected_report(self):
        runner = FakeSdkRunner()
        with mock.patch.object(verifier, "run_command", runner):
            code, stdout, stderr = self.run_main()
        self.assertEqual(code, 0, stderr)
        self.assertIn("verified app-release.apk", stdout)
        report = json.loads(self.output.read_text(encoding="utf-8"))
        self.assertEqual(
            list(report),
            [
                "version",
                "version_code",
                "application_id",
                "certificate_sha256",
                "apk_sha256",
                "abis",
                "checks",
            ],
        )
        self.assertEqual(report["version"], VERSION)
        self.assertEqual(report["version_code"], VERSION_CODE)
        self.assertEqual(report["application_id"], APPLICATION_ID)
        self.assertEqual(report["certificate_sha256"], CERTIFICATE)
        self.assertEqual(report["abis"], list(ABIS))
        self.assertEqual(list(report["checks"]), list(verifier.CHECKS))
        self.assertTrue(all(report["checks"].values()))
        self.assertEqual(
            report["apk_sha256"], verifier.file_sha256(self.apk)
        )
        self.assertEqual(len(report["apk_sha256"]), 64)
        self.assertEqual(report["apk_sha256"], report["apk_sha256"].lower())

    def test_signed_success_runs_expected_tool_arguments(self):
        runner = FakeSdkRunner()
        with mock.patch.object(verifier, "run_command", runner):
            code, _, stderr = self.run_main()
        self.assertEqual(code, 0, stderr)
        self.assertEqual(
            runner.args_for("apksigner")[-3:],
            ["--verbose", "--print-certs", str(self.apk)],
        )
        self.assertEqual(runner.args_for("apksigner")[-4], "verify")
        self.assertEqual(
            runner.args_for("zipalign")[-6:],
            ["-c", "-P", "16", "-v", "4", str(self.apk)],
        )
        self.assertEqual(
            runner.args_for("aapt")[-3:], ["dump", "badging", str(self.apk)]
        )

    def test_unsigned_success_reports_no_signature(self):
        runner = FakeSdkRunner()
        with mock.patch.object(verifier, "run_command", runner):
            code, stdout, stderr = self.run_main("--unsigned", certificate=False)
        self.assertEqual(code, 0, stderr)
        self.assertIn("apk_signature=false", stdout)
        report = json.loads(self.output.read_text(encoding="utf-8"))
        self.assertIsNone(report["certificate_sha256"])
        self.assertFalse(report["checks"]["apk_signature"])
        self.assertTrue(report["checks"]["apk_manifest"])
        self.assertTrue(report["checks"]["zip_alignment_16k"])
        self.assertTrue(report["checks"]["native_elf_alignment_16k"])
        self.assertNotIn("apksigner", [runner.tool_of(c) for c in runner.commands])

    def test_unsigned_rejects_signed_apk(self):
        signed = build_apk(
            self.directory / "signed.apk",
            extra_entries={"META-INF/CERT.RSA": b"fake pkcs7 signature"},
        )
        runner = FakeSdkRunner()
        with mock.patch.object(verifier, "run_command", runner):
            code, _, stderr = self.run_main("--unsigned", apk=signed, certificate=False)
        self.assertEqual(code, 1)
        self.assertIn("unsigned mode", stderr)
        self.assertIn("META-INF/CERT.RSA", stderr)
        self.assertFalse(self.output.exists())

    def test_unsigned_rejects_signing_block(self):
        block = build_apk(
            self.directory / "block.apk", comment=b"APK Sig Block 42 synthetic"
        )
        with mock.patch.object(verifier, "run_command", FakeSdkRunner()):
            code, _, stderr = self.run_main("--unsigned", apk=block, certificate=False)
        self.assertEqual(code, 1)
        self.assertIn("APK Signing Block", stderr)

    def test_unsigned_rejects_certificate_argument(self):
        with mock.patch.object(verifier, "run_command", FakeSdkRunner()):
            code, _, stderr = self.run_main("--unsigned")
        self.assertEqual(code, 1)
        self.assertIn("--unsigned does not accept", stderr)

    def test_signed_requires_certificate_argument(self):
        with mock.patch.object(verifier, "run_command", FakeSdkRunner()):
            code, _, stderr = self.run_main(certificate=False)
        self.assertEqual(code, 1)
        self.assertIn("requires --certificate-sha256", stderr)

    def test_rejects_certificate_mismatch(self):
        runner = FakeSdkRunner(
            apksigner=(0, APKSIGNER_OK.replace(CERTIFICATE_LOWER, "c" * 64), "")
        )
        with mock.patch.object(verifier, "run_command", runner):
            code, _, stderr = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("!= expected", stderr)
        self.assertFalse(self.output.exists())

    def test_rejects_debuggable_apk(self):
        with mock.patch.object(
            verifier, "run_command", FakeSdkRunner(badging=DEBUGGABLE_BADGING)
        ):
            code, _, stderr = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("application-debuggable", stderr)

    def test_rejects_aapt_failure(self):
        runner = FakeSdkRunner(aapt=(1, "", "ERROR: dump failed"))
        with mock.patch.object(verifier, "run_command", runner):
            code, _, stderr = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("aapt dump badging failed", stderr)

    def test_rejects_zipalign_failure(self):
        runner = FakeSdkRunner(zipalign=(1, "", "lib/x86/libwpm.so NOT aligned"))
        with mock.patch.object(verifier, "run_command", runner):
            code, _, stderr = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("zipalign", stderr)
        self.assertIn("NOT aligned", stderr)

    def test_rejects_apksigner_failure(self):
        runner = FakeSdkRunner(apksigner=(1, "", "DOES NOT VERIFY"))
        with mock.patch.object(verifier, "run_command", runner):
            code, _, stderr = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("apksigner verify failed", stderr)

    def test_rejects_misaligned_native_library(self):
        apk = build_apk(
            self.directory / "misaligned.apk",
            elf_factory=lambda abi: build_elf(align=4096) if abi == "x86" else build_elf(),
        )
        with mock.patch.object(verifier, "run_command", FakeSdkRunner()):
            code, _, stderr = self.run_main(apk=apk)
        self.assertEqual(code, 1)
        self.assertIn("native ELF checks failed", stderr)
        self.assertIn("p_align 4096", stderr)

    def test_rejects_missing_apk(self):
        with mock.patch.object(verifier, "run_command", FakeSdkRunner()):
            code, _, stderr = self.run_main(apk=self.directory / "absent.apk")
        self.assertEqual(code, 1)
        self.assertIn("APK not found", stderr)

    def test_rejects_missing_sdk(self):
        with mock.patch.object(verifier, "run_command", FakeSdkRunner()):
            code, _, stderr = self.run_main(sdk=self.directory / "no-sdk")
        self.assertEqual(code, 1)
        self.assertIn("SDK directory not found", stderr)

    def test_rejects_invalid_expected_values(self):
        cases = (
            (["--version", "0.7"], "invalid version"),
            (["--version-code", "0"], "invalid version code"),
            (["--application-id", "nodots"], "invalid application id"),
        )
        for override, expected in cases:
            with self.subTest(override=override):
                argv = [
                    "--apk",
                    str(self.apk),
                    "--version",
                    VERSION,
                    "--version-code",
                    str(VERSION_CODE),
                    "--application-id",
                    APPLICATION_ID,
                    "--output",
                    str(self.output),
                    "--sdk",
                    str(self.sdk),
                    "--certificate-sha256",
                    CERTIFICATE,
                ]
                for index, value in zip(range(0, len(override), 2), override[1::2]):
                    argv[argv.index(override[index]) + 1] = value
                with mock.patch.object(verifier, "run_command", FakeSdkRunner()):
                    with redirect_stdout(io.StringIO()), redirect_stderr(
                        io.StringIO()
                    ) as stderr:
                        code = verifier.main(argv)
                self.assertEqual(code, 1)
                self.assertIn(expected, stderr.getvalue())

    def test_report_shape_validation(self):
        report = {
            "version": VERSION,
            "version_code": VERSION_CODE,
            "application_id": APPLICATION_ID,
            "certificate_sha256": None,
            "apk_sha256": "a" * 64,
            "abis": list(ABIS),
            "checks": {name: False for name in verifier.CHECKS},
        }
        verifier.check_report_shape(dict(report))
        for mutation in (
            {**report, "extra": 1},
            {**report, "checks": {"apk_signature": False}},
            {**report, "checks": {**report["checks"], "apk_signature": "yes"}},
        ):
            with self.subTest(mutation=mutation):
                with self.assertRaises(verifier.VerificationError):
                    verifier.check_report_shape(mutation)

    def test_file_sha256_matches_hashlib(self):
        import hashlib

        expected = hashlib.sha256(self.apk.read_bytes()).hexdigest()
        self.assertEqual(verifier.file_sha256(self.apk), expected)


if __name__ == "__main__":
    unittest.main()
