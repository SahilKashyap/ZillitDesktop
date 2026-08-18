plugins {
    id("zillit.compose.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:mvvm"))
            implementation(project(":core:workspace"))
            implementation(libs.kotlinx.serialization.json)
        }
        // The PDF — Courier on US Letter, the page a script is measured in —
        // is PDFBox, JVM-only. Its interface lives in commonMain.
        jvmMain.dependencies {
            implementation(libs.pdfbox)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
