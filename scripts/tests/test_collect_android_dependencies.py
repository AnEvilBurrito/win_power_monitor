"""Synthetic tests for the Android release dependency export collector (no Gradle).

Uses only temporary directories, fabricated POMs and fabricated ZIP/AAR archives.
"""

from contextlib import redirect_stderr, redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile


SPEC = importlib.util.spec_from_file_location(
    "collect_android_dependencies",
    Path(__file__).resolve().parents[1] / "collect-android-dependencies.py",
)
assert SPEC is not None and SPEC.loader is not None
collector = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(collector)

APACHE_TEXT = (
    "                                 Apache License\n"
    "                           Version 2.0, January 2004\n"
    "                        http://www.apache.org/licenses/\n"
    + "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION\n" * 90
)
MIT_TEXT = (
    "MIT License\n\nPermission is hereby granted, free of charge, to any person "
    "obtaining a copy of this software...\n" * 30
)
CANONICAL_LICENSE = {
    "name": "Apache-2.0",
    "url": "https://www.apache.org/licenses/LICENSE-2.0.txt",
}
DEFAULT_POM_LICENSE = (
    "The Apache Software License, Version 2.0",
    "http://www.apache.org/licenses/LICENSE-2.0.txt",
)
# Exact pair recorded in REVIEWED_PARENT_LICENSES: the child POM declares no
# <licenses> and inherits Apache-2.0 from this reviewed parent POM only.
REVIEWED_CHILD = ("com.google.guava", "listenablefuture", "1.0")
REVIEWED_PARENT = ("com.google.guava", "guava-parent", "26.0-android")


def zip_bytes(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return buffer.getvalue()


def pom_xml(group, name, version, licenses=(DEFAULT_POM_LICENSE,), parent=None):
    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<project xmlns="http://maven.apache.org/POM/4.0.0">',
        f"  <groupId>{group}</groupId>",
        f"  <artifactId>{name}</artifactId>",
        f"  <version>{version}</version>",
        "  <packaging>aar</packaging>",
    ]
    if parent is not None:
        parts.append("  <parent>")
        for tag, value in zip(("groupId", "artifactId", "version"), parent):
            parts.append(f"    <{tag}>{value}</{tag}>")
        parts.append("  </parent>")
    if licenses is not None:
        parts.append("  <licenses>")
        for license_name, license_url in licenses:
            parts.append("    <license>")
            parts.append(f"      <name>{license_name}</name>")
            parts.append(f"      <url>{license_url}</url>")
            parts.append("      <distribution>repo</distribution>")
            parts.append("    </license>")
        parts.append("  </licenses>")
    parts.append("</project>")
    return "\n".join(parts).encode("utf-8")


class DependencyExportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repository = self.root / "repo"
        self.repository.mkdir()
        self.license_file = self.root / "licenses" / "Apache-2.0.txt"
        self.license_file.parent.mkdir(parents=True)
        self.license_file.write_text(APACHE_TEXT, encoding="utf-8")
        self.inventory = self.root / "release-dependencies-input.json"
        self.output = self.root / "build" / "release-dependencies"
        self.dependencies = []
        self.native_sources = None

    # -- fixtures ---------------------------------------------------------- #
    def declare(
        self,
        group="androidx.example",
        name="widget",
        version="1.2.3",
        source_entries=None,
        runtime_entries=None,
        pom_bytes=None,
        packaging="aar",
    ):
        coordinate = f"{group}:{name}:{version}"
        stem = f"{group}-{name}-{version}"
        if source_entries is None:
            source_entries = {f"example/{name}/Widget.java": "package example;\n"}
        if runtime_entries is None:
            runtime_entries = {
                "AndroidManifest.xml": "<manifest/>",
                "META-INF/LICENSE": "runtime license text\n",
            }
        if packaging == "aar":
            nested = zip_bytes({"example/Widget.class": b"\xca\xfe\xba\xbe"})
            runtime_entries = dict(runtime_entries)
            runtime_entries.setdefault("classes.jar", nested)
        source_path = self.repository / f"{stem}-sources.jar"
        source_path.write_bytes(zip_bytes(source_entries))
        pom_path = self.repository / f"{stem}.pom"
        pom_path.write_bytes(
            pom_bytes if pom_bytes is not None else pom_xml(group, name, version)
        )
        runtime_path = self.repository / f"{stem}.{packaging}"
        runtime_path.write_bytes(
            zip_bytes(runtime_entries)
            if not isinstance(runtime_entries, bytes)
            else runtime_entries
        )
        self.dependencies.append(
            {
                "coordinate": coordinate,
                "source": str(source_path),
                "pom": str(pom_path),
                "runtime": str(runtime_path),
            }
        )
        return {
            "coordinate": coordinate,
            "stem": stem,
            "source": source_path,
            "pom": pom_path,
            "runtime": runtime_path,
        }

    def write_inventory(self, dependencies=None):
        payload = {
            "schema": 1,
            "license_file": str(self.license_file),
            "dependencies": self.dependencies if dependencies is None else dependencies,
        }
        if self.native_sources is not None:
            payload["native_sources"] = self.native_sources
        self.inventory.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    def export(self):
        self.write_inventory()
        return collector.collect(self.inventory, self.output)

    def manifest(self):
        return json.loads((self.output / "manifest.json").read_text(encoding="utf-8"))

    def exported_state(self):
        state = {}
        for path in sorted(self.output.rglob("*")):
            if path.is_file():
                state[path.relative_to(self.output).as_posix()] = hashlib.sha256(
                    path.read_bytes()
                ).hexdigest()
        return state

    # -- happy path -------------------------------------------------------- #
    def test_exports_sources_poms_licenses_and_notices(self):
        widget = self.declare()
        tool = self.declare(
            group="org.example",
            name="tool",
            version="2.0.0",
            packaging="jar",
            runtime_entries={
                "META-INF/NOTICE": "tool notice\n",
                "org/example/tool/Tool.class": b"\xca\xfe\xba\xbe",
            },
            source_entries={"org/example/tool/Tool.kt": "class Tool\n"},
        )
        manifest = self.export()

        self.assertEqual(set(manifest), {"schema", "dependencies"})
        self.assertEqual(manifest["schema"], 1)
        self.assertEqual(
            [record["coordinate"] for record in manifest["dependencies"]],
            [widget["coordinate"], tool["coordinate"]],
        )
        for record, fixture in zip(manifest["dependencies"], (widget, tool)):
            self.assertEqual(
                set(record),
                {"coordinate", "source", "source_sha256", "pom", "pom_sha256", "licenses"},
            )
            self.assertEqual(record["source"], f"files/{fixture['stem']}-sources.jar")
            self.assertEqual(record["pom"], f"files/{fixture['stem']}.pom")
            self.assertEqual(record["licenses"], [CANONICAL_LICENSE])
            self.assertEqual(
                (self.output / record["source"]).read_bytes(), fixture["source"].read_bytes()
            )
            self.assertEqual(
                (self.output / record["pom"]).read_bytes(), fixture["pom"].read_bytes()
            )
            self.assertEqual(
                record["source_sha256"],
                hashlib.sha256(fixture["source"].read_bytes()).hexdigest(),
            )
            self.assertEqual(
                record["pom_sha256"], hashlib.sha256(fixture["pom"].read_bytes()).hexdigest()
            )
        # Standard Apache-2.0 text is copied verbatim.
        self.assertEqual(
            (self.output / "LICENSE-APACHE-2.0.txt").read_bytes(),
            self.license_file.read_bytes(),
        )
        notices = sorted(
            path.name for path in (self.output / "notices").iterdir() if path.is_file()
        )
        self.assertTrue(any("runtime__META-INF_NOTICE" in name for name in notices), notices)
        self.assertTrue(any(name.endswith("runtime__META-INF_LICENSE") for name in notices))
        self.assertIn(
            "androidx.example-widget-1.2.3__runtime__META-INF_LICENSE",
            notices,
        )

    def test_captures_notices_from_classes_jar_inside_aar(self):
        nested = zip_bytes({"META-INF/NOTICE.txt": "nested aar notice\n"})
        self.declare(runtime_entries={"classes.jar": nested})
        self.export()
        captured = {
            path.name: path.read_text(encoding="utf-8")
            for path in (self.output / "notices").iterdir()
        }
        self.assertEqual(
            captured["androidx.example-widget-1.2.3__runtime-classes__META-INF_NOTICE.txt"],
            "nested aar notice\n",
        )

    def test_captures_license_from_source_jar(self):
        self.declare(
            source_entries={
                "example/Widget.java": "package example;\n",
                "META-INF/LICENSE.md": "source license text\n",
            }
        )
        self.export()
        captured = {
            path.name: path.read_text(encoding="utf-8")
            for path in (self.output / "notices").iterdir()
        }
        self.assertEqual(
            captured["androidx.example-widget-1.2.3__source__META-INF_LICENSE.md"],
            "source license text\n",
        )

    def test_runtime_binaries_are_never_exported(self):
        fixture = self.declare()
        self.export()
        runtime_digest = hashlib.sha256(fixture["runtime"].read_bytes()).hexdigest()
        exported = self.exported_state()
        self.assertNotIn(runtime_digest, exported.values())
        self.assertFalse(any(name.endswith(".aar") for name in exported))
        self.assertTrue(all(name.endswith((".jar", ".pom")) for name in exported if name.startswith("files/")))
        self.assertLessEqual(
            {name.split("/")[0] for name in exported},
            {"files", "notices", "manifest.json", "LICENSE-APACHE-2.0.txt"},
        )

    # -- determinism ------------------------------------------------------- #
    def test_output_is_deterministic_across_runs_and_input_order(self):
        self.declare(group="org.b", name="second", version="1.0.0")
        self.declare(group="org.a", name="first", version="2.0.0")
        self.export()
        first = self.exported_state()
        self.export()
        self.assertEqual(self.exported_state(), first)
        self.dependencies.reverse()
        self.export()
        self.assertEqual(self.exported_state(), first)
        self.assertEqual(
            [record["coordinate"] for record in self.manifest()["dependencies"]],
            ["org.a:first:2.0.0", "org.b:second:1.0.0"],
        )

    def test_stale_output_entries_are_removed(self):
        # No META-INF license here, so a notices directory must not survive.
        self.declare(runtime_entries={"AndroidManifest.xml": "<manifest/>"})
        stale = self.output / "files" / "stale-sources.jar"
        stale.parent.mkdir(parents=True)
        stale.write_bytes(b"stale")
        (self.output / "notices").mkdir()
        self.export()
        self.assertFalse(stale.exists())
        self.assertFalse((self.output / "notices").exists())

    def test_coordinates_cannot_escape_the_export_directory(self):
        self.declare(group="..", name="..", version="..")
        self.export()
        resolved_output = self.output.resolve()
        for path in self.output.rglob("*"):
            path.resolve().relative_to(resolved_output)
        self.assertEqual(len(list((self.output / "files").iterdir())), 2)

    # -- failure modes ----------------------------------------------------- #
    def test_missing_source_jar_fails(self):
        fixture = self.declare()
        fixture["source"].unlink()
        with self.assertRaisesRegex(ValueError, "Missing source JAR"):
            self.export()

    def test_missing_pom_fails(self):
        fixture = self.declare()
        fixture["pom"].unlink()
        with self.assertRaisesRegex(ValueError, "Missing POM"):
            self.export()

    def test_missing_runtime_artifact_fails(self):
        fixture = self.declare()
        fixture["runtime"].unlink()
        with self.assertRaisesRegex(ValueError, "Missing runtime artifact"):
            self.export()

    def test_source_jar_must_be_an_archive(self):
        fixture = self.declare()
        fixture["source"].write_bytes(b"not a zip")
        with self.assertRaisesRegex(ValueError, "not a readable ZIP archive"):
            self.export()

    def test_pom_without_licenses_fails(self):
        self.declare(
            pom_bytes=pom_xml(
                "androidx.example",
                "widget",
                "1.2.3",
                licenses=None,
                parent=("androidx", "parent", "1.0.0"),
            )
        )
        with self.assertRaisesRegex(ValueError, "parent POM androidx:parent:1.0.0"):
            self.export()

    # -- reviewed parent-POM inheritance ----------------------------------- #
    def test_inherited_license_child_pom_without_group_or_version(self):
        # Mirrors the published listenablefuture-1.0.pom shape: <parent> first, no
        # own groupId/version/packaging and no <licenses> element.
        pom = (
            '<?xml version="1.0" encoding="UTF-8"?>\n'
            '<project xmlns="http://maven.apache.org/POM/4.0.0">\n'
            "  <parent>\n"
            "    <groupId>com.google.guava</groupId>\n"
            "    <artifactId>guava-parent</artifactId>\n"
            "    <version>26.0-android</version>\n"
            "  </parent>\n"
            "  <artifactId>listenablefuture</artifactId>\n"
            "  <name>Guava ListenableFuture only</name>\n"
            "</project>\n"
        ).encode("utf-8")
        self.declare(
            group="com.google.guava",
            name="listenablefuture",
            version="1.0",
            packaging="jar",
            runtime_entries={
                "com/google/common/util/concurrent/ListenableFuture.class": b"\xca\xfe\xba\xbe",
            },
            pom_bytes=pom,
        )
        manifest = self.export()
        self.assertEqual(manifest["dependencies"][0]["licenses"], [CANONICAL_LICENSE])

    def declare_reviewed_child(self, child=REVIEWED_CHILD, parent=REVIEWED_PARENT, licenses=None):
        group, name, version = child
        return self.declare(
            group=group,
            name=name,
            version=version,
            packaging="jar",
            runtime_entries={
                "com/google/common/util/concurrent/ListenableFuture.class": b"\xca\xfe\xba\xbe",
            },
            pom_bytes=pom_xml(group, name, version, licenses=licenses, parent=parent),
        )

    def test_exact_reviewed_parent_license_inheritance_is_accepted(self):
        fixture = self.declare_reviewed_child()
        manifest = self.export()
        record = manifest["dependencies"][0]
        self.assertEqual(record["coordinate"], ":".join(REVIEWED_CHILD))
        self.assertEqual(record["licenses"], [CANONICAL_LICENSE])
        # The child POM is still exported byte-identical; the parent is not.
        self.assertEqual(
            (self.output / record["pom"]).read_bytes(), fixture["pom"].read_bytes()
        )
        self.assertEqual(
            sorted(path.name for path in (self.output / "files").iterdir()),
            [f"{fixture['stem']}-sources.jar", f"{fixture['stem']}.pom"],
        )

    def test_reviewed_inheritance_matches_provenance_record(self):
        key = (":".join(REVIEWED_CHILD), ":".join(REVIEWED_PARENT))
        self.assertIn(key, collector.REVIEWED_PARENT_LICENSES)
        record = collector.REVIEWED_PARENT_LICENSES[key]
        self.assertEqual(record["license"], CANONICAL_LICENSE)
        self.assertIn(
            "f8698ab46ca996ce889c1afc8ca4f25eb8ac6b034dc898d4583742360016cc04",
            record["provenance"],
        )
        self.assertIn("guava-parent-26.0-android.pom", record["provenance"])

    def test_reviewed_inheritance_is_narrow(self):
        cases = (
            # wrong child artifact
            (("com.google.guava", "listenablefuture-other", "1.0"), REVIEWED_PARENT),
            # wrong child group
            (("org.example", "listenablefuture", "1.0"), REVIEWED_PARENT),
            # wrong child version
            (("com.google.guava", "listenablefuture", "1.0.1"), REVIEWED_PARENT),
            # wrong parent group
            (REVIEWED_CHILD, ("org.example", "guava-parent", "26.0-android")),
            # wrong parent artifact
            (REVIEWED_CHILD, ("com.google.guava", "guava-parent-jre", "26.0-android")),
            # wrong parent version
            (REVIEWED_CHILD, ("com.google.guava", "guava-parent", "26.0-jre")),
            (REVIEWED_CHILD, ("com.google.guava", "guava-parent", "33.4.8-android")),
        )
        for child, parent in cases:
            with self.subTest(child=child, parent=parent):
                self.dependencies = []
                self.declare_reviewed_child(child=child, parent=parent)
                with self.assertRaisesRegex(ValueError, "declares no <licenses>"):
                    self.export()

    def test_reviewed_child_without_parent_still_fails(self):
        self.declare_reviewed_child(parent=None)
        with self.assertRaisesRegex(ValueError, "no exported parent POM"):
            self.export()

    def test_reviewed_child_with_unknown_declared_license_still_fails(self):
        self.declare_reviewed_child(
            licenses=[("MIT License", "https://opensource.org/licenses/MIT")]
        )
        with self.assertRaisesRegex(ValueError, "Unrecognized license"):
            self.export()

    def test_unrelated_missing_licenses_still_fail(self):
        # Same reviewed parent, but another child POM without <licenses> fails.
        self.declare(
            pom_bytes=pom_xml(
                "androidx.example",
                "widget",
                "1.2.3",
                licenses=None,
                parent=REVIEWED_PARENT,
            )
        )
        with self.assertRaisesRegex(ValueError, "declares no <licenses>"):
            self.export()

    def test_unknown_license_requires_review(self):
        self.declare(
            pom_bytes=pom_xml(
                "androidx.example",
                "widget",
                "1.2.3",
                licenses=[("MIT License", "https://opensource.org/licenses/MIT")],
            )
        )
        with self.assertRaisesRegex(ValueError, "Unrecognized license"):
            self.export()

    def test_one_unknown_license_among_known_ones_fails(self):
        self.declare(
            pom_bytes=pom_xml(
                "androidx.example",
                "widget",
                "1.2.3",
                licenses=[
                    DEFAULT_POM_LICENSE,
                    ("Eclipse Public License 2.0", "https://www.eclipse.org/legal/epl-2.0/"),
                ],
            )
        )
        with self.assertRaisesRegex(ValueError, "Eclipse Public License 2.0"):
            self.export()

    def test_apache_license_variants_are_normalized(self):
        variants = (
            ("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"),
            ("Apache License, Version 2.0", "http://www.apache.org/licenses/LICENSE-2.0.txt"),
            ("The Apache Software License, Version 2.0", "http://www.apache.org/licenses/LICENSE-2.0.txt"),
            ("Apache 2.0", "http://www.apache.org/licenses/LICENSE-2.0"),
            ("ASL 2.0", "http://www.apache.org/licenses/LICENSE-2.0.txt"),
        )
        for index, variant in enumerate(variants):
            self.declare(name=f"widget{index}", pom_bytes=pom_xml("androidx.example", "widget", "1.0.0", licenses=[variant]))
        manifest = self.export()
        for record in manifest["dependencies"]:
            self.assertEqual(record["licenses"], [CANONICAL_LICENSE])

    def test_apache_url_wins_when_name_is_missing(self):
        self.declare(
            pom_bytes=pom_xml(
                "androidx.example",
                "widget",
                "1.2.3",
                licenses=[("", "https://www.apache.org/licenses/LICENSE-2.0.txt")],
            )
        )
        manifest = self.export()
        self.assertEqual(manifest["dependencies"][0]["licenses"], [CANONICAL_LICENSE])

    def test_missing_apache_license_file_fails(self):
        self.declare()
        self.license_file.unlink()
        with self.assertRaisesRegex(ValueError, "Missing Apache-2.0 license text"):
            self.export()

    def test_non_apache_license_file_fails(self):
        self.declare()
        self.license_file.write_text(MIT_TEXT, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "does not contain the Apache License"):
            self.export()

    def test_truncated_apache_license_file_fails(self):
        self.declare()
        self.license_file.write_text("Apache License\nVersion 2.0\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "does not contain the full Apache License"):
            self.export()

    def test_duplicate_coordinate_fails(self):
        self.declare()
        duplicate = dict(self.dependencies[0])
        self.dependencies.append(duplicate)
        with self.assertRaisesRegex(ValueError, "Duplicate dependency coordinate"):
            self.export()

    def test_colliding_export_file_names_fail(self):
        fixture = self.declare(group="a-b", name="c", version="1.0")
        self.write_inventory(
            [
                {
                    "coordinate": "a-b:c:1.0",
                    "source": str(fixture["source"]),
                    "pom": str(fixture["pom"]),
                    "runtime": str(fixture["runtime"]),
                },
                {
                    "coordinate": "a:b-c:1.0",
                    "source": str(fixture["source"]),
                    "pom": str(fixture["pom"]),
                    "runtime": str(fixture["runtime"]),
                },
            ]
        )
        with self.assertRaisesRegex(ValueError, "same export file name"):
            collector.collect(self.inventory, self.output)

    def test_coordinate_must_be_group_name_version(self):
        fixture = self.declare()
        for coordinate in (
            "androidx.example:widget",
            "androidx.example:widget:1.0.0:extra",
            "a:b:",
            "androidx.example/widget/1.0.0",
        ):
            self.write_inventory(
                [
                    {
                        "coordinate": coordinate,
                        "source": str(fixture["source"]),
                        "pom": str(fixture["pom"]),
                        "runtime": str(fixture["runtime"]),
                    }
                ]
            )
            with self.assertRaisesRegex(ValueError, "must be group:name:version"):
                collector.collect(self.inventory, self.output)

    def test_empty_dependency_list_fails(self):
        self.dependencies = []
        with self.assertRaisesRegex(ValueError, "at least one dependency"):
            self.export()

    def test_missing_inventory_fails(self):
        with self.assertRaisesRegex(ValueError, "Missing dependency input inventory"):
            collector.collect(self.root / "absent.json", self.output)

    def test_wrong_schema_fails(self):
        payload = {
            "schema": 2,
            "license_file": str(self.license_file),
            "dependencies": [{"coordinate": "a:b:1"}],
        }
        self.inventory.write_text(json.dumps(payload), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "schema must be 1"):
            collector.collect(self.inventory, self.output)

    # -- optional native sources ------------------------------------------- #
    def test_native_sources_are_copied_and_hashed(self):
        self.declare()
        pinned = self.root / "pinned-native.tar.gz"
        pinned.write_bytes(b"synthetic native source archive")
        self.native_sources = [
            {
                "coordinate": "androidx.graphics:graphics-path:1.0.1",
                "source": str(pinned),
                "path": "native/graphics-path-1.0.1.tar.gz",
                "provenance": "pinned upstream tag graphics-path-1.0.1",
            }
        ]
        manifest = self.export()
        record = manifest["native_sources"][0]
        self.assertEqual(
            set(record), {"coordinate", "path", "sha256", "provenance"}
        )
        self.assertEqual(
            (self.output / record["path"]).read_bytes(), pinned.read_bytes()
        )
        self.assertEqual(record["sha256"], hashlib.sha256(pinned.read_bytes()).hexdigest())

    def test_native_source_paths_must_stay_inside_export(self):
        self.declare()
        pinned = self.root / "pinned.tar.gz"
        pinned.write_bytes(b"native")
        self.native_sources = [
            {
                "coordinate": "androidx.graphics:graphics-path:1.0.1",
                "source": str(pinned),
                "path": "../escaped.tar.gz",
                "provenance": "pinned",
            }
        ]
        with self.assertRaisesRegex(ValueError, "must stay inside the export directory"):
            self.export()

    # -- CLI --------------------------------------------------------------- #
    def test_cli_success_and_failure_exit_codes(self):
        self.declare()
        self.write_inventory()
        with redirect_stdout(io.StringIO()) as out:
            self.assertEqual(
                collector.main(["--input", str(self.inventory), "--output", str(self.output)]),
                0,
            )
        self.assertIn("Manifest:", out.getvalue())
        self.dependencies[0]["coordinate"] = "broken"
        self.write_inventory()
        with redirect_stderr(io.StringIO()) as err:
            self.assertEqual(
                collector.main(["--input", str(self.inventory), "--output", str(self.output)]),
                1,
            )
        self.assertIn("Dependency export failed", err.getvalue())


if __name__ == "__main__":
    unittest.main()
