package com.zillit.desktop.feature.tasks.domain

/**
 * Links in a task's description and in comments. A port of the web's
 * `src/tasks/lib/links.js`: pure, no Compose.
 *
 * The text is stored exactly as it was typed — plain text — and only *shown*
 * with its links made clickable, so it reads well in the phone apps and
 * anywhere else:
 *
 *     [Recce photos](https://drive.google.com/…)   "Recce photos", as a link
 *     https://maps.app.goo.gl/…                    the address itself, shortened
 *     www.example.com                              a link to https://www.example.com
 *
 * **Only `http` and `https` addresses become links.** Anything else
 * (`javascript:`, `data:`, `mailto:`, a broken address) stays the text it is;
 * never loosen that.
 */

// [text](http…)  or a bare http… / www.… address.
private val TOKEN = Regex(
    """\[([^\]\n]{1,200})\]\((https?://[^\s)]+)\)|((?:https?://|www\.)[^\s<>"]+)""",
    RegexOption.IGNORE_CASE,
)

/** Punctuation that ends a sentence rather than the address it follows. */
private val TRAILING = Regex("""[.,;:!?)\]]+$""")

private val SCHEME = Regex("^(https?)://([^/?#\\s]+)([^\\s]*)$", RegexOption.IGNORE_CASE)
private val ANY_SCHEME = Regex("^[a-z][a-z0-9+.\\-]*://", RegexOption.IGNORE_CASE)
private val OTHER_SCHEME = Regex("^(mailto|tel|sms|javascript|data|file):", RegexOption.IGNORE_CASE)

/** An http(s) address broken into the pieces [shortUrl] shows. */
private class WebUrl(val scheme: String, val host: String, val rest: String) {
    val href: String get() = "$scheme://$host$rest"
    val path: String get() = rest.substringBefore('#').substringBefore('?')
    val query: String get() = rest.substringBefore('#').substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
}

private fun parse(address: String): WebUrl? {
    val match = SCHEME.matchEntire(address) ?: return null
    val host = match.groupValues[2]
    // A bare scheme with nothing after it ("https://") is not an address.
    if (host.isEmpty()) return null
    return WebUrl(match.groupValues[1].lowercase(), host, match.groupValues[SCHEME_REST])
}

/** The address to open, or null when it is not an http(s) one. A "www.…" gets `https://`. */
fun safeUrl(address: String?): String? {
    val typed = address.orEmpty()
    val url = if (typed.startsWith("www.", ignoreCase = true)) "https://$typed" else typed
    return parse(url)?.href
}

/** How a bare address is shown: host and path, without "www." and cut at about 48 characters. */
fun shortUrl(url: String): String {
    val parsed = parse(url) ?: return url
    val path = parsed.path.takeIf { it != "/" }.orEmpty()
    val text = parsed.host.removePrefix("www.") + path + parsed.query
    return if (text.length > SHORT_MAX) text.take(SHORT_CUT) + "…" else text
}

/** One piece of a description or a comment: plain text, or a link to show. */
sealed interface LinkPart {
    data class Text(val text: String) : LinkPart

    data class Link(val href: String, val text: String) : LinkPart
}

/**
 * The text cut into what to show, in order. Punctuation that ends a sentence
 * is left out of a bare address: "see https://x.com." links "x.com", then
 * the ".".
 */
fun linkParts(text: String?): List<LinkPart> {
    val value = text.orEmpty()
    val out = mutableListOf<LinkPart>()
    var last = 0
    TOKEN.findAll(value).forEach { match ->
        val label = match.groupValues[1].takeIf { it.isNotEmpty() }
        var href = match.groupValues[2].takeIf { it.isNotEmpty() } ?: match.groupValues[TOKEN_BARE]
        var raw = match.value
        var trail = ""
        if (label == null) {
            TRAILING.find(href)?.let { tail ->
                trail = tail.value
                href = href.dropLast(trail.length)
                raw = raw.dropLast(trail.length)
            }
        }
        val url = safeUrl(href)
        if (match.range.first > last) out += LinkPart.Text(value.substring(last, match.range.first))
        out += if (url != null) LinkPart.Link(url, label ?: shortUrl(url)) else LinkPart.Text(raw)
        if (trail.isNotEmpty()) out += LinkPart.Text(trail)
        last = match.range.last + 1
    }
    if (last < value.length) out += LinkPart.Text(value.substring(last))
    return out
}

/**
 * What was typed into "Add link", as an address to store: `https://` is put
 * in front when it has none. Null when it is not a web address (no dot in the
 * host, a space in it, or another kind of address such as `mailto:`).
 */
fun webAddress(input: String?): String? {
    val typed = input.orEmpty().trim()
    if (typed.isEmpty() || typed.any { it.isWhitespace() }) return null
    val web = typed.startsWith("http://", ignoreCase = true) || typed.startsWith("https://", ignoreCase = true)
    if (!web && (ANY_SCHEME.containsMatchIn(typed) || OTHER_SCHEME.containsMatchIn(typed))) return null
    val parsed = parse(if (web) typed else "https://$typed") ?: return null
    return if (parsed.host.contains('.')) parsed.href else null
}

/**
 * The text to store for a link: the bare address, or `[words](address)` when
 * there are words to show. Square brackets in the words and round ones in the
 * address would end the link early, so the first are dropped and the second
 * are written as %28 / %29.
 */
fun linkText(url: String, words: String = ""): String {
    val label = words.trim().replace(Regex("[\\[\\]]"), "").replace(Regex("\\s*\\n\\s*"), " ")
    if (label.isEmpty()) return url
    return "[$label](${url.replace("(", "%28").replace(")", "%29")})"
}

/** True when what was pasted is one web address and nothing else. */
fun isPastedLink(pasted: String?): Boolean =
    Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(pasted.orEmpty().trim())

/** [text] put in place of [start]..[end], and where the caret goes next. */
data class Inserted(val text: String, val caret: Int)

/**
 * [value] with [text] put in place of [start]..[end] (the caret, or the
 * selection). [spaced] keeps a space between the new text and a word right
 * before it.
 */
fun insertAt(value: String?, start: Int, end: Int, text: String, spaced: Boolean = false): Inserted {
    val current = value.orEmpty()
    val from = start.coerceIn(0, current.length)
    val to = end.coerceIn(from, current.length)
    val lead = if (spaced && from > 0 && !current[from - 1].isWhitespace()) " " else ""
    return Inserted(current.take(from) + lead + text + current.substring(to), from + lead.length + text.length)
}

/** TOKEN's third group: a bare address, where there was no `[label](…)`. */
private const val TOKEN_BARE = 3

/** SCHEME's third group: the path, query and fragment after the host. */
private const val SCHEME_REST = 3

private const val SHORT_MAX = 48
private const val SHORT_CUT = 45
