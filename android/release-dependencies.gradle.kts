// Release dependency export for the Android app.
//
// Applied from android/app/build.gradle.kts:
//
//     apply(from = rootProject.file("release-dependencies.gradle.kts"))
//
// Registers :app:exportReleaseDependencies which
//   * activates dependency locking for releaseRuntimeClasspath only (no other
//     configuration is locked, and nothing is locked project-wide),
//   * resolves the external Maven components of releaseRuntimeClasspath at task
//     execution time - never at configuration time,
//   * writes app/build/release-dependencies-input.json: for every external
//     component (group:name:version) the absolute source JAR, POM and runtime
//     artifact paths, fetched with the detached "sources@jar" classifier and the
//     "pom" extension so they do not depend on repository-declared variants,
//   * runs scripts/collect-android-dependencies.py (python3 on Linux/macOS,
//     python on Windows) to copy sanitized deterministic sources/POMs, the
//     Apache-2.0 text, captured licenses/notices and manifest.json into
//     app/build/release-dependencies.
//
// Binary runtime artifacts are resolved so their licenses/notices (including
// classes.jar inside AARs) can be captured, but they are never exported into the
// dependency source directory. The export runs without signing credentials.
//
// Copy the output into the source ZIP as android/dependency-sources/.

import java.io.File
import org.gradle.api.GradleException

val releaseDependencyConfigurationName = "releaseRuntimeClasspath"

// android/ is the root project; the collector and license text live in the repo.
val repositoryRoot: File = rootProject.projectDir.parentFile
val releaseDependencyCollector: File = File(repositoryRoot, "scripts/collect-android-dependencies.py")
val apacheLicenseText: File = File(rootProject.projectDir, "licenses/Apache-2.0.txt")

configurations.configureEach {
    if (name == releaseDependencyConfigurationName) {
        // Lock only the release runtime graph. Other configurations stay unlocked.
        resolutionStrategy.activateDependencyLocking()
    }
}

