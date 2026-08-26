import org.jetbrains.compose.ExperimentalComposeLibrary

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
            implementation(project(":core:sync"))
            implementation(project(":core:badges"))
            implementation(project(":core:datastore"))
            implementation(project(":core:localization"))
            implementation(project(":core:notifications"))
            implementation(project(":core:socket"))
            implementation(project(":core:designsystem"))
            // The calendar event's Location field is a map picker.
            implementation(project(":core:locationpicker"))
            // The picked-media preview and the pen editor behind the image reply.
            implementation(project(":core:media"))
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
            // Render tests compose the real board — see HomeBoardRenderTest.
            implementation(compose.desktop.currentOs)
            @OptIn(ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
        }
    }
}
