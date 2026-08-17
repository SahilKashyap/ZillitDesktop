package com.zillit.desktop.core.localization

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Fetches one translated dictionary. */
fun interface LabelSource {
    suspend fun fetch(kind: LabelKind, language: String): ZillitResult<Map<String, String>>
}

/**
 * `GET preset/labels | preset/messages | preset/identifiers`, each `?lang=`.
 *
 * All three are unauthenticated reference data carrying the device header only,
 * like the rest of the `preset` namespace — which is what lets the sign-in
 * screen be localised before there is a session to localise it for.
 *
 * The same three calls exist in every client: Android fires them together from
 * `CommonApis.getLanguages`, iOS from `MessagePresetManger.saveMessagePresets`,
 * and the web through `presetsApi`. Only the endpoint set is shared — how the
 * result is stored and looked up differs in all three, and this follows
 * Android's, because it is the only one that keeps the dictionaries distinct.
 */
class PresetLabelSource(
    private val apiClient: ApiClient,
    private val config: AppConfig,
) : LabelSource {

    override suspend fun fetch(kind: LabelKind, language: String): ZillitResult<Map<String, String>> =
        apiClient.request(
            verb = HttpVerb.Get,
            url = "${config.apiV2(ZillitService.Core)}preset/${kind.path}",
            serializer = JsonObject.serializer(),
            module = RequestModule.Device,
            queryParameters = mapOf("lang" to language),
        ).map { it.toTranslations() }
}

/**
 * Reads the `data` object as `key -> text`, tolerating what is not text.
 *
 * The dictionaries are flat string maps in practice, but the envelope's `data`
 * is untyped and a single number or nested object anywhere in several thousand
 * keys would fail a `Map<String, String>` decode and lose the whole language.
 * Non-primitives are dropped and everything else is taken as its content — the
 * same tolerance Android gets from holding a `JsonObject` and stringifying on
 * read, without Android's habit of leaving the quotes on.
 */
private fun JsonObject.toTranslations(): Map<String, String> =
    entries.mapNotNull { (key, element) ->
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        text?.let { key to it }
    }.toMap()
