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
            implementation(project(":core:mvvm"))
            implementation(project(":core:localization"))
            implementation(project(":core:network"))
            implementation(project(":core:socket"))
            implementation(project(":core:workspace"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
