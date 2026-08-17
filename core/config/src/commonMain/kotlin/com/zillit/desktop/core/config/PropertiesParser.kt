package com.zillit.desktop.core.config

/**
 * A `.properties` reader that follows the Java format properly.
 *
 * A naive `split('=')` is not enough, and this is not a theoretical concern:
 * the Zillit config files written by Android tooling escape colons, so every
 * URL arrives as `https\://projectapi.zillit.com/`. Left unescaped, the leading
 * `https\:` fails the TLS check in `AppConfig.validate` and the app refuses to
 * start with "Non-TLS endpoints configured" — for a file that is entirely
 * correct.
 *
 * Handles the parts of the format that appear in practice:
 *
 *  - `=` **or** `:` as the key/value separator, whichever comes first unescaped
 *  - `\:` `\=` `\\` `\ ` and the usual `\t \n \r \f` escapes
 *  - `\uXXXX`
 *  - `#` and `!` comments, blank lines
 *  - leading whitespace around the separator
 *
 * Line continuations (a trailing `\`) are deliberately **not** supported: no
 * Zillit config uses them, and silently mis-joining two lines is worse than
 * ignoring a feature nobody needs.
 */
object PropertiesParser {

    fun parse(raw: String): Map<String, String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .mapNotNull(::parseLine)
            .toMap()

    private fun parseLine(line: String): Pair<String, String>? {
        val separator = indexOfSeparator(line) ?: return null
        if (separator == 0) return null

        val key = unescape(line.substring(0, separator)).trim()
        // Java strips leading whitespace after the separator, which matters:
        // real files contain `KEY= https://host/`.
        val value = unescape(line.substring(separator + 1)).trim()

        return key.takeIf { it.isNotEmpty() }?.let { it to value }
    }

    /**
     * First `=` or `:` that is not escaped and not inside an escape sequence.
     *
     * `https\://host` has its colon escaped, so the separator is the earlier
     * `=` — which is the whole point.
     */
    private fun indexOfSeparator(line: String): Int? {
        var index = 0
        while (index < line.length) {
            when (line[index]) {
                '\\' -> index++ // skip whatever is escaped
                '=', ':' -> return index
            }
            index++
        }
        return null
    }

    @Suppress("CyclomaticComplexMethod")
    private fun unescape(raw: String): String {
        if (!raw.contains('\\')) return raw

        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char != '\\' || index == raw.lastIndex) {
                out.append(char)
                index++
                continue
            }

            when (val escaped = raw[index + 1]) {
                't' -> out.append('\t')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                'f' -> out.append('')
                'u' -> {
                    val hex = raw.substring(index + 2, minOf(index + 2 + UNICODE_DIGITS, raw.length))
                    val code = hex.toIntOrNull(radix = HEX_RADIX)
                    if (code != null && hex.length == UNICODE_DIGITS) {
                        out.append(code.toChar())
                        index += UNICODE_DIGITS
                    } else {
                        // Malformed \u — keep it literal rather than throwing.
                        // A config file is not worth refusing to start over.
                        out.append(escaped)
                    }
                }
                // `\:` `\=` `\ ` `\#` `\!` `\\` and anything else: the escape
                // just means "this character literally".
                else -> out.append(escaped)
            }
            index += 2
        }
        return out.toString()
    }

    private const val UNICODE_DIGITS = 4
    private const val HEX_RADIX = 16
}
