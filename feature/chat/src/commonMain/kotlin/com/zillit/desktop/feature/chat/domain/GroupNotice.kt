package com.zillit.desktop.feature.chat.domain

import com.zillit.desktop.core.common.MessageElement
import com.zillit.desktop.core.localization.LabelKind
import com.zillit.desktop.core.localization.Labels

/**
 * A `message_type: "group_notification"` line — "A new chat group has been
 * Created. and 14 MEMBERS have been added to the group." The server sends a
 * label key in `message` and the blanks' values in `message_elements`; every
 * client turns the pair into a sentence itself and draws it as a centred
 * system line, never as somebody's bubble (web `TextMessage.jsx:125`,
 * Android `GroupMessageNotificationVH`).
 */
data class GroupNotice(
    val key: String,
    val elements: List<MessageElement> = emptyList(),
) {
    /** The sentence, in the production's language. */
    val text: String get() = groupNotificationText(key, elements, ::labelOrNull)
}

/** The dictionary's word for [key], messages first, or null when neither table has it. */
private fun labelOrNull(key: String): String? =
    Labels.current.exact(key, LabelKind.Messages) ?: Labels.current.exact(key, LabelKind.Labels)

/**
 * The web's `dynamicNotificationLabel` (`multipleFunction.js:578-640`), step
 * for step, so the same wire row reads the same here: look the key up; walk
 * the sentence word by word swapping each `{{blank}}` for its element's
 * value; then give every word one more look in the dictionary (which is how
 * `members` becomes `MEMBERS`). A key the dictionary lacks is shown as it
 * came, never as nothing.
 *
 * One of the web's quirks is kept on purpose: a blank that ends the sentence
 * (`{{group_name}}.`) is replaced whole, so its full stop goes with it.
 */
fun groupNotificationText(
    message: String,
    elements: List<MessageElement>,
    lookup: (String) -> String?,
): String {
    val lookupKey = if (message.firstOrNull() in BRACKETS) {
        BLANK.findAll(message).joinToString("") { match ->
            match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty()
        }
    } else {
        message
    }
    val sentence = lookup(lookupKey) ?: return message
    val swapped = sentence.split(' ').map { word -> swapBlank(word, elements) }
    val words = swapped.map { word ->
        if (word == "N/A" || word == "{{datetime}}") {
            ""
        } else {
            val bare = word.replace("{", "").replace("}", "")
            lookup(bare)?.takeIf { it.isNotEmpty() } ?: word
        }
    }
    return words.joinToString(" ").ifBlank { message }
}

private fun swapBlank(word: String, elements: List<MessageElement>): String {
    fun valueOf(search: String) = elements.firstOrNull { it.search == search }?.replacer
    val body = word.dropLast(1)
    val last = word.lastOrNull()
    return when {
        word.isEmpty() -> word
        (last == '.' && body.firstOrNull() == '{') || body.firstOrNull() == '(' ->
            valueOf(body.replace("(", "").replace(")", "")) ?: word
        else -> valueOf(word) ?: word
    }
}

private val BRACKETS = setOf('{', '[', '(')
private val BLANK = Regex(
    """\(\{*(\w*)\}\)|\{\{*(\w*)\}\}|\[(\w*)\]|\[\[(\w*)\]\]""" +
        """|\{*([\w\d_]*)\}|\{\{*([\w\d_]*)\}\}""",
)
