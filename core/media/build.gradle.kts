import org.jetbrains.compose.ExperimentalComposeLibrary

plugins {
    id("zillit.compose.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:designsystem"))
            // Coroutines for the picker's off-thread dialog, and the log.
            implementation(project(":core:common"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // Composes the real dialog in tests, as the feature modules do.
            implementation(compose.desktop.currentOs)
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
