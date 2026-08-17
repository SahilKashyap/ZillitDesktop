package com.zillit.desktop.core.common

private class JvmPlatform : Platform {
    override val name: String =
        "${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
            "(JVM ${System.getProperty("java.version")})"

    override val os: OperatingSystem = detectOs()
}

private fun detectOs(): OperatingSystem {
    val raw = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        raw.contains("mac") || raw.contains("darwin") -> OperatingSystem.MacOs
        raw.contains("win") -> OperatingSystem.Windows
        raw.contains("nix") || raw.contains("nux") -> OperatingSystem.Linux
        else -> OperatingSystem.Unknown
    }
}

actual fun currentPlatform(): Platform = JvmPlatform()
