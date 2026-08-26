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
            // The pin editor's Address field searches a place by name, which
            // the canvas's click-to-place cannot do — and which a host with no
            // canvas has no other way to do at all.
            implementation(project(":core:locationpicker"))
            implementation(project(":core:mvvm"))
            implementation(project(":core:config"))
            implementation(project(":core:network"))
            implementation(project(":core:permissions"))
            implementation(project(":core:localization"))
            implementation(project(":core:workspace"))
            implementation(libs.kotlinx.serialization.json)
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
