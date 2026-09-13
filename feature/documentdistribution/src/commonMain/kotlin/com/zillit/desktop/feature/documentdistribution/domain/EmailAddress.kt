package com.zillit.desktop.feature.documentdistribution.domain

/**
 * Validates an address the way the doc-dist backend does.
 *
 * Shape first — `local@domain.tld` with a two-plus-letter alphabetic TLD —
 * then the TLD against the IANA set in [KNOWN_TLDS]. The server runs the same
 * two checks (Joi `.email()`), and an address that fails them is silently
 * dropped from the send rather than reported, so catching it here is the only
 * warning the sender gets.
 */
fun isValidEmail(value: String?): Boolean {
    val email = value?.trim().orEmpty()
    if (!EMAIL_SHAPE.matches(email)) return false
    val tld = email.substringAfterLast('.').lowercase()
    return tld in KNOWN_TLDS
}

private val EMAIL_SHAPE = Regex("^[^\\s@]+@[^\\s@]+\\.[A-Za-z]{2,}$")

/**
 * Splits pasted text into addresses, accepting `Name <addr>` and the
 * separators people paste from a mail client.
 */
fun parseAddressList(text: String): List<Recipient> =
    text.split(',', ';', '\n', '\t')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { token ->
            val angled = ANGLED.find(token)
            if (angled != null) {
                Recipient(email = angled.groupValues[2].trim(), name = angled.groupValues[1].trim())
            } else {
                Recipient(email = token.trim('<', '>'))
            }
        }

private val ANGLED = Regex("^\\s*([^<]*?)\\s*<([^>]+)>\\s*$")
