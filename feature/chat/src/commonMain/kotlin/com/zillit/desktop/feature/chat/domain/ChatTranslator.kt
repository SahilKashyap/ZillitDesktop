package com.zillit.desktop.feature.chat.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * The machine translation behind the bubble menu's Translate — the web's
 * `handleTranslateCncMessage` (`cnc_latest/cncUtil.js:1056-1112`), which
 * posts the text to `{integrations}/v2/chat-gpt/translate` with the device's
 * language and shows the answer under the original.
 *
 * Offered only when the production's language and this computer's differ
 * (ZL-16953, `SenderMessage.jsx:253-260`) — that comparison is the two
 * language functions here. Hosts wire the call; null hides the item.
 */
interface ChatTranslator {
    /** The production's working language code — `language_code`, `en` when unset. */
    fun projectLanguage(): String

    /** This computer's language code — what a message is translated INTO. */
    fun deviceLanguage(): String

    /** [text] rendered in [toLanguage], or why not. */
    suspend fun translate(text: String, toLanguage: String): ZillitResult<String>

    /** The gate the menu reads: nothing to translate into when the two agree. */
    fun isOffered(): Boolean = projectLanguage().lowercase() != deviceLanguage().lowercase()
}
