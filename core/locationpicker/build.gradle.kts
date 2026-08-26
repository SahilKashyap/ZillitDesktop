import org.jetbrains.compose.ExperimentalComposeLibrary

plugins {
    id("zillit.compose.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The field is built from design-system components only, and
            // designsystem re-exposes core:common.
            api(project(":core:designsystem"))
            // For LocationPickerWire — the page bridge's JSON, kept here so it
            // is testable without an embedded browser anywhere in sight.
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            // Composes the real field in tests, as the feature modules do.
            implementation(compose.desktop.currentOs)
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
