package com.zillit.desktop.core.remoteconfig

import com.zillit.desktop.core.common.ZillitLog
import kotlinx.coroutines.sync.withLock
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Fetches `GET api/v2/configuration` and decrypts what comes back.
 *
 * Transcribed from `CommonApis.getDeviceConfiguration`, including which fields
 * are encrypted — the Android client decrypts five of them and passes
 * `app_download_url` through untouched.
 */
class RemoteConfigRepositoryImpl(
    private val apiClient: ApiClient,
    private val config: AppConfig,
    private val decrypt: SecretDecryptor,
) : RemoteConfigRepository {

    private val state = MutableStateFlow<RemoteCredentials?>(null)
    override val credentials: StateFlow<RemoteCredentials?> = state.asStateFlow()

    /** One refetch at a time — ten parallel uploads must not mean ten fetches. */
    private val refetch = kotlinx.coroutines.sync.Mutex()

    override suspend fun current(): RemoteCredentials? {
        state.value?.let { return it }
        refetch.withLock {
            state.value?.let { return it }
            refresh()
        }
        return state.value
    }

    override suspend fun refresh(): ZillitResult<RemoteCredentials> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2()}configuration",
            serializer = ConfigDataDto.serializer(),
            // MODELDATA.WITH_PROJECT_USER_ID: device, project and user. Sending
            // a lighter header here returns 401, not a partial payload.
            module = RequestModule.Configuration,
        ).map { dto ->
            dto.toCredentials(decrypt).also {
                state.value = it
                ZillitLog.i(TAG) { "configuration loaded: ${it.describeAvailability()}" }
            }
        }

    override fun clear() {
        state.value = null
    }

    private companion object {
        const val TAG = "RemoteConfig"
    }
}

/**
 * Maps the payload to [RemoteCredentials], decrypting what the server encrypts.
 *
 * A top-level function rather than a private method so tests exercise the real
 * mapping — a test that reimplements it would pass whatever the test happens to
 * do, including the wrong thing.
 */
internal fun ConfigDataDto.toCredentials(decrypt: SecretDecryptor): RemoteCredentials {
    /**
     * A failure yields null, never the ciphertext. The Android version returns
     * the input unchanged on failure (`EncrytionDecryption:150`), which hands a
     * hex blob to the Maps SDK as though it were an API key — the error then
     * surfaces from inside a third-party library with no hint of its origin.
     */
    fun field(name: String, cipherHex: String?): String? {
        if (cipherHex.isNullOrBlank()) return null

        return when (val result = decrypt.decryptFromHex(cipherHex)) {
            is ZillitResult.Success -> result.data.takeIf { it.isNotBlank() }
            is ZillitResult.Failure -> {
                // Which field, but never the payload — so a wrong key is
                // diagnosable without writing ciphertext to the log.
                ZillitLog.w(LOG_TAG) { "could not decrypt $name: ${result.error.technical}" }
                null
            }
        }
    }

    return RemoteCredentials(
        googleMapsKey = field("google_map_key", googleMapKey),
        googlePlacesKey = field("places_secret", placesSecret),
        awsAccessKey = field("aws_access_key", awsAccessKey),
        awsSecretKey = field("aws_secret_key", awsSecretKey),
        chatGptTranslationToken = field("chat_gpt_translation_token", chatGptTranslationToken),
        // Passed through: Android does not decrypt this one, and it is a public
        // URL rather than a credential.
        appDownloadUrl = appDownloadUrl?.takeIf { it.isNotBlank() },
    )
}

private const val LOG_TAG = "RemoteConfig"

/**
 * Which credentials arrived, without naming any value.
 *
 * "Maps failed" and "Maps was never issued a key" look identical from a stack
 * trace, so this is worth one log line.
 */
internal fun RemoteCredentials.describeAvailability(): String = listOf(
    "maps" to googleMapsKey,
    "places" to googlePlacesKey,
    "aws" to awsSecretKey,
    "translation" to chatGptTranslationToken,
).joinToString(", ") { (name, value) -> "$name=${if (value != null) "yes" else "no"}" }

/**
 * The decryption this module needs, narrowed to one method.
 *
 * Mirrors `HeaderCrypto`: the repository can decrypt a payload and cannot reach
 * the keychain behind it.
 */
fun interface SecretDecryptor {
    fun decryptFromHex(cipherHex: String): ZillitResult<String>
}

/**
 * The `data` object, matching Android's `ConfigData` field for field.
 *
 * `box_client_id` and `box_client_secret` are in the Android DTO but read by
 * nothing there; they are omitted rather than modelled as fields no caller can
 * explain. Unknown keys are ignored by the shared `Json`, so adding them later
 * is not a breaking change.
 */
@Serializable
internal data class ConfigDataDto(
    @SerialName("google_map_key") val googleMapKey: String? = null,
    @SerialName("places_secret") val placesSecret: String? = null,
    @SerialName("aws_access_key") val awsAccessKey: String? = null,
    @SerialName("aws_secret_key") val awsSecretKey: String? = null,
    @SerialName("chat_gpt_translation_token") val chatGptTranslationToken: String? = null,
    @SerialName("app_download_url") val appDownloadUrl: String? = null,
)
