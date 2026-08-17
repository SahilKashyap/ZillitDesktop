plugins {
    id("zillit.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            implementation(project(":core:config"))
            implementation(project(":core:security"))
        }
        jvmMain.dependencies {
            implementation(libs.socket.io.client)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
