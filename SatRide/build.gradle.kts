plugins {
    base
    // Declared here (and not applied) so each plugin is loaded once into the root classloader
    // rather than once per subproject.
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.ktlint)
}

val ktlintVersion = libs.versions.ktlint.get()

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(ktlintVersion)
        filter {
            // Generated Compose resource accessors and build output are not ours to format.
            exclude { it.file.path.contains("${File.separator}build${File.separator}") }
        }
    }
}

/**
 * Enforces the module boundaries the architecture depends on (docs/plan/02-architecture.md).
 *
 * Rules:
 *  1. A feature module never depends on another feature module. Cross-feature navigation goes
 *     through the app shell, otherwise the graph becomes a mesh and modules stop being
 *     independently testable.
 *  2. core:orbit and core:dsp stay pure — model, common and stdlib only. They are the two
 *     modules extracted as open-source libraries in P13, so a stray dependency on Room or
 *     Ktor there is a release-blocking mistake, not a style preference.
 *
 * Checked against the declared dependencies in each module's build file, which is where every
 * dependency in this project is declared.
 */
val featureBuildFiles: List<File> =
    layout.projectDirectory
        .dir("feature")
        .asFile
        .listFiles()
        ?.mapNotNull { dir -> dir.resolve("build.gradle.kts").takeIf { it.isFile } }
        ?.sorted()
        .orEmpty()

val pureCoreBuildFiles: List<File> =
    listOf("orbit", "dsp")
        .map { layout.projectDirectory.file("core/$it/build.gradle.kts").asFile }
        .filter { it.isFile }

val checkModuleDependencyRules by tasks.registering {
    group = "verification"
    description = "Fails when a module depends on something the architecture forbids."
    inputs
        .files(featureBuildFiles + pureCoreBuildFiles)
        .withPropertyName("moduleBuildFiles")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    val features = featureBuildFiles
    val pureCores = pureCoreBuildFiles
    val allowedPureDeps = setOf("model", "common")

    doLast {
        val violations = mutableListOf<String>()
        val featureDep = Regex("""projects\.feature\.(\w+)""")
        val coreDep = Regex("""projects\.core\.(\w+)""")

        features.forEach { file ->
            val owner = file.parentFile.name
            file.readLines().forEachIndexed { index, line ->
                featureDep.find(line)?.let { match ->
                    violations += "feature:$owner -> feature:${match.groupValues[1]} " +
                        "(${file.name}:${index + 1}) — features must not depend on features"
                }
            }
        }

        pureCores.forEach { file ->
            val owner = file.parentFile.name
            file.readLines().forEachIndexed { index, line ->
                coreDep.find(line)?.let { match ->
                    val target = match.groupValues[1]
                    if (target !in allowedPureDeps) {
                        violations += "core:$owner -> core:$target (${file.name}:${index + 1}) — " +
                            "core:$owner must stay pure (model/common only) for library extraction"
                    }
                }
                if (line.contains("projects.feature.")) {
                    violations += "core:$owner depends on a feature (${file.name}:${index + 1})"
                }
            }
        }

        if (violations.isNotEmpty()) {
            error(
                buildString {
                    appendLine("Module dependency rules violated:")
                    violations.forEach { appendLine("  - $it") }
                    appendLine()
                    append("See docs/plan/02-architecture.md for why these boundaries exist.")
                },
            )
        }
        logger.lifecycle(
            "Module dependency rules OK (${features.size} features, ${pureCores.size} pure core modules).",
        )
    }
}

tasks.named("check") {
    dependsOn(checkModuleDependencyRules)
}
