plugins {
    id("zillit.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            implementation(project(":core:database"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(libs.sqldelight.sqliteDriver.get().toString()) {
                exclude(group = "org.xerial", module = "sqlite-jdbc")
            }
            implementation(libs.sqlite.jdbc.encrypted)
        }
    }
}
