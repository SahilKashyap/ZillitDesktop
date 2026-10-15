import org.jetbrains.compose.ExperimentalComposeLibrary

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
            implementation(project(":core:config"))
            implementation(project(":core:network"))
            implementation(project(":core:localization"))
            implementation(project(":core:socket"))
            implementation(project(":core:workspace"))
            implementation(libs.kotlinx.serialization.json)
            // Every grid day is a LocalDate: the chart's window, the day codes
            // and the holiday ranges are all day arithmetic.
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
