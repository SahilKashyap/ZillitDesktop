plugins {
    id("zillit.kmp.library")
}

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        commonMain.dependencies {
            api(project(":core:common"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
