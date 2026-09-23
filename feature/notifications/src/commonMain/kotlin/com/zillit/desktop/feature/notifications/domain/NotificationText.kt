package com.zillit.desktop.feature.notifications.domain

import com.zillit.desktop.core.common.MessageElement
import com.zillit.desktop.core.localization.LabelKind
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The raw text-bearing fields of one notification row, before decoding.
 *
 * `reference_data.encrypted` is the push payload's flag; the list rows rarely
 * carry it and Android infers "encrypted" from the absence of substitutions
 * instead (`NotificationAdapter.onBind`, lines 46-54). Both signals are read.
 */
data class NotificationWireText(
    val message: String,
    val action: String = "",
    val path: String = "",
    val elements: List<MessageElement> = emptyList(),
    val encrypted: Boolean = false,
)

/**
 * The dictionary, as the decoder needs it.
 *
 * Two reads, because Android's `String.getDataFromLabelKey(isFromLabel)` has
 * two orders: labels-first for a path or an identifier, messages-first for
 * a message body. And [exact], because a substitution's value is a name or a
 * count more often than a key — a miss must come back verbatim, not
 * humanised into wording the server never sent.
 */
interface NotificationLabels {
    fun translate(key: String, preferMessages: Boolean): String
    fun exact(key: String): String?
}

/** The app's loaded dictionary. Uninstalled it humanises keys, which is what tests see. */
object DictionaryNotificationLabels : NotificationLabels {
    override fun translate(key: String, preferMessages: Boolean): String =
        Labels.translate(key, if (preferMessages) LabelKind.Messages else LabelKind.Labels)

    override fun exact(key: String): String? = Labels.current.exact(key, LabelKind.Messages)
}

/**
 * Turns a wire row into the sentence Android shows.
 *
 * A port of `NotificationAdapter.onBind` (`bottomNav/common/adapter/
 * NotificationAdapter.kt:30-57`) and the helper it leans on,
 * `NotificationHelper.decodeSubLevelNotification` (`utils/NotificationHelper.kt:
 * 210-256`), with the web's `renderNotificationMessage` as the second witness:
 *
 *  1. **Access-grant actions** (`Constants.kt:1532-1538`) are prefixed with the
 *     last segment of the path — the tool or unit the right was granted on —
 *     because their message text alone (`access granted`) says nothing.
 *  2. Otherwise the message is a **label key** whose text has `{{slots}}`
 *     filled from `messageElements`; a slot named for an event time is a
 *     timestamp and is formatted. Any `{single_brace}` left over is itself a
 *     key. Android replaces every slot, matched or not — an unmatched one
 *     becomes empty rather than showing its braces.
 *  3. A row with **no substitutions** is taken to be an **AES-encrypted body**
 *     — Android decrypts unconditionally there, and its decrypt hands back
 *     the input on failure, which is how a plain key survives that branch.
 *     Here the body is decrypted only when it is shaped like ciphertext (hex,
 *     whole blocks); anything else is a key and goes to the dictionary, and a
 *     cipher that will not open shows a placeholder rather than hex.
 *
 * Android renders the result through `Html.fromHtml`; here the tags are
 * stripped, so a `<b>` around a name costs nothing and a `<br>` still breaks.
 * Android's list also strips every `(` and `)` from the general branch — a
 * quirk the web does not share, and not carried over.
 */
