#!/usr/bin/env python3
"""Record secret-free Android build evidence, then sign in a protected CI job.

Neither command publishes. record-build requires successful release tests,
lint and unsigned APK checks. sign binds that APK's hash to the run SHA, uses
environment-only credentials, and verifies the signed APK before writing its
SHA-256 sidecar. Production keys never enter Gradle, caches or public assets.
"""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

PIN = "D3D54F63925EAD1F229259CA6C711A403DDCE9D06B7B7C76CDFF699C516EF6CF"
TOOLCHAIN = {"java": "21", "gradle": "8.13", "compile_sdk": 36, "build_tools": "35.0.0"}
BUILD_CHECKS = ("release_unit_tests", "release_lint")
INPUT_FILES = {"unsigned.apk", "build.json"}


def load(name):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), Path(__file__).with_name(name + ".py"))
    if spec is None or spec.loader is None:
        raise ImportError(f"Cannot load Android release helper: {name}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def digest(path):
    with Path(path).open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def require_commit(commit):
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("A full lowercase release commit SHA is required")


def identity(source):
    text = (Path(source) / "android/app/build.gradle.kts").read_text(encoding="utf-8")
    def single(pattern):
        values = re.findall(pattern, text)
        if len(values) != 1:
            raise ValueError("Ambiguous Gradle release identity")
        return values[0]
    version = single(r'(?<![\w.])versionName\s*=\s*"([^"]+)"')
    code = int(single(r"(?<![\w.])versionCode\s*=\s*([0-9]+)"))
    app = single(r'(?<![\w.])applicationId\s*=\s*"([^"]+)"')
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", version) or version.endswith("-dev"):
        raise ValueError("A release versionName, not a development version, is required")
    if not 0 < code <= 2_100_000_000 or app != "io.github.anevilburrito.wpm":
        raise ValueError("Unexpected release application ID or versionCode")
    return {"version": version, "version_code": code, "application_id": app}


def release_reports(source):
    """Read real Gradle reports; missing/empty/failing reports are not success."""
    build = Path(source) / "android/app/build"
    reports = sorted((build / "test-results/testReleaseUnitTest").glob("TEST-*.xml"))
    if not reports:
        raise ValueError("Release JVM test reports are missing")
    total = 0
    for path in reports:
        suite = ET.parse(path).getroot()
        total += int(suite.get("tests", "0"))
        if any(int(suite.get(k, "0")) for k in ("failures", "errors", "skipped")):
            raise ValueError("Release JVM tests have failures, errors or skips")
    if total <= 0:
        raise ValueError("No release JVM tests executed")
    lint = ET.parse(build / "reports/lint-results-release.xml").getroot()
    if any(issue.get("severity", "").lower() in ("fatal", "error") for issue in lint):
        raise ValueError("Release lint has errors")
    return {"tests": total, "lint_warnings": sum(i.get("severity") == "Warning" for i in lint)}


def record_build(commit, input_dir):
    require_commit(commit)
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if head != commit:
        raise ValueError("Build checkout SHA differs from approved run SHA")
    subprocess.run(["git", "diff", "--exit-code", "HEAD", "--", "android", "scripts"], check=True)
    java_home = os.environ.get("JAVA_HOME")
    java_exe = str(Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")) if java_home else "java"
    java = subprocess.run([java_exe, "-version"], capture_output=True, text=True, check=True)
    if not re.search(r'version "21[.\-+\"]', java.stdout + java.stderr):
        raise ValueError("Release evidence requires JDK 21")
    data = identity(Path.cwd())
    ref = os.environ.get("GITHUB_REF", "")
    if ref.startswith("refs/tags/") and ref != "refs/tags/android-v" + data["version"]:
        raise ValueError("Dispatch tag does not match the Android version")
    reports = release_reports(Path.cwd())
    original = Path("android/app/build/outputs/apk/release/app-release-unsigned.apk")
    load("verify-android-apk").verify(original, **data, unsigned=True)
    dest = Path(input_dir)
    dest.mkdir(parents=True, exist_ok=False)
    shutil.copyfile(original, dest / "unsigned.apk")
    record = {
        "schema": 1, **data, "commit": commit, "toolchain": TOOLCHAIN,
        "unsigned_apk_sha256": digest(dest / "unsigned.apk"),
        "checks": {key: True for key in BUILD_CHECKS}, "reports": reports,
    }
    write_json(dest / "build.json", record)
    print(f"Recorded verified release APK: {reports}")
    return record


def validate_input(input_dir, commit):
    require_commit(commit)
    root = Path(input_dir)
    if {p.name for p in root.iterdir()} != INPUT_FILES:
        raise ValueError("Signing input inventory mismatch")
    if any(p.is_symlink() or not p.is_file() for p in root.iterdir()):
        raise ValueError("Signing inputs must be regular files")
    data = json.loads((root / "build.json").read_text(encoding="utf-8"))
    if type(data.get("schema")) is not int or data["schema"] != 1 or data.get("commit") != commit or data.get("toolchain") != TOOLCHAIN:
        raise ValueError("Signing input commit/schema/toolchain mismatch")
    checks = data.get("checks")
    if not isinstance(checks, dict) or set(checks) != set(BUILD_CHECKS) or any(checks[k] is not True for k in BUILD_CHECKS):
        raise ValueError("Signing requires all release build checks")
    if data.get("application_id") != "io.github.anevilburrito.wpm" or type(data.get("version_code")) is not int or not 0 < data["version_code"] <= 2_100_000_000:
        raise ValueError("Signing input application ID/versionCode mismatch")
    if not isinstance(data.get("version"), str) or not re.fullmatch(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?", data["version"]) or data["version"].endswith("-dev"):
        raise ValueError("Signing input is not a release version")
    if digest(root / "unsigned.apk") != data.get("unsigned_apk_sha256"):
        raise ValueError("Signing input hash mismatch: unsigned.apk")
    if any(data[key] != value for key, value in identity(Path.cwd()).items()):
        raise ValueError("Signing input identity differs from release checkout")
    return data


def decode_key(value):
    if not isinstance(value, str) or not value.strip() or len(value) > 100_000:
        raise ValueError("Release keystore secret is missing or oversized")
    try:
        result = base64.b64decode("".join(value.split()), validate=True)
    except ValueError as error:
        raise ValueError("Release keystore secret is not valid base64") from error
    if not result or len(result) > 64_000:
        raise ValueError("Release keystore is empty or oversized")
    return result


def sign(commit, input_dir, output):
    """Signing secrets are passed only to this protected, non-Gradle process."""
    data = validate_input(input_dir, commit)
    required = ("ANDROID_KEYSTORE_BASE64", "ANDROID_KEYSTORE_PASSWORD", "ANDROID_KEY_ALIAS", "ANDROID_KEY_PASSWORD")
    if any(not os.environ.get(k) for k in required):
        raise ValueError("A required Android signing secret is missing")
    configured = re.sub(r"[\s:]", "", os.environ.get("ANDROID_SIGNING_CERT_SHA256", "")).upper()
    if configured != PIN:
        raise ValueError("Environment certificate pin differs from approved release identity")
    if os.environ["ANDROID_KEY_ALIAS"] != "wpm-release":
        raise ValueError("Unexpected release key alias")
    temporary_root = os.environ.get("RUNNER_TEMP")
    if not temporary_root:
        raise ValueError("Protected CI RUNNER_TEMP is required")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    root = Path(input_dir)
    apk = load("verify-android-apk")
    tools = apk.resolve_tools(apk.resolve_sdk())
    signed = output / f'wpm-android-v{data["version"]}.apk'
    with tempfile.TemporaryDirectory(prefix="wpm-android-sign-", dir=temporary_root) as temp:
        key = Path(temp) / "release.p12"
        with key.open("xb") as f:
            f.write(decode_key(os.environ["ANDROID_KEYSTORE_BASE64"]))
        key.chmod(0o600)
        aligned = Path(temp) / "aligned.apk"
        code, _, _ = apk.run_tool(tools["zipalign"], ["-P", "16", "-f", "4", str(root / "unsigned.apk"), str(aligned)], label="zipalign")
        if code:
            raise ValueError("Pre-signing APK alignment failed")
        arguments = [
            "sign", "--ks", str(key), "--ks-type", "PKCS12",
            "--ks-key-alias", os.environ["ANDROID_KEY_ALIAS"],
            "--ks-pass", "env:ANDROID_KEYSTORE_PASSWORD", "--key-pass", "env:ANDROID_KEY_PASSWORD",
            "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
            "--out", str(signed), str(aligned),
        ]
        code, _, _ = apk.run_tool(tools["apksigner"], arguments, label="apksigner sign")
        if code:
            # Passwords are env references, never literal command arguments.
            raise ValueError("Production APK signing failed; check keystore/password/alias secrets")
    # Private key has been deleted before inspecting any public files.
    inspected = apk.verify(signed, version=data["version"], version_code=data["version_code"],
                           application_id=data["application_id"], certificate_sha256=PIN)
    record = {
        "schema": 1, **{k: data[k] for k in ("version", "version_code", "application_id", "commit")},
        "certificate_sha256": inspected["certificate_sha256"],
        "apk_sha256": inspected["apk_sha256"], "abis": inspected["abis"],
        "toolchain": TOOLCHAIN, "checks": {**data["checks"], **inspected["checks"]},
    }
    checksum = digest(signed)
    if checksum != inspected["apk_sha256"]:
        raise ValueError("Signed APK changed after verification")
    sidecar = signed.with_name(signed.name + ".sha256")
    sidecar.write_text(f"{checksum}  {signed.name}\n", encoding="ascii", newline="\n")
    if {p.name for p in output.iterdir()} != {signed.name, sidecar.name}:
        raise ValueError("Expected only the signed APK and SHA-256 checksum")
    print(json.dumps(record, indent=2))
    print("Verified signed APK and checksum ready; nothing published")
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("record-build")
    build.add_argument("--commit", required=True)
    build.add_argument("--input", type=Path, required=True)
    signing = commands.add_parser("sign")
    signing.add_argument("--commit", required=True)
    signing.add_argument("--input", type=Path, required=True)
    signing.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "record-build":
            record_build(args.commit, args.input)
        else:
            sign(args.commit, args.input, args.output)
    except Exception as error:
        parser.exit(1, f"Android candidate failed: {error}\n")


if __name__ == "__main__":
    main()
