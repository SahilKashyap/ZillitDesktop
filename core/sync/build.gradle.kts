plugins {
    id("zillit.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            // The durable tables live in core:database's SyncDatabase; this
            // module owns what is done with them.
            implementation(project(":core:database"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // An in-memory driver for the store and engine tests.
            implementation(libs.sqldelight.sqliteDriver.get().toString()) {
                exclude(group = "org.xerial", module = "sqlite-jdbc")
            }
            implementation(libs.sqlite.jdbc.encrypted)
        }
    }
}
