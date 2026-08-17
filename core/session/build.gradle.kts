plugins {
    id("zillit.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:database"))
            implementation(project(":core:config"))
            implementation(project(":core:network"))
            implementation(project(":core:permissions"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
