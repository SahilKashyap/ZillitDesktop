import org.jetbrains.compose.ExperimentalComposeLibrary

plugins {
    id("zillit.compose.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:localization"))
            implementation(project(":core:socket"))
            implementation(project(":core:common"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:mvvm"))
            implementation(project(":core:config"))
            implementation(project(":core:network"))
            implementation(project(":core:permissions"))
            implementation(project(":core:workspace"))
            implementation(libs.kotlinx.serialization.json)
        }
        // The PDF work — rendering pages for preview, stamping the signature
        // into the document — is PDFBox, which is JVM-only. The interfaces
        // live in commonMain; these are their only implementations.
        jvmMain.dependencies {
            implementation(libs.pdfbox)
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
