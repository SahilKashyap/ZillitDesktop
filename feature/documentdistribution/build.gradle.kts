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
            implementation(project(":core:permissions"))
            implementation(project(":core:workspace"))
            // `decodeImageBitmap`, for the in-app preview of an image document.
            implementation(project(":core:media"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            // Resolving a document's URL must not touch the network; the mock
            // engine is what proves it by refusing every request.
            implementation(libs.ktor.client.mock)
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
