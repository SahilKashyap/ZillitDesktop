import org.jetbrains.compose.ExperimentalComposeLibrary

plugins {
    id("zillit.compose.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:socket"))
            implementation(project(":core:common"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:mvvm"))
            implementation(project(":core:config"))
            implementation(project(":core:network"))
            implementation(project(":core:localization"))
            implementation(project(":core:workspace"))
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            // The deal page renders, and signs, its PDFs itself: the web uses
            // pdf.js and pdf-lib, which a Compose window has no equivalent of.
            implementation(libs.pdfbox)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
