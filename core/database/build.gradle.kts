plugins {
    id("zillit.kmp.library")
    alias(libs.plugins.sqldelight)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:common"))
            api(project(":core:security"))

            api(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.jdbcDriver)
            // `sqlite-driver` supplies JdbcSqliteDriver but pulls
            // org.xerial:sqlite-jdbc with it. Two drivers both registering for
            // `jdbc:sqlite:` is a coin toss over which one handles the URL —
            // and losing that toss means the database opens UNENCRYPTED while
            // looking fine. Excluded, and asserted in EncryptedDriverFactoryTest.
            implementation(libs.sqldelight.sqliteDriver.get().toString()) {
                exclude(group = "org.xerial", module = "sqlite-jdbc")
            }
            implementation(libs.sqlite.jdbc.encrypted)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

sqldelight {
    databases {
        create("ZillitDatabase") {
            packageName.set("com.zillit.desktop.core.database")
            // Each feature module contributes its own .sq files into this
            // schema directory (plan §11.5) — no shared mega-schema file.
            srcDirs.setFrom("src/commonMain/sqldelight")
            // Off until M1 lands the first real schema: verification needs a
            // committed baseline .db plus .sqm migration files, and neither
            // exists yet. Turn on in the same commit that adds them — the plan
            // calls for forward-only migrations from the first shipped schema
            // (§5), and this is what enforces it.
            verifyMigrations.set(false)
        }
    }
}
