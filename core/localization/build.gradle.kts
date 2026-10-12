import org.jetbrains.compose.ExperimentalComposeLibrary

/**
 * The server's dictionaries — labels, messages, identifiers — for the words
 * the backend owns.
 *
 * A Compose library rather than a plain one for the same single reason
 * `core:strings` is: the loaded dictionary's revision is snapshot state, which
 * is what lets one ordinary extension — `"call_sheet_label".localised()` —
 * read correctly from a mapper and also recompose the caller when a language
 * lands. Nothing else in Compose is touched; there is no UI here.
 */
plugins {
    id("zillit.compose.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:database"))
            implementation(project(":core:config"))
            implementation(project(":core:network"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            // Composes a `Text` that reads a label, to prove the repaint.
            implementation(compose.desktop.currentOs)
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
