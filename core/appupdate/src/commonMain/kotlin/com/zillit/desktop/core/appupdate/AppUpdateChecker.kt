package com.zillit.desktop.core.appupdate

import com.zillit.desktop.core.common.OperatingSystem
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.config.FirebaseConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Asks Firebase Remote Config whether this install is out of date.
 *
 * ## The keys the backend team must publish
 *
 * All three live in the Firebase console under the same project as the calling
 * plane's config, and — per the Remote Config REST contract — **every value is
 * a string**, even the version numbers. Type the bare value into the console:
 * `1.2.0`, **not** `"1.2.0"`. The quotes below are Kotlin's, not part of the
 * value. (They were typed in once, on every environment, and the checker read
 * `"1.0.3"` as `0.0.3` for months — see [AppVersions.normalise], which now
 * forgives it.)
 *
 * | Key | Value | Meaning |
 * |---|---|---|
 * | `desktop_latest_version` | `1.2.0` | newest published desktop build |
 * | `desktop_min_version` | `1.1.0` | below this, updating is mandatory |
 * | `desktop_download_url` | `https://zillit.com/download` | where to send the reader |
 *
 * ### Per-platform overrides
 *
 * A Mac and a Windows build are cut on different machines and rarely on the
 * same day, and a `.dmg` is no use to someone on Windows. Each key therefore
 * accepts a platform-suffixed variant that wins over the plain one when this
 * install matches it:
 *
 * | Platform | Suffix | e.g. |
 * |---|---|---|
 * | macOS | `_mac` | `desktop_download_url_mac` |
 * | Windows | `_windows` | `desktop_download_url_windows` |
 * | Linux | `_linux` | `desktop_latest_version_linux` |
 *
 * The plain key is the fallback for every platform, so a template that names
 * only `desktop_download_url` keeps working; one that adds
 * `desktop_download_url_windows` sends Windows to the `.msi` and everyone
 * else to the plain link. Versions can be split the same way when one
 * platform's build lags.
 *
 * `desktop_download_url` is optional: when it is absent the checker falls back
 * to the Zillit configuration's own `app_download_url`
 * (`RemoteCredentials.appDownloadUrl`), which is the value the phones already
 * use. Only `https://` is accepted from any source — see [usableUrl].
 *
 * The keys are `desktop_`-prefixed on purpose. Android reads
 * `android_force_update` (`utils/Constants.kt:213`); sharing one key across
 * clients would mean a desktop release note forcing an update on every phone.
 *
 * ## Why this talks to Google over the plain client
 *
 * The request carries no Zillit identity and must carry none. `core:network`'s
 * `ApiClient` attaches the encrypted `moduledata` / `bodyhash` headers to every
 * call; sending those to `firebaseremoteconfig.googleapis.com` would hand a
 * third party the session's device, project and user for nothing. The app
 * injects the *plain* Ktor client here, exactly as it does for the chat
 * presence feed (`AppGraph.kt:915` — "Google must never see the Zillit
 * headers").
 *
 * ## No JVM Firebase SDK
 *
 * There is no desktop Firebase Remote Config SDK, so this speaks the REST
 * surface directly — the same choice `DevicePresenceSource` made for the
 * Realtime Database.
 *
 * ## Failure posture: silence, never a false alarm
 *
 * Anything that is not a clear "there is a newer version" resolves to
 * [UpdateStatus.Unknown]: no app id configured, no packaged version, a network
 * error, an HTTP failure, `NO_TEMPLATE`, an empty `entries` object. Telling
 * somebody they are out of date when they are not is worse than telling them
 * nothing, because it is unfalsifiable from where they sit.
 *
 * ## Nothing here is ever logged
 *
 * The api key travels as a query parameter because that is the API's contract.
 * It is never written to a log, never included in an error message, and the
 * request URL is never logged — [FirebaseConfig.toString] masks the key and
 * this class never prints the URL it built. The app injects the plain client
 * with `verboseLogging = false`, so Ktor does not log it either.
 *
 * @param httpClient the plain client — **never** `ApiClient`.
 * @param firebase the configured Firebase triple; null switches the whole
 *   feature off.
 * @param installedVersion the build's own version — `BuildInfo.VERSION` in the
 *   app, which jpackage also stamps as `jpackage.app-version`. Null or blank
 *   means there is nothing honest to compare against, and the check stays
 *   silent.
 * @param os which platform-suffixed keys apply to this install. `Unknown`
 *   reads only the plain keys.
 * @param instanceId a stable per-install id, persisted by the caller. A fresh
 *   id every launch would make this install look like a new one to Firebase and
 *   skew percentage rollouts, so the caller generates once and stores.
 * @param fallbackDownloadUrl the Zillit configuration's `app_download_url`,
 *   used when `desktop_download_url` is absent. Suspending because the caller
 *   reads it from `RemoteConfigRepository.current()`.
 * @param host overridable for tests only; production never passes it.
 */
class AppUpdateChecker(
    private val httpClient: HttpClient,
    private val firebase: FirebaseConfig?,
    private val installedVersion: () -> String?,
    private val instanceId: suspend () -> String,
    private val fallbackDownloadUrl: suspend () -> String?,
    private val os: OperatingSystem = OperatingSystem.Unknown,
    private val host: String = FIREBASE_REMOTE_CONFIG_HOST,
) {

    /** Logged at most once per instance — a six-hourly poll must not fill the log. */
    private var announcedOff = false

    /**
     * One fetch, one verdict. Safe to call on any schedule; never throws.
     */
    suspend fun check(): UpdateStatus {
        val ready = readiness() ?: return UpdateStatus.Unknown
        val entries = fetchEntries(ready) ?: return UpdateStatus.Unknown
        return verdict(entries, ready.installedVersion)
    }

    /**
     * The three preconditions, or null with one quiet explanation.
     *
     * `appId` is the interesting one: the Remote Config REST API rejects a body
     * without it, and it is not in `zillit.properties` on any existing install.
     * Absent, the feature is simply off — no banner, no error dialog, one debug
     * line for whoever is wondering why. A deployment that has not been given
     * an app id has not opted into update notices.
     */
    private fun readiness(): Readiness? {
        val config = firebase
        val appId = config?.appId
        if (config == null || appId.isNullOrBlank()) {
            announceOff("no Firebase app id configured (<ENV>_FIREBASE_APP_ID)")
            return null
        }

        val installed = AppVersions.normalise(installedVersion())
        if (!AppVersions.isMeaningful(installed)) {
            // Nothing to compare against can only produce a wrong answer, so
            // it produces none.
            announceOff("no build version on this run; not checking")
            return null
        }

        return Readiness(config.projectId, config.apiKey, appId, installed)
    }

    /**
     * `POST /v1/projects/{projectId}/namespaces/firebase:fetch?key={apiKey}`.
     *
     * Returns the `entries` map, or null for every failure mode. `NO_TEMPLATE`
     * and an absent/empty `entries` are treated as "no information" rather than
     * "you are current" — a project whose template was never published knows
     * nothing about which build is newest.
     */
    private suspend fun fetchEntries(ready: Readiness): Map<String, String>? = try {
        val response = httpClient.post("$host/v1/projects/${ready.projectId}/namespaces/firebase:fetch") {
            parameter("key", ready.apiKey)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(FetchRequest.serializer(), request(ready)))
        }
        if (response.status.isSuccess()) {
            parseEntries(response.bodyAsText())
        } else {
            // The status, never the body: an error payload echoes the request.
            ZillitLog.d(TAG) { "remote config fetch returned ${response.status.value}" }
            null
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (@Suppress("TooGenericExceptionCaught") ignored: Throwable) {
        // Deliberately swallowed, and deliberately without its message or stack
        // trace: a Ktor failure stringifies the request URL, which carries the
        // api key. "Unreachable" is the whole of what this is allowed to say,
        // and the verdict it produces — Unknown — is the same either way.
        ZillitLog.d(TAG) { "remote config unreachable; saying nothing" }
        null
    }

    private suspend fun request(ready: Readiness) = FetchRequest(
        appId = ready.appId,
        appInstanceId = instanceId(),
        // The app's own strings are English; the values read here are version
        // numbers and a URL, none of which is translated.
        languageCode = DEFAULT_LANGUAGE,
    )

    /**
     * Turns published values into a verdict.
     *
     * `Required` is tested first and wins outright: when a build is below the
     * floor it is both below the floor and behind the latest, and being told
     * "an update is available" for something that is actually mandatory is the
     * failure that gets ignored for a week.
     */
    private suspend fun verdict(entries: Map<String, String>, installed: String): UpdateStatus {
        val latest = AppVersions.normalise(entries.forPlatform(KEY_LATEST_VERSION, os))
        val floor = AppVersions.normalise(entries.forPlatform(KEY_MIN_VERSION, os))
        if (!AppVersions.isMeaningful(latest) && !AppVersions.isMeaningful(floor)) {
            // The template exists but says nothing about desktop builds.
            return UpdateStatus.Unknown
        }

        val url = usableUrl(entries.forPlatform(KEY_DOWNLOAD_URL, os)) ?: usableUrl(fallbackDownloadUrl())
        ZillitLog.d(TAG) {
            "installed $installed; latest ${latest.ifEmpty { "-" }}; floor ${floor.ifEmpty { "-" }}"
        }
        if (AppVersions.isMeaningful(floor) && AppVersions.isBelow(installed, floor)) {
            // The floor is the honest thing to name when no separate latest was
            // published — "update to at least this" beats naming nothing.
            return UpdateStatus.Required(latest.takeIf { AppVersions.isMeaningful(it) } ?: floor, url)
        }
        return if (AppVersions.isNewer(latest, installed)) {
            UpdateStatus.Available(latest, url)
        } else {
            UpdateStatus.UpToDate
        }
    }

    private fun announceOff(reason: String) {
        if (!announcedOff) {
            announcedOff = true
            ZillitLog.d(TAG) { "update check off: $reason" }
        }
    }

    private companion object {
        const val TAG = "AppUpdate"
        const val DEFAULT_LANGUAGE = "en"

        val json = Json { ignoreUnknownKeys = true }
    }
}

/** The gates cleared, carried as one value so [AppUpdateChecker.check] reads as three steps. */
private data class Readiness(
    val projectId: String,
    val apiKey: String,
    val appId: String,
    val installedVersion: String,
)

/**
 * The client-fetch body.
 *
 * `appId` and `appInstanceId` are both required by the API. The instance id is
 * what Firebase buckets percentage rollouts by, which is why it is persisted
 * rather than generated per launch.
 */
@Serializable
internal data class FetchRequest(
    @SerialName("appId") val appId: String,
    @SerialName("appInstanceId") val appInstanceId: String,
    @SerialName("languageCode") val languageCode: String,
)

/**
 * Reads `{"entries": {...}, "state": "..."}` by hand.
 *
 * Hand-parsed rather than modelled as a DTO because every Remote Config value
 * is a string whose *key set* is open — the backend adds keys for other clients
 * and this must not care. An empty or absent map returns null, which the caller
 * reads as "no information".
 *
 * Internal rather than private so the parse is exercised directly by tests
 * instead of being reimplemented by them.
 */
internal fun parseEntries(payload: String): Map<String, String>? {
    val root = runCatching { Json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
    val entries = root["entries"] as? JsonObject ?: return null

    return entries
        .mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }
        .toMap()
        .takeIf { it.isNotEmpty() }
}

/**
 * The value for [key] on this [os]: the platform-suffixed key when the console
 * publishes one with something in it, otherwise the plain key.
 *
 * "Something in it" matters: dev's template carries `desktop_download_url` as
 * the two characters `""`, and a suffixed key left equally empty must fall
 * through to the plain one rather than blank it.
 */
internal fun Map<String, String>.forPlatform(key: String, os: OperatingSystem): String? {
    val suffixed = os.keySuffix?.let { suffix -> this["$key$suffix"] }
    return suffixed?.takeIf { AppVersions.normalise(it).isNotEmpty() } ?: this[key]
}

/** `_mac`, `_windows`, `_linux`; null where no suffix applies. */
internal val OperatingSystem.keySuffix: String?
    get() = when (this) {
        OperatingSystem.MacOs -> "_mac"
        OperatingSystem.Windows -> "_windows"
        OperatingSystem.Linux -> "_linux"
        OperatingSystem.Unknown -> null
    }

/**
 * Accepts a download target, or nothing.
 *
 * `https` only. The value is typed into a remote console by a human and lands
 * in a browser launcher, so a `file:` or custom-scheme URL slipping through
 * would turn a config field into a way to open something local. The app's
 * launcher guards this too (`BrowserLauncher.openInBrowser`); this is the
 * second lock, and it is stricter than the first because a download page for a
 * signed installer has no business being served over plaintext.
 *
 * Quotes are forgiven for the same reason they are on versions: the console
 * gets typed into.
 */
internal fun usableUrl(raw: String?): String? =
    AppVersions.normalise(raw).takeIf { it.startsWith("https://", ignoreCase = true) }

/** `https://firebaseremoteconfig.googleapis.com` — no trailing slash. */
const val FIREBASE_REMOTE_CONFIG_HOST = "https://firebaseremoteconfig.googleapis.com"

/** Newest published desktop build, e.g. `"1.2.0"`. */
const val KEY_LATEST_VERSION = "desktop_latest_version"

/** Builds below this must update to keep working. */
const val KEY_MIN_VERSION = "desktop_min_version"

/** Where the Download action sends the reader; falls back to `app_download_url`. */
const val KEY_DOWNLOAD_URL = "desktop_download_url"

/**
 * How often the app re-asks while it is running.
 *
 * Six hours: a desktop app stays open for days at a time, so a launch-only
 * check would leave someone on a stale build for a week; and Remote Config's
 * own SDK default minimum interval is twelve hours, so this stays well inside
 * anything Firebase considers polite.
 */
const val UPDATE_CHECK_INTERVAL_MILLIS = 6L * 60L * 60L * 1000L
