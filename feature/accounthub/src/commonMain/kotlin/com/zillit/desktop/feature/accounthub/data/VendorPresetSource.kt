package com.zillit.desktop.feature.accounthub.data

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.accounthub.domain.IsdCountry
import com.zillit.desktop.feature.accounthub.domain.PostcodePlace
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonNull

/**
 * The two preset catalogues the vendor form reads: the countries, and the
 * postcode lookup that fills in a city and a county.
 *
 * Both live on the main project service beside `taxes-by-country`, not on the
 * account-hub host.
 */
internal class VendorPresetSource(private val apiClient: ApiClient, config: AppConfig) {

    private val presetBase = "${config.apiV2(ZillitService.Core)}preset"

    /** `GET /v2/preset/isd-codes`, the web's `presetsApi.getIsdCodes`. */
    suspend fun isdCodes(): ZillitResult<List<IsdCountry>> =
        confirmedRows("$presetBase/isd-codes", IsdCodeDto.serializer())
            .map { rows -> rows.mapNotNull { it.toDomain() } }

    /**
     * `GET /v2/preset/geonames/postalcode/{country}/{postcode}`, the web's
     * `lookupPostcode`.
     *
     * The first match's place, or an empty one when the service found none.
     * An empty answer is still an answer: the form writes it, so a postcode
     * with no place does not keep the previous postcode's city (ZL-20356).
     */
    suspend fun postcodePlace(countryCode: String, postcode: String): ZillitResult<PostcodePlace> =
        confirmedRows(
            "$presetBase/geonames/postalcode/${countryCode.pathSegment()}/${postcode.pathSegment()}",
            PostcodePlaceDto.serializer(),
        ).map { rows -> rows.firstOrNull()?.toDomain() ?: PostcodePlace() }

    /**
     * A preset list, from a confirmed answer only.
     *
     * The client hands back any 200 as a success whatever its `status` says,
     * and a refusal carries no `data`. Read through `requestOrNull`, a failed
     * postcode lookup looked exactly like "no place found" and wiped the city
     * it should have left alone. The web writes a place only inside
     * `status === 1`; so does this.
     */
    private suspend fun <T> confirmedRows(url: String, row: KSerializer<T>): ZillitResult<List<T>> =
        apiClient.envelope(verb = HttpVerb.Get, url = url, module = RequestModule.Device).flatMap { envelope ->
            val data = envelope.data
            when {
                envelope.status != STATUS_CONFIRMED ->
                    ZillitResult.Failure(ZillitError.Http(status = HTTP_OK, serverMessage = envelope.message))
                data == null || data is JsonNull -> ZillitResult.Success(emptyList())
                else -> runCatching { accountHubJson.decodeFromJsonElement(ListSerializer(row), data) }.fold(
                    onSuccess = { ZillitResult.Success(it) },
                    onFailure = { ZillitResult.Failure(ZillitError.Serialization(it.message)) },
                )
            }
        }
}

@Serializable
internal data class IsdCodeDto(
    @SerialName("name") val name: String? = null,
    @SerialName("dial_code") val dialCode: String? = null,
    @SerialName("code") val code: String? = null,
) {
    /** A row with no name cannot be picked, so it is not offered. */
    fun toDomain(): IsdCountry? {
        val country = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return IsdCountry(name = country, dialCode = dialCode?.trim().orEmpty(), code = code?.trim().orEmpty())
    }
}

@Serializable
internal data class PostcodePlaceDto(
    @SerialName("city") val city: String? = null,
    @SerialName("state") val state: String? = null,
) {
    fun toDomain() = PostcodePlace(city = city?.trim().orEmpty(), state = state?.trim().orEmpty())
}

/**
 * One URL path segment, percent-encoded.
 *
 * A UK postcode carries a space ("SL0 0NH"), and a `/` typed into the field
 * would otherwise split the route in two.
 */
private fun String.pathSegment(): String = buildString {
    for (byte in this@pathSegment.trim().encodeToByteArray()) {
        val char = byte.toInt().toChar()
        if (byte >= 0 && (char.isLetterOrDigit() || char in UNRESERVED)) {
            append(char)
        } else {
            append('%').append(HEX[(byte.toInt() shr NIBBLE_BITS) and NIBBLE]).append(HEX[byte.toInt() and NIBBLE])
        }
    }
}

private const val STATUS_CONFIRMED = 1
private const val UNRESERVED = "-._~"
private const val HEX = "0123456789ABCDEF"
private const val NIBBLE_BITS = 4
private const val NIBBLE = 0xF
