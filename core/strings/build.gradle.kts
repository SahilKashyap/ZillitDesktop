import org.jetbrains.compose.ExperimentalComposeLibrary

/**
 * The app's own words, in every language the Android client ships.
 *
 * A Compose library rather than a plain one for a single reason: the loaded
 * catalogue is snapshot state (`mutableStateOf`), which is what lets one
 * ordinary function — `str(S.key)` — read correctly from a ViewModel and also
 * recompose the caller when the language changes. Nothing else in Compose is
 * touched; there is no UI here.
 */
plugins {
    id("zillit.compose.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
