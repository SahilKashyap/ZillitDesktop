package com.zillit.desktop.core.network

import com.zillit.desktop.core.common.ZillitLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Builds the shared Ktor client.
 *
 * Deliberate differences from the Android client:
 *
 *  - **No custom SSL configuration at all.** The platform trust store is used as
 *    supplied. The Android version installs a trust-all `X509TrustManager` and a
 *    permissive hostname verifier for every build type (plan §8.1); nothing here
 *    may reintroduce that, and `scripts/security-scan.sh` fails the build if
 *    anyone tries.
 *  - **Logging is redacted and off by default in release.** The Android version
 *    runs at `LogLevel.ALL`, writing bearer tokens and message bodies to logcat.
 */
object HttpClientFactory {

    /**
     * `ignoreUnknownKeys` tolerates server fields we do not model.
     * `coerceInputValues` maps an unexpected `null` onto the declared default
     * instead of throwing — the Android client added this after single nulls
     * (`userPresetId`, `location`, `callType`) were failing whole list parses
     * and silently emptying screens.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    /**
     * @param logBodies include request and response bodies in the log.
     *   **Opt-in, never in production**, and the caller is responsible for that
     *   gate — see `AppGraph`. Bodies carry PII, financials and message content
     *   (plan §8.4), so this exists for a developer chasing one call, not as a
     *   setting anyone should leave on. Everything still passes through
     *   `ZillitLog.redact`.
     */
    fun create(
        engineFactory: HttpClientEngineProvider,
        verboseLogging: Boolean = false,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        logBodies: Boolean = false,
    ): HttpClient = HttpClient(engineFactory.engine()) {
        expectSuccess = false

        install(ContentNegotiation) { json(this@HttpClientFactory.json) }

        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMillis
            connectTimeoutMillis = timeoutMillis
            socketTimeoutMillis = timeoutMillis
        }

        if (verboseLogging) {
            install(Logging) {
                logger = object : Logger {
                    override fun log(message: String) {
                        ZillitLog.d(TAG) { message }
                    }
                }
                // HEADERS by default: bodies carry PII, financials and message
                // content (plan §8.4). BODY only when explicitly asked for, and
                // only outside production.
                level = if (logBodies) LogLevel.ALL else LogLevel.HEADERS
                sanitizeHeader { header -> header.equals("Authorization", ignoreCase = true) }
            }
        }
    }

    const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    private const val TAG = "Http"
}
