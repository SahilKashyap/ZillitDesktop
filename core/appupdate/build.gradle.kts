plugins {
    id("zillit.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // `FirebaseConfig` — the project id, api key and app id the fetch
            // needs. Nothing else from config is read.
            implementation(project(":core:config"))
            // For the `HttpClient` type only. This module must never see
            // `ApiClient`: the request goes to Google, and Google must never
            // see the Zillit headers (AppGraph.kt:915 makes the same choice for
            // `DevicePresenceSource`).
            implementation(project(":core:network"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}
