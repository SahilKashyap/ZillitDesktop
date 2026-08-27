package com.zillit.desktop.core.config

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult

/**
 * Resolves [AppConfig] for the running process.
 *
 * Endpoints come from an external properties file rather than the binary. The
 * file uses the same `<PREFIX>_<SERVICE>_BASE_URL` key shape as the Android
 * app's `local.properties`, so one file can serve both clients and there is no
 * second format to keep in sync.
 */
interface ConfigLoader {
    fun load(): ZillitResult<AppConfig>
}

/**
 * Parses a flat `key=value` map into [AppConfig].
 *
 * Kept free of file I/O so it is testable in `commonTest`; the platform loader
 * supplies the map.
 */
object ConfigParser {

    fun parse(
        environment: Environment,
        properties: Map<String, String>,
        featureFlags: FeatureFlags = FeatureFlags(),
    ): ZillitResult<AppConfig> {
        val prefix = "${environment.propertyPrefix}_"

        val declared = ZillitService.entries.mapNotNull { service ->
            properties[prefix + service.configKey]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { service to it }
        }.toMap()

        // Thirteen modules moved to four consolidated hosts; see
        // [ConsolidatedHosts]. Android decides this per call from a Firebase
        // flag because its BuildConfig hosts are compile-time constants. Here
        // every host resolves through one map, so the swap happens once.
        //
        // The default is ON, which is where this deliberately parts company
        // with Android's fail-closed default: theirs protects a fresh install
        // whose old hosts still answer, while ours are decommissioned — twelve
        // of the thirteen returned 502 on 2026-08-27. Failing closed here
        // would mean shipping an app that reaches nothing. Set the key to
        // false to roll back without a release.
        val useConsolidated = properties[prefix + USE_NEW_URL_SUFFIX]
            ?.trim()
            ?.lowercase()
            ?.let { it != "false" && it != "0" && it != "no" }
            ?: true

        val services = if (useConsolidated) ConsolidatedHosts.applied(declared) else declared

        val realtime = ZillitRealtimeEndpoint.entries.mapNotNull { endpoint ->
            properties[prefix + endpoint.configKey]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { endpoint to it }
        }.toMap()

        // `PROD_ENCRYPTION_KEY` / `PROD_IV_ENCRYPTION_KEY`, matching the Android
        // `local.properties` names so one file serves both clients. Absent is
        // allowed: the app then falls back to keychain-entered values.
        val headerKey = headerKeyFrom(properties, prefix)
        val firebase = firebaseFrom(properties, prefix)
        val agoraAppId = properties[prefix + AGORA_APP_ID_SUFFIX]?.trim()?.takeIf { it.isNotEmpty() }
        val weatherApiKey = properties[prefix + WEATHER_API_KEY_SUFFIX]?.trim()?.takeIf { it.isNotEmpty() }

        if (!services.containsKey(ZillitService.Core)) {
            return ZillitResult.Failure(
                ZillitError.Validation(
                    userMessage = "Zillit is not configured and cannot start.",
                    technical = "Missing required key ${prefix}${ZillitService.Core.configKey}",
                ),
            )
        }

        return AppConfig(
            environment = environment,
            services = services,
            realtime = realtime,
            featureFlags = featureFlags,
            headerKey = headerKey,
            firebase = firebase,
            agoraAppId = agoraAppId,
            weatherApiKey = weatherApiKey,
        ).validate()
    }

    /**
     * Reads the header key/IV, if the file carries them.
     *
     * A wrong-length pair is dropped rather than returned, so the app falls back
     * to the keychain instead of encrypting with material the server cannot
     * decrypt — which surfaces as a 401 on every call and looks like anything
     * but a typo in a config file. [ConfigLoader] logs the drop.
     */
    private fun headerKeyFrom(properties: Map<String, String>, prefix: String): HeaderKeyMaterial? {
        val key = properties[prefix + HeaderKeyMaterial.KEY_SUFFIX]?.trim().orEmpty()
        val iv = properties[prefix + HeaderKeyMaterial.IV_SUFFIX]?.trim().orEmpty()
        if (key.isEmpty() || iv.isEmpty()) return null

        return HeaderKeyMaterial(key, iv).takeIf { it.isCanonical }
    }

    /**
     * Both halves or neither: a project id without its key cannot call Firestore.
     *
     * The app id is a third, *optional* value on top of that pair — only the
     * Remote Config fetch needs it (see `AppUpdateChecker`), and requiring it
     * would switch calling's Firestore mirror off on every install configured
     * before it existed.
     */
    private fun firebaseFrom(properties: Map<String, String>, prefix: String): FirebaseConfig? {
        val projectId = properties[prefix + FirebaseConfig.PROJECT_ID_SUFFIX]?.trim().orEmpty()
        val apiKey = properties[prefix + FirebaseConfig.API_KEY_SUFFIX]?.trim().orEmpty()
        if (projectId.isEmpty() || apiKey.isEmpty()) return null

        val appId = properties[prefix + FirebaseConfig.APP_ID_SUFFIX]?.trim()?.takeIf { it.isNotEmpty() }
        return FirebaseConfig(projectId, apiKey, appId)
    }

    /**
     * Whether the file carried a key/IV pair that was rejected for its length.
     *
     * Separate from [headerKeyFrom] so the caller can tell "no key configured"
     * from "a key was configured and is wrong" — silence on the second would be
     * the worst outcome.
     */
    fun headerKeyIsMalformed(properties: Map<String, String>, environment: Environment): Boolean {
        val prefix = "${environment.propertyPrefix}_"
        val key = properties[prefix + HeaderKeyMaterial.KEY_SUFFIX]?.trim().orEmpty()
        val iv = properties[prefix + HeaderKeyMaterial.IV_SUFFIX]?.trim().orEmpty()
        if (key.isEmpty() && iv.isEmpty()) return false

        return !HeaderKeyMaterial(key, iv).isCanonical
    }

    /**
     * Splits a `.properties` payload.
     *
     * Delegates to [PropertiesParser], which handles Java's escaping rules —
     * real Zillit config files escape colons (`https\://host`), and a naive
     * split leaves the backslash in the value where it fails the TLS check.
     */
    fun parseProperties(raw: String): Map<String, String> = PropertiesParser.parse(raw)

    /** `<ENV>_WEATHER_API_KEY` — the Weather tool's OpenWeatherMap key. */
    private const val WEATHER_API_KEY_SUFFIX = "WEATHER_API_KEY"

    private const val AGORA_APP_ID_SUFFIX = "AGORA_APP_ID"

    /**
     * `<ENV>_USE_NEW_URL` — the consolidated-host switch, Android's
     * `use_new_url` Remote Config flag by another route. Absent means on;
     * see the note at the call site for why the default differs from
     * Android's.
     */
    private const val USE_NEW_URL_SUFFIX = "USE_NEW_URL"
}
