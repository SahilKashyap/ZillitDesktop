package com.zillit.desktop.core.common

import java.io.File

/**
 * Which packaged build this process is — the thing that lets a production,
 * QA and develop install of Zillit sit on one machine at once without
 * touching each other's data.
 *
 * Baked in at package time as `-Dzillit.variant` (see
 * `desktopApp/build.gradle.kts`, `-PzillitVariant` / `-PzillitEnv`), and
 * forwarded the same way for `:desktopApp:run`. Absent, blank, "prod" and
 * "production" all mean production — the id every existing install already
 * has data under, so leaving the property unset must reproduce today's paths
 * exactly rather than demanding a migration.
 *
 * Everything a second, differently-badged copy of the app would otherwise
 * collide over reads through here: the data directory ([dataDir], which is
 * also what [SingleInstance][com.zillit.desktop.SingleInstance] locks — see
 * its own doc for the SIGTRAP two unrelated copies once produced by sharing
 * it), the Keychain/Credential Manager service name, and the macOS bundle id
 * / LaunchAgent label.
 */
object ZillitVariant {

    /** Raw id: `""` for production, else `"qa"`, `"develop"`, or whatever was baked in. */
    val id: String = System.getProperty(PROPERTY, "")
        .trim()
        .lowercase()
        .let { if (it == "prod" || it == "production") "" else it }

    /** A word for a person to read: blank for production, otherwise Title-cased. */
    val label: String = when (id) {
        "" -> ""
        "qa" -> "QA"
        "develop" -> "Dev"
        else -> id.replaceFirstChar(Char::uppercase)
    }

    /**
     * Where this variant's database, preferences, workspace session, single-
     * instance lock and logs live — `~/.zillit` for production so nothing
     * changes for it, `~/.zillit-qa` / `~/.zillit-develop` otherwise.
     */
    val dataDir: File by lazy {
        File(System.getProperty("user.home"), if (id.isEmpty()) ".zillit" else ".zillit-$id")
    }

    /** The Keychain / Credential Manager service entries are filed under. */
    val keychainService: String = if (id.isEmpty()) "Zillit Desktop" else "Zillit Desktop $label"

    /**
     * The macOS bundle id this variant is packaged and signed under, and the
     * LaunchAgent label its login item uses — must match `macOS { bundleID }`
     * in `desktopApp/build.gradle.kts` for the given variant.
     */
    val bundleId: String = if (id.isEmpty()) "com.zillit.desktop" else "com.zillit.desktop.$id"

    private const val PROPERTY = "zillit.variant"
}
