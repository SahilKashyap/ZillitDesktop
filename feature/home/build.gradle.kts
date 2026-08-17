plugins {
    id("zillit.compose.library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            implementation(project(":core:config"))
            implementation(project(":core:network"))
            implementation(project(":core:permissions"))
            implementation(project(":core:badges"))
            implementation(project(":core:datastore"))
            implementation(project(":core:localization"))
            implementation(project(":core:notifications"))
            implementation(project(":core:socket"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:mvvm"))
            implementation(project(":core:workspace"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.jcodec)
            implementation(libs.jcodec.javase)
            implementation(libs.pdfbox)
        }
        jvmTest.dependencies {
            implementation(libs.pdfbox)
        }
    }
}