tasks.register("exportReleaseDependencies") {
    group = "release"
    description =
        "Export resolved $releaseDependencyConfigurationName sources, POMs, licenses and notices."

    // Resolves the runtime graph and shells out to the collector during execution.
    notCompatibleWithConfigurationCache(
        "Resolves $releaseDependencyConfigurationName and runs the Python dependency collector at execution time."
    )

    val inventoryFile = layout.buildDirectory.file("release-dependencies-input.json")
    val exportDirectory = layout.buildDirectory.dir("release-dependencies")

    outputs.file(inventoryFile)
    outputs.dir(exportDirectory)
    // Always re-export: the result depends on resolved artifacts, not on declared inputs.
    outputs.upToDateWhen { false }

    doLast {
        val runtimeConfiguration = project.configurations.getByName(releaseDependencyConfigurationName)
        val runtimeArtifacts = runtimeConfiguration.incoming.artifacts.artifacts

        // External Maven components only: platforms/BOMs contribute no artifact and
        // project components are not external, so both drop out naturally.
        val artifactsByCoordinate = sortedMapOf<String, MutableList<File>>()
        for (artifact in runtimeArtifacts) {
            val identifier = artifact.id.componentIdentifier
            if (identifier !is org.gradle.api.artifacts.component.ModuleComponentIdentifier) {
                continue
            }
            val coordinate = "${identifier.group}:${identifier.module}:${identifier.version}"
            artifactsByCoordinate.getOrPut(coordinate) { mutableListOf() }.add(artifact.file)
        }
        if (artifactsByCoordinate.isEmpty()) {
            throw GradleException(
                "No external Maven components resolved for $releaseDependencyConfigurationName"
            )
        }

        fun runtimeArtifactRank(file: File): Int = when (file.extension.lowercase()) {
            "aar" -> 0
            "jar" -> 1
            else -> 2
        }

        // Resolve one auxiliary artifact of the same module (sources JAR, POM) without
        // transitivity and without touching the locked runtime configuration.
        fun resolveAuxiliaryArtifact(coordinate: String, notation: String, kind: String): File {
            val configuration = project.configurations.detachedConfiguration(
                project.dependencies.create(notation)
            )
            configuration.isTransitive = false
            val resolved = try {
                configuration.resolve()
            } catch (error: Exception) {
                throw GradleException(
                    "Missing $kind artifact for $coordinate (looked up '$notation'): ${error.message}",
                    error,
                )
            }
            if (resolved.size != 1) {
                throw GradleException(
                    "Expected exactly one $kind artifact for $coordinate but resolved ${resolved.size}"
                )
            }
            val file = resolved.single()
            if (!file.isFile) {
                throw GradleException("Missing $kind artifact file for $coordinate: $file")
            }
            return file
        }

        fun escapeJson(value: String): String {
            val escaped = StringBuilder(value.length + 8)
            for (character in value) {
                when (character) {
                    '\\' -> escaped.append("\\\\")
                    '"' -> escaped.append("\\\"")
                    '\n' -> escaped.append("\\n")
                    '\r' -> escaped.append("\\r")
                    '\t' -> escaped.append("\\t")
                    else -> if (character < ' ') {
                        escaped.append("\\u%04x".format(character.code))
                    } else {
                        escaped.append(character)
                    }
                }
            }
            return escaped.toString()
        }

        val coordinates = artifactsByCoordinate.keys.toList()
        val inventory = StringBuilder()
        inventory.append("{\n")
        inventory.append("  \"schema\": 1,\n")
        inventory.append("  \"license_file\": \"${escapeJson(apacheLicenseText.absolutePath)}\",\n")
        inventory.append("  \"dependencies\": [\n")
        coordinates.forEachIndexed { index, coordinate ->
            val runtimeArtifact = artifactsByCoordinate.getValue(coordinate)
                .sortedWith(compareBy<File> { runtimeArtifactRank(it) }.thenBy { it.name })
                .first()
            val sourceArtifact = resolveAuxiliaryArtifact(
                coordinate,
                "$coordinate:sources@jar",
                "source JAR",
            )
            val pomArtifact = resolveAuxiliaryArtifact(coordinate, "$coordinate@pom", "POM")
            if (index > 0) {
                inventory.append(",\n")
            }
            inventory.append("    {")
            inventory.append("\"coordinate\": \"${escapeJson(coordinate)}\", ")
            inventory.append("\"source\": \"${escapeJson(sourceArtifact.absolutePath)}\", ")
            inventory.append("\"pom\": \"${escapeJson(pomArtifact.absolutePath)}\", ")
            inventory.append("\"runtime\": \"${escapeJson(runtimeArtifact.absolutePath)}\"")
            inventory.append("}")
        }
        inventory.append("\n  ]\n}\n")

        val inventoryFilePath = inventoryFile.get().asFile
        inventoryFilePath.parentFile?.mkdirs()
        inventoryFilePath.writeText(inventory.toString())
        logger.lifecycle(
            "Recorded ${coordinates.size} Maven runtime components in ${inventoryFilePath.absolutePath}"
        )

        if (!releaseDependencyCollector.isFile) {
            throw GradleException(
                "Missing dependency collector script: ${releaseDependencyCollector.absolutePath}"
            )
        }
        if (!apacheLicenseText.isFile) {
            throw GradleException(
                "Missing Apache-2.0 license text: ${apacheLicenseText.absolutePath}"
            )
        }

        val windows = System.getProperty("os.name").orEmpty().lowercase().contains("windows")
        val pythonExecutable = if (windows) "python" else "python3"
        val command = listOf(
            pythonExecutable,
            releaseDependencyCollector.absolutePath,
            "--input",
            inventoryFilePath.absolutePath,
            "--output",
            exportDirectory.get().asFile.absolutePath,
        )
        val process = try {
            ProcessBuilder(command)
                .directory(repositoryRoot)
                .redirectErrorStream(true)
                .start()
        } catch (error: java.io.IOException) {
            throw GradleException(
                "Unable to start '$pythonExecutable' for the dependency collector: ${error.message}",
                error,
            )
        }
        val transcript = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw GradleException(
                "Dependency collector failed with exit code $exitCode:\n${transcript.trim()}"
            )
        }
        transcript.trim().lines().filter { it.isNotBlank() }.forEach { logger.lifecycle(it) }

        val manifestFile = exportDirectory.get().asFile.resolve("manifest.json")
        if (!manifestFile.isFile) {
            throw GradleException(
                "Dependency collector did not write ${manifestFile.absolutePath}"
            )
        }
        logger.lifecycle("Exported dependency sources to ${exportDirectory.get().asFile.absolutePath}")
    }
}
