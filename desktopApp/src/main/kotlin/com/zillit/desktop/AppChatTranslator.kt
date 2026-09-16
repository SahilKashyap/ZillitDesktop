package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.flatMap
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.HttpVerb
import com.zillit.desktop.core.network.RequestModule
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.ChatTranslator
import com.zillit.desktop.feature.email.domain.StoredFile
import com.zillit.desktop.feature.email.ui.EmailViewModel
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The host halves of the chat bubble menu's Translate and Share.
 *
 * Translate is the web's `callChatGPT` (`api/projectapi/projectApi.js:21-37`):
 * `POST {integrations}/v2/chat-gpt/translate` with `{text, lang}`, the
 * translation answered in the envelope's `data`. The gate — production
 * language against this computer's — reads the production's
 * `language_code` (English when unset, as the web's `getProjectLanguage`
 * falls back) and the app's own UI language.
 */
internal class AppChatTranslator(
    private val ready: AppGraph.Ready,
    /** The app's UI language as it changes; the OS language until the preference is read. */
    private val uiLanguage: StateFlow<String>,
) : ChatTranslator {

    override fun projectLanguage(): String =
        ready.projectContext?.context?.value?.project?.languageCode?.takeIf { it.isNotBlank() } ?: "en"

    override fun deviceLanguage(): String =
        uiLanguage.value.ifBlank { java.util.Locale.getDefault().language }.substringBefore('-').ifBlank { "en" }

    override suspend fun translate(text: String, toLanguage: String): ZillitResult<String> =
        ready.apiClient.request(
            verb = HttpVerb.Post,
            url = "${ready.config.apiV2(ZillitService.Integrations)}chat-gpt/translate",
            serializer = JsonElement.serializer(),
            module = RequestModule.ProjectUser,
            body = buildJsonObject {
                put("text", text)
                put("lang", toLanguage)
            },
        ).flatMap { data ->
            // The answer is the translated string itself; a wrapped shape
            // (`{text}` / `{translation}`) is read too, in case the service
            // ever grows one.
            val words = (data as? JsonPrimitive)?.contentOrNull
                ?: (data as? kotlinx.serialization.json.JsonObject)?.let { obj ->
                    listOf("translated_text", "translation", "text", "data")
                        .firstNotNullOfOrNull { key -> (obj[key] as? JsonPrimitive)?.contentOrNull }
                }
            if (words.isNullOrBlank()) {
                ZillitResult.Failure(ZillitError.Unknown(TRANSLATION_FAILED))
            } else {
                ZillitResult.Success(words)
            }
        }

    private companion object {
        /** The web's own sentence (`cncUtil.js:1109`). */
        const val TRANSLATION_FAILED = "Translation failed"
    }
}

/**
 * The app's language preference, followed — the same rule `trackUiLanguage`
 * applies for the label dictionaries: the chosen language, else the OS's.
 */
internal fun CoroutineScope.chatUiLanguage(preferences: PreferenceStore): StateFlow<String> {
    val language = MutableStateFlow(java.util.Locale.getDefault().language.ifBlank { "en" })
    launch {
        preferences.observe(ZillitPreferences.Language).collect { chosen ->
            language.value = chosen.ifBlank { java.util.Locale.getDefault().language.ifBlank { "en" } }
        }
    }
    return language
}

/**
 * The menu's Share — the web's `shareMessagesAsEmail`
 * (`utils/helpers/share-messages-as-email.js`): the line's words as the
 * body, its file attached as the stored object it already is, a place as
 * a Google Maps link; refused with the web's `email_id_not_available` when
 * the user has no Zillit mailbox. Answers the refusal, or null once the
 * request is queued and the mailbox window opened.
 */
internal fun AppGraph.Ready.shareChatMessageAsEmail(
    mail: EmailViewModel?,
    message: ChatMessage,
    navigator: WindowNavigator,
): String? {
    val mailbox = projectContext?.context?.value?.profile?.mailboxAddress
    if (mail == null || mailbox.isNullOrBlank()) return EMAIL_NOT_AVAILABLE
    mail.composeRequests.post(
        addressedTo = "",
        bodyHtml = chatMessageAsEmailHtml(message),
        attachments = listOfNotNull(
            message.attachment?.takeIf { message.location == null && it.media.isNotBlank() }?.let { file ->
                StoredFile(
                    media = file.media,
                    bucket = file.bucket,
                    region = file.region,
                    fileName = file.name,
                    contentType = file.contentType,
                    sizeBytes = 0L,
                )
            },
        ),
    )
    navigator.openInNewWindow(WorkspaceRoute.Tool(EMAIL_TOOL_ROUTE))
    return null
}

/** `buildEmailFromMessages` for one message: words with `<br>` for newlines, a place as its link. */
internal fun chatMessageAsEmailHtml(message: ChatMessage): String {
    val place = message.location
    if (place != null) {
        val label = message.body.ifBlank { "${place.lat}, ${place.lng}" }.escapeHtml()
        return """<a href="${place.mapsUrl}" target="_blank">$label</a>"""
    }
    return message.body.escapeHtml().replace("\n", "<br>")
}

private fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** The web's `email_id_not_available` (`utils/language/en.js:6608`). */
private const val EMAIL_NOT_AVAILABLE = "Email-ID is not available"
private const val EMAIL_TOOL_ROUTE = "/email"
