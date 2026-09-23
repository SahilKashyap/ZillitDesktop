import org.jetbrains.compose.ExperimentalComposeLibrary

plugins {
    id("zillit.compose.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // `api`, not `implementation`: every feature module reaches
            // `str(S.key)` through the design system, so the words and the
            // widgets that show them arrive together.
            api(project(":core:strings"))
        }
        jvmMain.dependencies {
            implementation(libs.zxing.core)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // Composes real components in tests, as the feature modules do.
            implementation(compose.desktop.currentOs)
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.zillit.desktop.core.designsystem.generated.resources"
    generateResClass = always
}
