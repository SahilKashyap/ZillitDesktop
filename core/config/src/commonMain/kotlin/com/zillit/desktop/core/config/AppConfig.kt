package com.zillit.desktop.core.config

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult

/**
 * Runtime configuration, replacing the Android app's three product flavors and
 * ~800 lines of `buildConfigField`.
 *
 * ## The header key lives here, and that is a known compromise
 *
 * [headerKey] carries the AES key/IV for the `moduledata` + `bodyhash` scheme.
 * The Android app compiles the same pair into `BuildConfig` from
 * `local.properties`; this reads it from `zillit.properties` so one file serves
 * both clients.
 *
 * It is **not** secure, and nothing here should pretend otherwise:
 *
 *  - the key is shared by every install, so anyone holding it can forge headers
 *    for any device;
 *  - a desktop JAR decompiles more easily than a minified APK, so shipping it
 *    beside the app exposes it more than Android does.
 *
 * It is done this way because the backend requires the scheme and there is no
 * endpoint that serves the key — `GET api/v2/configuration` returns the *other*
 * secrets (Maps, AWS, ChatGPT, Box) and is itself called with headers built from
 * this key, so it cannot bootstrap it. Per-device request signing (plan §8.3)
 * removes the shared key; until then this is transport obfuscation, not auth.
 *
 * Everything else here is hostnames and flags — values already visible in
 * network traffic.
 */
data class AppConfig(
    val environment: Environment,
    val services: Map<ZillitService, String>,
    val realtime: Map<ZillitRealtimeEndpoint, String>,
    val featureFlags: FeatureFlags = FeatureFlags(),
    /** Null when the file omits the key/IV; the app then falls back to the keychain. */
    val headerKey: HeaderKeyMaterial? = null,
    /** Null when the file omits the Firebase pair; calling then runs socket-only. */
    val firebase: FirebaseConfig? = null,
    /**
     * The Agora application id — the media engine's project address, the same
     * value Android carries in `local.properties` as `AGORA_APP_ID`. Null
     * leaves calling signalling-only. An identifier, not a credential (the
     * channel token is the credential), but kept out of logs like every key.
     */
    val agoraAppId: String? = null,
) {

    /**
     * Redacted, because an [AppConfig] reaches log lines and crash reports.
     *
     * The generated `toString` would print the key in full. `HeaderKeyMaterial`
     * redacts itself too — both, so neither is the single point of failure.
     */
    override fun toString(): String =
        "AppConfig(environment=${environment.id}, services=${services.size}, " +
            "realtime=${realtime.size}, featureFlags=$featureFlags, " +
            "headerKey=${if (headerKey == null) "absent" else "present"}, " +
            "firebase=${if (firebase == null) "absent" else "present"}, " +
            "agora=${if (agoraAppId == null) "absent" else "present"})"

    /**
     * Base URL for a service.
     *
     * Throws rather than returning a default: a missing endpoint is a
     * deployment error, and silently falling back to the core host would send
     * requests somewhere they do not belong.
     */
    fun baseUrl(service: ZillitService): String =
        requireNotNull(services[service]) {
            "No endpoint configured for ${service.name} (${service.configKey}) in ${environment.id}"
        }.trimEnd('/')

    /** `<service>/api/v2/` — the path prefix every REST call shares. */
    fun apiV2(service: ZillitService = ZillitService.Core): String = "${baseUrl(service)}/api/v2/"

    fun realtimeUrl(endpoint: ZillitRealtimeEndpoint): String =
        requireNotNull(realtime[endpoint]) {
            "No realtime endpoint configured for ${endpoint.name} in ${environment.id}"
        }

    /**
     * Rejects any cleartext or unvalidatable endpoint before a request is ever
     * made (plan §8.2). Called by [ConfigLoader] at startup so a misconfigured
     * deployment fails loudly at launch, not silently mid-session.
     */
    fun validate(): ZillitResult<AppConfig> {
        val offenders = buildList {
            services.forEach { (service, url) ->
                if (!url.startsWith("https://")) add("${service.configKey}=$url")
            }
            realtime.forEach { (endpoint, url) ->
                if (!url.startsWith("wss://") && !url.startsWith("https://")) {
                    add("${endpoint.configKey}=$url")
                }
            }
        }
        return if (offenders.isEmpty()) {
            ZillitResult.Success(this)
        } else {
            ZillitResult.Failure(
                ZillitError.Validation(
                    userMessage = "Zillit is not configured correctly and cannot start securely.",
                    technical = "Non-TLS endpoints configured: ${offenders.joinToString()}",
                ),
            )
        }
    }
}

/**
 * The AES key and IV for the legacy header scheme.
 *
 * A dedicated type rather than two `String` fields on [AppConfig] so that
 * "this is the secret" is visible at every use site, and so [toString] can be
 * overridden in one place.
 *
 * Not held as `ByteArray` and zeroed the way `CryptoKeyMaterial` is: these come
 * from a file that stays on disk for the process lifetime, so scrubbing the heap
 * copy would protect nothing while making the value look better guarded than it
 * is.
 */
data class HeaderKeyMaterial(val key: String, val iv: String) {

    /** True when encrypt and decrypt derive the same bytes — see `CryptoKeyMaterial`. */
    val isCanonical: Boolean get() = key.length == KEY_LENGTH && iv.length == IV_LENGTH

    /** Never prints the key: an [AppConfig] can reach a log line or a crash report. */
    override fun toString(): String = "HeaderKeyMaterial(key=***, iv=***)"

    companion object {
        const val KEY_LENGTH = 32
        const val IV_LENGTH = 16

        const val KEY_SUFFIX = "ENCRYPTION_KEY"
        const val IV_SUFFIX = "IV_ENCRYPTION_KEY"
    }
}

enum class Environment(val id: String, val propertyPrefix: String) {
    Develop("develop", "STG"),
    Qa("qa", "QA"),
    Production("prod", "PROD"),
    ;

    val isProduction: Boolean get() = this == Production

    companion object {
        /** Unknown or absent input resolves to production — fail closed. */
        fun fromId(id: String?): Environment =
            entries.firstOrNull { it.id.equals(id?.trim(), ignoreCase = true) } ?: Production
    }
}

/**
 * Remotely-togglable behaviour, mirroring the web app's Remote Config gates.
 *
 * `mdiEnabled` is the significant one: it turns the multi-window workspace on
 * per project (plan §3.1), and when off the app falls back to single-window
 * navigation — same contract as the web `mdi_windows_projectid` allowlist.
 */
data class FeatureFlags(
    val mdiEnabled: Boolean = true,
    val callingEnabled: Boolean = false,
    val darkThemeEnabled: Boolean = true,
)

/**
 * The Firestore status plane's address — Android's `google-services.json`
 * pair, as two properties so one file shape serves every client.
 *
 * The API key is a project identifier, not a credential (Firebase's own
 * docs say so; access control lives in security rules), but it is still
 * kept out of logs the way every key here is.
 */
data class FirebaseConfig(
    val projectId: String,
    val apiKey: String,
) {
    override fun toString(): String = "FirebaseConfig(projectId=$projectId, apiKey=present)"

    companion object {
        const val PROJECT_ID_SUFFIX = "FIREBASE_PROJECT_ID"
        const val API_KEY_SUFFIX = "FIREBASE_API_KEY"
    }
}
