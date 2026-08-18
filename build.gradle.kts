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
    commandLine(posixBash(), "${rootProject.projectDir}/scripts/security-scan.sh")
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

/**
 * The shell the tripwire runs under.
 *
 * `bash` off PATH is not safe to assume on Windows: System32 ships a `bash.exe`
 * that is only a WSL launcher, so CI failed the scan with "Windows Subsystem
 * for Linux has no installed distributions" rather than on anything it found —
 * a green-looking guard that never actually ran. Git for Windows carries a real
 * bash and is present wherever this repo was cloned, so it is preferred and the
 * WSL stub is skipped explicitly.
 *
 * Falls back to plain `bash` rather than failing configuration, so a machine
 * with a bash somewhere unusual still gets the original error from the task
 * rather than a build that will not configure at all.
 */
fun posixBash(): String {
    if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return "bash"

    val onPath = (providers.environmentVariable("PATH").orNull ?: "")
        .split(java.io.File.pathSeparator)
        .filter { it.isNotBlank() }
        .map { java.io.File(it, "bash.exe") }
        // System32's bash is WSL; WindowsApps holds the Store alias stubs.
        .filterNot { it.path.contains("System32", ignoreCase = true) }
        .filterNot { it.path.contains("WindowsApps", ignoreCase = true) }

    val alongsideGit = (providers.environmentVariable("PATH").orNull ?: "")
        .split(java.io.File.pathSeparator)
        .filter { it.isNotBlank() && java.io.File(it, "git.exe").isFile }
        // git.exe lives in Git's `cmd/`; bash sits beside it in `bin/`.
        .map { java.io.File(java.io.File(it).parentFile, "bin/bash.exe") }

    val wellKnown = listOf(
        java.io.File("""C:\Program Files\Git\bin\bash.exe"""),
        java.io.File("""C:\Program Files (x86)\Git\bin\bash.exe"""),
    )

    return (onPath + alongsideGit + wellKnown).firstOrNull { it.isFile }?.absolutePath ?: "bash"
}