class NotificationDecoder(
    private val labels: NotificationLabels = DictionaryNotificationLabels,
    /** Hex ciphertext to plaintext, or null when it does not decrypt. */
    private val decrypt: (String) -> String? = { null },
    private val formatEventTime: (Long) -> String = { it.toStampLabel() },
) {

    /** The row's sentence, or blank for a row with no message. */
    fun text(wire: NotificationWireText): String {
        val message = wire.message.trim()
        if (message.isEmpty()) return ""
        val body = if (wire.action in ACCESS_ACTIONS) accessGrantText(wire, message) else generalText(wire, message)
        return body.stripHtml().trim()
    }

    /**
     * `{tools_label/call_sheet_label}` — or `{tools_label}/{call_sheet_label}`
     * — as `Tools : Call Sheet`. The web's `getLocalStaticDataforNotifiaction`
     * strips every brace and joins on ` : `; Android's push path expects each
     * segment braced. Stripping all braces first serves both spellings.
     */
    fun pathLabel(path: String): String =
        path.pathSegments().joinToString(PATH_SEPARATOR) { labels.translate(it, preferMessages = false) }

    private fun accessGrantText(wire: NotificationWireText, message: String): String {
        val identifier = wire.path.pathSegments().lastOrNull()
            ?.let { labels.translate(it, preferMessages = false) }
            .orEmpty()
        val body = labels.translate(message, preferMessages = false)
        return if (identifier.isBlank()) body else "$identifier: $body"
    }

    private fun generalText(wire: NotificationWireText, message: String): String {
        val cipher = wire.encrypted || (wire.elements.isEmpty() && message.looksEncrypted())
        val plain = if (cipher) decrypt(message) ?: UNREADABLE else labels.translate(message, preferMessages = true)
        return substitute(plain, wire.elements)
    }

    /**
     * `decodeSubLevelNotification`: `{{slot}}` from the elements, then
     * `{key}` from the dictionary, then the escaped quotes.
     */
    private fun substitute(text: String, elements: List<MessageElement>): String {
        val filled = DOUBLE_BRACE.replace(text) { match ->
            val slot = match.value
            val element = elements.firstOrNull { it.search == slot }
            val raw = element?.replacer.orEmpty()
            val value = raw.takeIf { it.isNotEmpty() }?.let { labels.exact(it) } ?: raw
            if (slot.contains(EVENT_TIME) && value.isNotEmpty() && value.all(Char::isDigit)) {
                value.toLongOrNull()?.let(formatEventTime) ?: value
            } else {
                value
            }
        }
        val labelled = SINGLE_BRACE.replace(filled) { match ->
            labels.translate(match.groupValues[1], preferMessages = true)
        }
        return labelled.replace("\\", "\"")
    }

    private fun String.pathSegments(): List<String> =
        replace("{", "").replace("}", "").split('/').map(String::trim).filter(String::isNotEmpty)

    /** AES/CBC output is whole 16-byte blocks in hex: an even run of hex digits, at least one block. */
    private fun String.looksEncrypted(): Boolean =
        length >= HEX_BLOCK && length % 2 == 0 && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    companion object {
        /**
         * The actions Android prefixes with the path's last segment
         * (`NotificationAdapter.onBind`, line 31; keys at `Constants.kt:1532-1538`).
         */
        val ACCESS_ACTIONS: Set<String> = setOf(
            "project_post_access_granted",
            "project_view_access_granted",
            "project_post_access_revoked",
            "project_view_access_revoked",
            "project_download_access_granted",
            "project_download_access_revoked",
            "filecabinate_zip_created",
        )

        /** Shown in place of a body that will not decrypt — never the ciphertext. */
        val UNREADABLE: String get() = str(S.desktop_notification_unreadable)

        private const val PATH_SEPARATOR = " : "
        private const val EVENT_TIME = "event_time"
        private const val HEX_BLOCK = 32
        private val DOUBLE_BRACE = Regex("""\{\{(.*?)}}""")
        private val SINGLE_BRACE = Regex("""\{(.*?)}""")
    }
}

/**
 * The little of HTML a notification body uses, flattened to text: breaks and
 * paragraphs become newlines, every other tag goes, entities are unescaped.
 * `Html.fromHtml` on Android does the same job with a renderer attached.
 */
fun String.stripHtml(): String {
    if (!contains('<') && !contains('&')) return this
    return replace(BREAK_TAG, "\n")
        .replace(ANY_TAG, "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .lines()
        .joinToString("\n") { it.trim() }
        .trim()
}

private val BREAK_TAG = Regex("""(?i)<br\s*/?>|</p>|</div>""")
private val ANY_TAG = Regex("""<[^>]*>""")
