package com.zillit.desktop.core.strings

import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Reads an Android `strings.xml` into a [StringCatalog].
 *
 * The files under `resources/i18n` are the Android client's, copied verbatim
 * by `scripts/sync-android-strings.py`, so this has to read what `aapt` reads:
 * the three text-bearing elements, and Android's escaping rules on their
 * contents (see [unescape]). Anything else in the file is ignored.
 */
object AndroidStringsXml {

    fun parse(language: AppLanguage, input: InputStream): StringCatalog {
        val factory = DocumentBuilderFactory.newInstance().apply {
            // Reference data from our own jar, but the parser does not know
            // that. No DTDs, no external entities, ever.
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
            isCoalescing = true
        }
        val root = factory.newDocumentBuilder().parse(input).documentElement
        val strings = HashMap<String, String>()
        val plurals = HashMap<String, Map<String, String>>()
        val arrays = HashMap<String, List<String>>()

        root.children().forEach { element ->
            val name = element.getAttribute("name").takeIf { it.isNotBlank() } ?: return@forEach
            when (element.tagName) {
                "string" -> strings[name] = unescape(element.textContent)
                "plurals" -> plurals[name] = element.children("item")
                    .associate { it.getAttribute("quantity") to unescape(it.textContent) }
                "string-array" -> arrays[name] = element.children("item").map { unescape(it.textContent) }
            }
        }
        return StringCatalog(language, strings, plurals, arrays)
    }

    private fun Element.children(tag: String? = null): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length)
            .mapNotNull { nodes.item(it) as? Element }
            .filter { tag == null || it.tagName == tag }
    }

    /**
     * Android's resource-string rules, as `aapt` applies them.
     *
     *  - Runs of whitespace collapse to one space and the ends are trimmed,
     *    *except* inside a pair of unescaped double quotes, which are removed
     *    and preserve what they wrap.
     *  - A backslash escapes the next character: `\n` and `\t` are the
     *    control characters, `\uXXXX` a code point, and `\'`, `\"`, `\\`, `\@`,
     *    `\?` the character itself.
     *
     * Ported rather than approximated because the translated files use every
     * one of these — French apostrophes are all `\'`, and several strings wrap
     * a leading space in quotes to keep it.
     */
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements") // One state machine; one place.
    internal fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var quoted = false
        var pendingSpace = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    val (literal, consumed) = escaped(raw, i + 1)
                    i += 1 + consumed
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.append(literal)
                }
                c == '"' -> {
                    quoted = !quoted
                    i++
                }
                !quoted && c.isWhitespace() -> {
                    pendingSpace = out.isNotEmpty()
                    i++
                }
                else -> {
                    if (pendingSpace) out.append(' ')
                    pendingSpace = false
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** The character an escape at [at] (the char after the backslash) stands for, and how many chars it used. */
    private fun escaped(raw: String, at: Int): Pair<Char, Int> = when (val next = raw[at]) {
        'n' -> '\n' to 1
        't' -> '\t' to 1
        'u' -> {
            val hex = raw.substring(at + 1, minOf(at + 1 + HEX_DIGITS, raw.length))
            val code = hex.toIntOrNull(HEX_RADIX)
            if (code != null && hex.length == HEX_DIGITS) code.toChar() to 1 + HEX_DIGITS else 'u' to 1
        }
        else -> next to 1
    }

    private const val HEX_DIGITS = 4
    private const val HEX_RADIX = 16
}
