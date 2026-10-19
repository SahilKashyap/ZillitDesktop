package com.zillit.desktop.feature.chat.domain

/**
 * Crew tags on their way out of the composer.
 *
 * The composer leaves a tag in the draft the way the writer reads it —
 * `@Aisha Khan` — because a field showing `@{{678108ba34a30d92ea0}}` cannot be
 * read back or edited, and hiding the token behind a visual transformation
 * means mapping every keystroke across two different lengths of text.
 *
 * The wire, though, carries ids: `@{{userId}}`, which every client resolves
 * against its own crew list (see `MentionSpan`, and the note in
 * `ChatWire.editEnvelope` — tags ride inside the body and nothing goes beside
 * them). So the name becomes an id once, on the way to the socket.
 */

/**
 * [draft] with every tag of a known crew member rewritten as `@{{userId}}`.
 *
 * At each word-starting `@`, the longest crew name that fits wins, so
 * `@Aisha Khan` is one tag and not a tag of `@Aisha` in front of the word
 * "Khan". Matching is case-insensitive because people type a name as they
 * think of it, not as the directory spells it.
 *
 * An `@` that names nobody is left exactly as typed: `@here`, an email
 * address, a price in a foreign currency. Only a name the crew list can place
 * becomes a token, which is the same bargain the reader's end strikes — an id
 * it cannot place stays raw text rather than becoming a dead tag.
 *
 * Note [crew] decides what is taggable, so a caller passing the whole
 * production can tag someone who is not in the room. That is the web's
 * behaviour too: the room's members are not loaded until its info panel is
 * opened, and a tag of an absent person is readable rather than harmful.
 */
fun taggedBody(draft: String, crew: List<CrewContact>): String {
    if ('@' !in draft || crew.isEmpty()) return draft
    val byLongestName = crew.filter { it.fullName.isNotBlank() }.sortedByDescending { it.fullName.length }
    if (byLongestName.isEmpty()) return draft

    val out = StringBuilder(draft.length)
    var consumed = 0
    while (consumed < draft.length) {
        val at = draft.indexOf('@', consumed)
        if (at < 0) break
        out.append(draft, consumed, at)
        val tagged = byLongestName.firstOrNull { it.isTaggedAt(draft, at) }
        if (tagged == null) {
            // Not a tag. The `@` goes out as it came, and the scan carries on
            // past it rather than from it, or the next pass finds it again.
            out.append('@')
            consumed = at + 1
        } else {
            out.append("@{{").append(tagged.userId).append("}}")
            consumed = at + 1 + tagged.fullName.length
        }
    }
    out.append(draft, consumed, draft.length)
    return out.toString()
}

/**
 * This person's name follows the `@` at [at] in [draft], and that `@` opens a
 * word.
 *
 * The word test is what keeps `sahil@zillit.com` out: an `@` with a letter in
 * front of it is an address, never a tag — the same rule the reader's
 * `mentionQueryOf` and `mentionRangesIn` apply.
 */
private fun CrewContact.isTaggedAt(draft: String, at: Int): Boolean {
    if (at > 0 && !draft[at - 1].isWhitespace()) return false
    return draft.regionMatches(at + 1, fullName, 0, fullName.length, ignoreCase = true)
}

