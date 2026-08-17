plugins {
    // Declared here so each plugin is loaded once into the root classloader and
    // reused by every subproject, rather than re-resolved per module.
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.detekt)
}

// Static analysis is applied to every module from here rather than from a
// convention plugin: detekt needs no per-module configuration, and applying it
// centrally keeps one ruleset and one baseline for the whole build.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    detekt {
        parallel = true
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        basePath = rootProject.projectDir.absolutePath
        // detekt defaults to the JVM layout (`src/main/kotlin`), which does not
        // exist in a KMP module — without this it silently reports NO-SOURCE and
        // analyses nothing. Non-existent directories are ignored, so one list
        // covers both KMP modules and the plain-JVM desktopApp.
        source.setFrom(
            files(
                "src/commonMain/kotlin",
                "src/jvmMain/kotlin",
                "src/commonTest/kotlin",
                "src/jvmTest/kotlin",
                "src/main/kotlin",
                "src/test/kotlin",
            ),
        )
    }

    // Hook the TLS tripwire into every module that has a `check` — so `build`,
    // `check`, and CI all run it, and no single module can opt out.
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn(":securityScan")
    }
}

/**
 * TLS-bypass tripwire (plan §8.2).
 *
 * A grep rather than a detekt rule on purpose: detekt's `ForbiddenMethodCall`
 * only fires with type resolution, which is easy to lose without noticing. This
 * either matches or it does not, and it is wired into `check` so it runs on
 * every build and in CI.
 */
tasks.register<Exec>("securityScan") {
    group = "verification"
    description = "Fails the build on TLS-bypass patterns (plan §8.2)."
    commandLine("bash", "${rootProject.projectDir}/scripts/security-scan.sh")
    // Scans the whole tree, so it is a single task — but it must be reachable
    // from any module's `check`, which is why subprojects hook it below rather
    // than the root declaring its own aggregate `check`. The container projects
    // (`:core`, `:feature`) have no `check` task at all, so an aggregate would
    // fail to resolve.
    inputs.files(
        fileTree(rootProject.projectDir) {
            include("**/*.kt", "**/*.kts", "**/*.java")
            exclude("**/build/**", "**/.gradle/**")
        },
    )
    inputs.file("${rootProject.projectDir}/scripts/security-scan.sh")
    outputs.upToDateWhen { false }
}
