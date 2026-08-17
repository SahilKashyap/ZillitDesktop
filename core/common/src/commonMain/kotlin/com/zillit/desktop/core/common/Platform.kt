package com.zillit.desktop.core.common

/**
 * The host the app is running on.
 *
 * Kept deliberately small: this is the seam that lets `core:filestore`,
 * `core:security` and the packaging config branch per OS without every module
 * reaching for `System.getProperty` (plan §11.1).
 */
interface Platform {
    val name: String
    val os: OperatingSystem
}

enum class OperatingSystem {
    MacOs,
    Windows,
    Linux,
    Unknown,
    ;

    /** ChromeOS runs the Linux build inside Crostini (plan §1). */
    val isDesktop: Boolean get() = this != Unknown
}

expect fun currentPlatform(): Platform
