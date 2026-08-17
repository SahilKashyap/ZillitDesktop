import org.jetbrains.compose.ExperimentalComposeLibrary

plugins {
    id("zillit.compose.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
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
