package com.zillit.desktop.feature.sos.domain

import com.zillit.desktop.core.common.MessageElement
import com.zillit.desktop.core.localization.LabelKind
import com.zillit.desktop.core.localization.Labels

/**
 * An SOS alert's sentence from its wire parts.
 *
 * The web's `messageWithReplacements` (`multipleFunction.js:1698-1714`):
 * the message is a label key, its `{{slots}}` are filled from the elements
 * with the replacer verbatim, then links are made clickable. Android's
 * `ReceivedSosHolder` (`bottomNav/sos/adapter/holder/ReceivedSosHolder.kt:26-33`)
 * runs the same key through `decodeSubLevelNotification`, which also turns
 * any `{single_brace}` left over into a label. (Android shows the raw message
 * when there are no elements at all; the web translates regardless, and so
 * does this — a key with nothing to fill is still a key.)
 *
 * A deliberately small twin of the notifications module's decoder — SOS
 * bodies are never encrypted and never carry an event time, so the two
 * modules stay independent rather than one depending on the other for forty
 * lines.
 */
class SosTextDecoder(
    private val translate: (key: String) -> String = { Labels.translate(it, LabelKind.Messages) },
) {

    fun text(message: String, elements: List<MessageElement>): String {
        val key = message.trim()
        if (key.isEmpty()) return ""
        val filled = elements.fold(translate(key)) { text, element ->
            val search = element.search?.takeIf { it.isNotEmpty() } ?: return@fold text
            text.replace(search, element.replacer.orEmpty())
        }
        val labelled = SINGLE_BRACE.replace(filled) { match -> translate(match.groupValues[1]) }
        return labelled.replace("\\", "\"").stripTags().trim()
    }

    private fun String.stripTags(): String {
        if (!contains('<') && !contains('&')) return this
        return replace(BREAK_TAG, "\n")
            .replace(ANY_TAG, "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
    }

    private companion object {
        val SINGLE_BRACE = Regex("""\{(.*?)}""")
        val BREAK_TAG = Regex("""(?i)<br\s*/?>|</p>|</div>""")
        val ANY_TAG = Regex("""<[^>]*>""")
    }
}
