import org.jetbrains.compose.ExperimentalComposeLibrary

plugins {
    id("zillit.compose.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:config"))
            implementation(project(":core:localization"))
            implementation(project(":core:network"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:media"))
            implementation(project(":core:mvvm"))
            implementation(project(":core:database"))
            implementation(project(":core:socket"))
            implementation(project(":core:workspace"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Composes the real composer, as the other feature modules do.
            implementation(compose.desktop.currentOs)
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
