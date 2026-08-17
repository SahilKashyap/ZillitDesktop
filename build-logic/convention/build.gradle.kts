plugins {
    `kotlin-dsl`
}

group = "com.zillit.desktop.buildlogic"

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    // Plugin artifacts are `compileOnly`: the convention plugins only need them
    // on the compile classpath to configure their extensions. The real plugin
    // versions are resolved by the consuming build from the version catalog.
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.composeCompiler.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
}
