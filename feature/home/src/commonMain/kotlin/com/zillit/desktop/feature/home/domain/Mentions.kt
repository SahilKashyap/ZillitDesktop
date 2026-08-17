package com.zillit.desktop.feature.home.domain

/**
 * Crew mentions, the cross-client-safe way.
 *
 * CNC group chat has a server-side mention system (`@{{userId}}` tokens plus
 * `messageElements` replacements) — home chat does not, on any client, and a
 * token the other clients cannot parse would render as literal noise on every
 * phone. So a mention here is the crew member's **plain name**: `@Aisha Khan`
 * travels as ordinary text everyone can read, and this client recognises and
 * highlights names that match the production's crew list.
 */

/**
 * The `@partial` currently being typed at the end of [text], or null.
 *
 * The `@` must start a word — `email@example.com` is not a mention. Names
 * carry spaces, so the token may too; the cap keeps a stray `@` early in a
 * long paragraph from turning the whole tail into a query.
 */
fun mentionQueryOf(text: String): String? {
    val at = text.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !text[at - 1].isWhitespace()) return null

    val partial = text.substring(at + 1)
    if (partial.length > MAX_MENTION_QUERY || partial.contains('\n')) return null
    return partial
}

/**
 * Crew ranked against [query], best answers first.
 *
 * Tiers, strongest to weakest: the whole name starts with the query, a later
 * word does (`pix` → Vidya **Pix**el), the initials do (`vp` → **V**idya
 * **P**ixel), the query appears inside the name, and — for three letters or
 * more — the letters appear in order with gaps, which forgives a dropped
 * letter (`vdya`). Inside a tier, names in [recentFirst] float up in that
 * order — recency breaks ties but never beats a stronger tier, so a prefix
 * answer cannot be pushed down by someone merely mentioned yesterday. Ties
 * beyond that keep crew order, and everyone answers an empty query — which
 * makes the bare `@` popup open on the people mentioned last.
 */
fun mentionMatches(
    query: String,
    names: List<String>,
    recentFirst: List<String> = emptyList(),
): List<String> =
    names.asSequence()
        .filter { it.isNotBlank() }
        .distinct()
        .mapNotNull { name -> tierMatchOf(query, name)?.let { it.rank to name } }
        .sortedWith(compareBy({ it.first }, { recencyRank(it.second, recentFirst) }))
        .map { it.second }
        .take(MAX_MENTION_SUGGESTIONS)
        .toList()

/** Position in the recent list; strangers sort after every remembered name. */
private fun recencyRank(name: String, recentFirst: List<String>): Int {
    val at = recentFirst.indexOf(name)
    return if (at < 0) Int.MAX_VALUE else at
}

/**
 * Which characters of [name] answered [query] — for the picker to light up.
 *
 * Computed by the same tier that ranked the name, so the letters shown as
 * matched are exactly the letters that made the row appear: a prefix or
 * substring lights a solid run, initials light one letter per word, and the
 * typo tier lights its scattered path. Empty for an empty query or no match.
 */
fun mentionMatchedIndices(query: String, name: String): List<Int> =
    tierMatchOf(query, name)?.indices ?: emptyList()

/** One tier's answer: how strong the match is, and which name characters made it. */
private class TierMatch(val rank: Int, val indices: List<Int>)

private fun tierMatchOf(query: String, name: String): TierMatch? = when {
    query.isEmpty() -> TierMatch(RANK_PREFIX, emptyList())
    name.startsWith(query, ignoreCase = true) -> TierMatch(RANK_PREFIX, solidRun(0, query.length))
    else -> wordTier(query, name)
        ?: initialIndices(query, name)?.let { TierMatch(RANK_INITIALS, it) }
        ?: substringTier(query, name)
        ?: typoTier(query, name)
}

private fun wordTier(query: String, name: String): TierMatch? =
    wordStartPositions(name)
        .firstOrNull { name.regionMatches(it, query, 0, query.length, ignoreCase = true) }
        ?.let { TierMatch(RANK_WORD, solidRun(it, query.length)) }

