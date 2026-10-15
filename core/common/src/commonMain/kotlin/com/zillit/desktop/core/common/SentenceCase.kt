package com.zillit.desktop.core.common

/**
 * The capital letter a phone gives you for free.
 *
 * On the phones every composer sits above a soft keyboard that opens in shift
 * for the first letter of a sentence, so a crew member's messages arrive
 * capitalised without anyone thinking about it. A hardware keyboard does no
 * such thing, and the same person typing the same message on the desktop sends
 * "copy that" where their phone sent "Copy that" — the same thread, two
 * voices.
 *
 * This puts the capital back, and only ever that: it acts on the one character
 * that was just typed, never on text that is already sitting in the field.
 * Rewriting the whole draft on every keystroke would undo a deliberate
 * lowercase the moment anything else was typed, and would mangle a pasted
 * paragraph or a completed mention — so a change that is not exactly one
 * inserted character is left alone.
 */
object SentenceCase {

    /** What a sentence can end with. A capital follows any of these and a space. */
    private const val TERMINATORS = ".!?…"

    /**
     * Marks that open a sentence rather than belong to it, so a sentence is
     * still looked for behind them: `"copy that"` as a whole message gets its
     * capital. Only looked *past* — a quote mid-sentence opens nothing, and
     * `He said "we leave at six"` is left as the typist wrote it.
     */
    private const val OPENERS = "\"'([{“‘«"

    /**
     * [after] with the just-typed character capitalised, when that character
     * began a sentence — otherwise [after] exactly as it came in.
     *
     * [before] is the field's text as it was one keystroke ago; the pair is
     * what says *which* character the typist is responsible for. Callers hold
     * both already, since the draft they are about to replace is the old one.
     */
    fun applied(before: String, after: String): String {
        val at = typedAt(before, after) ?: return after
        val typed = after[at]
        return if (typed.isLowerCase() && startsSentence(after, at)) {
            after.substring(0, at) + typed.uppercaseChar() + after.substring(at + 1)
        } else {
            after
        }
    }

    /**
     * Where the one new character landed, or null when [after] is not [before]
     * with a single character inserted — a deletion, a paste, a mention
     * completed, a draft cleared on send, or a field set from elsewhere.
     */
    private fun typedAt(before: String, after: String): Int? {
        if (after.length != before.length + 1) return null
        var at = 0
        while (at < before.length && before[at] == after[at]) at++
        // The rest has to be untouched, or this was a replacement that happens
        // to have grown by one — not a keystroke whose character we can name.
        val tailMatches = before.regionMatches(at, after, at + 1, before.length - at)
        return at.takeIf { tailMatches }
    }

    /**
     * Whether the character at [at] opens a sentence: nothing but openers
     * before it, a line of its own, or a terminator and the space after it.
     *
     * The space is the whole of the "and a space": without it `www.example`
     * and `e.g` would be capitalised mid-word, which is the one way this can
     * be worse than leaving the text alone.
     */
    private fun startsSentence(text: String, at: Int): Boolean {
        var index = at - 1
        while (index >= 0 && text[index] in OPENERS) index--
        var spaced = false
        while (index >= 0 && text[index].isWhitespace()) {
            // A new line is a new sentence whatever ended the last one — a
            // list of names, a signature, the second paragraph.
            if (text[index] == '\n' || text[index] == '\r') return true
            spaced = true
            index--
        }
        // Nothing behind it at all: the first word of the message.
        if (index < 0) return true
        return spaced && text[index] in TERMINATORS
    }
}
