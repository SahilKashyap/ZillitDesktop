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
            implementation(project(":core:database"))
            implementation(project(":core:designsystem"))
            // The picked-media preview dialog the thread's composer opens
            // before a file is sent — the same one the Home board hosts.
            implementation(project(":core:media"))
            implementation(project(":core:mvvm"))
            implementation(project(":core:localization"))
            implementation(project(":core:network"))
            implementation(project(":core:socket"))
            implementation(project(":core:sync"))
            implementation(project(":core:workspace"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
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
