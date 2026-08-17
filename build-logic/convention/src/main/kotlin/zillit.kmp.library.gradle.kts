import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/**
 * Base convention for every Kotlin Multiplatform library in the build.
 *
 * Targets JVM only today. Adding `wasmJs` later (DESKTOP_KMP_PLAN.md §1) is a
 * change to this one file plus platform `actual`s — which is precisely why
 * modules must never apply the Kotlin plugin directly.
 */

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val toolchainVersion = libs.findVersion("jvmToolchain").get().requiredVersion.toInt()

kotlin {
    jvmToolchain(toolchainVersion)

    jvm()

    sourceSets {
        getByName("commonTest").dependencies {
            implementation(libs.findLibrary("kotlin-test").get())
        }
    }

    compilerOptions {
        // `expect`/`actual` classes are still flagged as beta; we rely on them
        // for the platform boundaries listed in the plan (§11.1).
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(toolchainVersion.toString()))
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
