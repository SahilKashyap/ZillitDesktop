package com.zillit.desktop.core.appupdate

/**
 * What the app knows about its own currency, as one closed set.
 *
 * ## Why [Unknown] is a first-class state and not `null`
 *
 * Every way this check can fail — no Firebase app id, no network, a Remote
 * Config template that has never been published, a build running from
 * `:desktopApp:run` with no packaged version — has to land somewhere that means
 * *"say nothing"*. Collapsing those onto `UpToDate` would be a lie the user
 * cannot detect; collapsing them onto [Available] would nag people who are
 * already current, which is how an update banner earns itself a permanent
 * dismissal in the reader's head.
 *
 * So there are four states, not three, and the UI shows a banner for exactly
 * two of them.
 */
sealed interface UpdateStatus {

    /**
     * No information. Never rendered.
     *
     * The default posture: unconfigured, unreachable, unpublished, or running
     * unpackaged. All of those are "we do not know", and none of them is
     * something to interrupt somebody about.
     */
    data object Unknown : UpdateStatus

    /** The installed build is at or above `desktop_latest_version`. */
    data object UpToDate : UpdateStatus

    /**
     * A newer build exists. Optional — the banner is dismissible and nothing is
     * blocked.
     *
     * @param latestVersion the value of `desktop_latest_version`, shown verbatim.
     * @param downloadUrl where to send the reader, or null when neither Remote
     *   Config nor the Zillit configuration supplied a usable https URL. The
     *   banner still appears (knowing a version exists is worth saying); it
     *   simply has no button.
     */
    data class Available(
        val latestVersion: String,
        val downloadUrl: String?,
        val installer: InstallerRef? = null,
    ) : UpdateStatus

    /**
     * The installed build is below `desktop_min_version`.
     *
     * Mirrors the Android client's `android_force_update` Remote Config boolean
     * (`utils/Constants.kt:213`, read in `ZillitApplication.kt:570` and
     * `splash/SplashActivity.kt:164`, acted on in `baseUtils/BaseActivity.kt:225`
     * where it picks `AppUpdateType.IMMEDIATE` over `FLEXIBLE`) — with one
     * deliberate change of shape. Android's flag is a global boolean, so turning
     * it on forces *everyone* including people already on the newest build. A
     * version floor forces only the builds that are actually too old, which is
     * what the flag was always being used to approximate.
     */
    data class Required(
        val latestVersion: String,
        val downloadUrl: String?,
        val installer: InstallerRef? = null,
    ) : UpdateStatus
}

/**
 * A direct link to the installer file, and the digest it must hash to.
 *
 * Separate from `downloadUrl` on purpose: that one is a page for a person to
 * read (prod's is a Google Drive page), while this one must answer the bytes
 * of a `.dmg` or `.msi` with no page in between. Both halves come from Remote
 * Config (`desktop_installer_url` and `desktop_installer_sha256`, each with the
 * usual platform suffixes), and one without the other is no installer at all:
 * a file nothing vouches for is never run.
 *
 * @param url https only — see [usableUrl].
 * @param sha256 64 lowercase hex characters.
 */
data class InstallerRef(val url: String, val sha256: String)

/** The installer, if either status carries one. */
val UpdateStatus.installer: InstallerRef?
    get() = when (this) {
        is UpdateStatus.Available -> installer
        is UpdateStatus.Required -> installer
        UpdateStatus.Unknown, UpdateStatus.UpToDate -> null
    }