private fun substringTier(query: String, name: String): TierMatch? =
    name.indexOf(query, ignoreCase = true)
        .takeIf { it >= 0 }
        ?.let { TierMatch(RANK_SUBSTRING, solidRun(it, query.length)) }

private fun typoTier(query: String, name: String): TierMatch? = when {
    query.length < MIN_TYPO_QUERY -> null
    else -> subsequenceIndices(query, name)?.let { TierMatch(RANK_TYPO, it) }
}

/**
 * Anchored on the first initial, then a subsequence of the rest — `sg`
 * reaches Sunil k Gautam without the middle initial, the way editors match
 * camel humps. A query with a space is words, not initials. Returns the
 * name-indices of the initials the query consumed, or null for no match.
 */
private fun initialIndices(query: String, name: String): List<Int>? {
    if (query.isEmpty() || query.contains(' ')) return null
    val starts = wordStartPositions(name)
    if (starts.isEmpty() || !query.first().equals(name[starts.first()], ignoreCase = true)) {
        return null
    }
    val hits = mutableListOf<Int>()
    var at = 0
    for (start in starts) {
        if (at < query.length && name[start].equals(query[at], ignoreCase = true)) {
            hits += start
            at++
        }
    }
    return if (at == query.length) hits else null
}

/** The greedy in-order walk of the typo tier, as name-indices; null if it fails. */
private fun subsequenceIndices(query: String, name: String): List<Int>? {
    val hits = mutableListOf<Int>()
    var at = 0
    name.forEachIndexed { index, ch ->
        if (at < query.length && ch.equals(query[at], ignoreCase = true)) {
            hits += index
            at++
        }
    }
    return if (at == query.length) hits else null
}

/** Index of the first character of each word. */
private fun wordStartPositions(name: String): List<Int> =
    name.indices.filter { name[it] != ' ' && (it == 0 || name[it - 1] == ' ') }

/** A solid run of [length] indices from [from]. */
private fun solidRun(from: Int, length: Int): List<Int> = (from until from + length).toList()

/** Replaces the trailing `@partial` with the chosen name, space appended. */
fun completeMention(text: String, name: String): String {
    val at = text.lastIndexOf('@')
    if (at < 0) return text
    return text.substring(0, at) + "@" + name + " "
}

/**
 * Where crew mentions sit in [body] — for the bubble's highlight spans.
 *
 * At each word-starting `@`, the longest matching crew name wins, so
 * `@Aisha Khan` is one mention rather than a mention of `@Aisha` trailing
 * the word "Khan". Case-insensitive: people type names as they think of them.
 */
fun mentionRangesIn(body: String, names: List<String>): List<IntRange> {
    if (names.isEmpty()) return emptyList()
    val byLength = names.filter { it.isNotBlank() }.sortedByDescending { it.length }
    val ranges = mutableListOf<IntRange>()

    var index = body.indexOf('@')
    while (index >= 0) {
        val startsWord = index == 0 || body[index - 1].isWhitespace()
        if (startsWord) {
            val match = byLength.firstOrNull { name ->
                body.regionMatches(index + 1, name, 0, name.length, ignoreCase = true)
            }
            if (match != null) ranges += index..(index + match.length)
        }
        index = body.indexOf('@', index + 1)
    }
    return ranges
}

private const val MAX_MENTION_QUERY = 30
private const val MAX_MENTION_SUGGESTIONS = 6

// Match tiers, in sortedBy order — prefix answers always outrank fuzzier ones.
private const val RANK_PREFIX = 0
private const val RANK_WORD = 1
private const val RANK_INITIALS = 2
private const val RANK_SUBSTRING = 3
private const val RANK_TYPO = 4

/**
 * The scattered tier needs this many letters: one or two characters appear
 * "in order with gaps" in half the crew list, and a picker full of strangers
 * reads as broken rather than forgiving.
 */
private const val MIN_TYPO_QUERY = 3
