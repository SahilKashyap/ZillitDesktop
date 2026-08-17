import org.jetbrains.compose.ExperimentalComposeLibrary

plugins {
    id("zillit.compose.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:mvvm"))
            implementation(project(":core:permissions"))
            implementation(project(":core:workspace"))
        }
        // The gateway lives in jvmMain: it is a loopback HTTP server built on
        // `com.sun.net.httpserver` and `java.net.http`, which have no place in
        // common code and no third-party equivalent worth a dependency.
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
