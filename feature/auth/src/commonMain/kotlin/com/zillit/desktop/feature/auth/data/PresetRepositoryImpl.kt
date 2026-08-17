package com.zillit.desktop.feature.auth.data

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.feature.auth.domain.PresetRepository
import com.zillit.desktop.feature.auth.domain.ProductionLanguage
import com.zillit.desktop.feature.auth.domain.ProductionType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * The reference lists the create-production form needs.
 *
 * `GET preset/project-types` and `GET preset/languages?lang=`. Both are
 * unauthenticated reference data — they carry the device header only, as on the
 * web, where the whole `preset` namespace sits outside the project-scoped API.
 *
 * (Do not write a `preset` wildcard path in a KDoc here: Kotlin block comments
 * nest, so a slash-star inside one opens a comment that the next star-slash
 * closes, leaving the doc unterminated.)
 */
class PresetRepositoryImpl(
    private val apiClient: ApiClient,
    config: AppConfig,
    /** The UI language to localise preset labels into. */
    private val languageCode: () -> String,
) : PresetRepository {

    private val endpoints = AuthEndpoints(config)

    override suspend fun productionTypes(): ZillitResult<List<ProductionType>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = endpoints.projectTypes,
            serializer = ListSerializer(ProductionTypeDto.serializer()),
            module = RequestModule.Device,
        ).map { dtos -> dtos.mapNotNull { it.toDomain() } }

    override suspend fun languages(): ZillitResult<List<ProductionLanguage>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = endpoints.languages,
            serializer = ListSerializer(LanguageDto.serializer()),
            module = RequestModule.Device,
            queryParameters = mapOf("lang" to languageCode()),
        ).map { dtos ->
            // Sorted here rather than in the UI: the server returns them in
            // insertion order, and a language picker that is not alphabetical is
            // unusable at 100+ entries.
            dtos.mapNotNull { it.toDomain() }.sortedBy { it.name.lowercase() }
        }
}

@Serializable
internal data class ProductionTypeDto(
    @SerialName("_id") val id: String? = null,
    @SerialName("project_type") val projectType: String? = null,
    @SerialName("sub_types") val subTypes: List<String>? = null,
) {
    fun toDomain(): ProductionType? {
        val resolvedId = id?.takeIf { it.isNotBlank() } ?: return null
        return ProductionType(
            id = resolvedId,
            label = (projectType?.takeIf { it.isNotBlank() } ?: resolvedId).humanise(),
            subTypes = subTypes?.filter { it.isNotBlank() }.orEmpty(),
        )
    }
}

@Serializable
internal data class LanguageDto(
    @SerialName("language_code") val code: String? = null,
    @SerialName("name") val name: String? = null,
) {
    fun toDomain(): ProductionLanguage? {
        val resolvedCode = code?.takeIf { it.isNotBlank() } ?: return null
        return ProductionLanguage(code = resolvedCode, name = name?.takeIf { it.isNotBlank() } ?: resolvedCode)
    }
}

/**
 * Preset labels are translation keys (`entertainment_industry_label`), not
 * display names, so they are resolved against the label dictionary the same way
 * unit and tool names are.
 *
 * Kept as a named function rather than inlined at the two call sites because
 * production types are one of the few lists shown *before* sign-in, where the
 * dictionary may still be arriving — this is the line to look at when one of
 * them reads as a raw key.
 */
internal fun String.humanise(): String = localised()
