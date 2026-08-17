import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Convention for library modules that expose Compose UI — `core:designsystem`,
 * `core:workspace`, and every `feature:*` module.
 *
 * Non-UI modules (`core:network`, `core:database`, …) use `zillit.kmp.library`
 * instead, so the Compose compiler never runs over pure logic.
 */

plugins {
    id("zillit.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

kotlin {
    sourceSets {
        getByName("commonMain").dependencies {
            implementation(libs.findLibrary("compose-runtime").get())
            implementation(libs.findLibrary("compose-foundation").get())
            implementation(libs.findLibrary("compose-material3").get())
            implementation(libs.findLibrary("compose-ui").get())
            implementation(libs.findLibrary("compose-components-resources").get())
            implementation(libs.findLibrary("androidx-lifecycle-viewmodelCompose").get())
            implementation(libs.findLibrary("androidx-lifecycle-runtimeCompose").get())
        }
    }
}
