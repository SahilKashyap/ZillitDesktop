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
            implementation(project(":core:designsystem"))
            implementation(project(":core:media"))
            implementation(project(":core:localization"))
            implementation(project(":core:mvvm"))
            implementation(project(":core:network"))
            implementation(project(":core:units"))
            implementation(project(":core:security"))
            implementation(project(":core:socket"))
            api(project(":core:workspace"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.test)
            // Composes the real picker in tests, as core:designsystem does.
            implementation(compose.desktop.currentOs)
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
