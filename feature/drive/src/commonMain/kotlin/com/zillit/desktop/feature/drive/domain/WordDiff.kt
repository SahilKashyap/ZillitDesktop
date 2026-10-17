package com.zillit.desktop.feature.drive.domain

/**
 * The word-level difference between two versions of a paragraph — the web's
 * `wordDiff`, which is how an edited paragraph is shown struck through and
 * added in place rather than as two whole paragraphs.
 *
 * Three behaviours, each of which the web had to add after reading real
 * diffs:
 *
 *  - **matching ends are skipped first**, so a long paragraph with one edited
 *    clause costs a grid of the clause, not of the paragraph;
 *  - **a space between two edits joins them**, so "6:30 sharp" →
 *    "7:00 exactly" reads as one replacement rather than four runs;
 *  - **a word replaced by a near-identical one marks only the characters that
 *    differ** ("ipsum." → "ipsumcxz."), while unrelated words stay whole
 *    ("6:30" → "7:00"). See [refineReplacements].
 */

/** A stretch of a paragraph, as the diff marks it. */
data class DiffRun(val type: DiffRunType, val text: String)

enum class DiffRunType { Same, Removed, Added }

fun wordDiff(before: String?, after: String?): List<DiffRun> {
    val a = tokensOf(before)
    val b = tokensOf(after)
    var start = 0
    while (start < a.size && start < b.size && a[start] == b[start]) start += 1
    var endA = a.size
    var endB = b.size
    while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
        endA -= 1
        endB -= 1
    }
    val steps = diffSteps(a, b, start, endA, endB)
    return assemble(a, steps, start, endA)
}

/**
 * The edit script for the middle, by longest common subsequence.
 *
 * Past [MAX_WORD_GRID] the whole middle is marked removed-then-added instead:
 * an exact diff of two thousand-word paragraphs is a million-cell grid, and
 * the panel would stall rather than render.
 */
private fun diffSteps(
    a: List<String>,
    b: List<String>,
    start: Int,
    endA: Int,
    endB: Int,
): List<Pair<DiffRunType, String>> {
    val rows = endA - start
    val cols = endB - start
    val steps = mutableListOf<Pair<DiffRunType, String>>()
    if (rows.toLong() * cols.toLong() > MAX_WORD_GRID) {
        for (i in start until endA) steps += DiffRunType.Removed to a[i]
        for (j in start until endB) steps += DiffRunType.Added to b[j]
        return steps
    }
    val width = cols + 1
    val lcs = IntArray((rows + 1) * width)
    for (i in rows - 1 downTo 0) {
        for (j in cols - 1 downTo 0) {
            lcs[i * width + j] = if (a[start + i] == b[start + j]) {
                lcs[(i + 1) * width + j + 1] + 1
            } else {
                maxOf(lcs[(i + 1) * width + j], lcs[i * width + j + 1])
            }
        }
    }
    var i = 0
    var j = 0
    while (i < rows && j < cols) {
        when {
            a[start + i] == b[start + j] -> {
                steps += DiffRunType.Same to a[start + i]
                i += 1
                j += 1
            }

            lcs[(i + 1) * width + j] >= lcs[i * width + j + 1] -> {
                steps += DiffRunType.Removed to a[start + i]
                i += 1
            }

            else -> {
                steps += DiffRunType.Added to b[start + j]
                j += 1
            }
        }
    }
    while (i < rows) {
        steps += DiffRunType.Removed to a[start + i]
        i += 1
    }
    while (j < cols) {
        steps += DiffRunType.Added to b[start + j]
        j += 1
    }
    return steps
}

/** The steps as runs, with the untouched ends put back and neighbours merged. */
// Transcribed from the web's `wordDiff` assembly, whose branches are the three
// behaviours in the file header; split up, neither half can be read against it.
@Suppress("CyclomaticComplexMethod")
private fun assemble(
    a: List<String>,
    steps: List<Pair<DiffRunType, String>>,
    start: Int,
    endA: Int,
): List<DiffRun> {
    val runs = mutableListOf<DiffRun>()
    var removed = StringBuilder()
    var added = StringBuilder()

    fun same(text: String) {
        if (text.isEmpty()) return
        val last = runs.lastOrNull()
        if (last != null && last.type == DiffRunType.Same) {
            runs[runs.lastIndex] = last.copy(text = last.text + text)
        } else {
            runs += DiffRun(DiffRunType.Same, text)
        }
    }

    fun flush() {
        if (removed.isNotEmpty()) runs += DiffRun(DiffRunType.Removed, removed.toString())
        if (added.isNotEmpty()) runs += DiffRun(DiffRunType.Added, added.toString())
        removed = StringBuilder()
        added = StringBuilder()
    }

    same(a.subList(0, start).joinToString(""))
    steps.forEachIndexed { index, (type, text) ->
        val pending = removed.isNotEmpty() || added.isNotEmpty()
        val nextIsEdit = steps.getOrNull(index + 1)?.first?.let { it != DiffRunType.Same } == true
        when {
            type == DiffRunType.Removed -> removed.append(text)
            type == DiffRunType.Added -> added.append(text)
            // Whitespace between two edits belongs to both, which is what
            // turns two adjacent replacements into one.
            pending && text.isBlank() && nextIsEdit -> {
                removed.append(text)
                added.append(text)
            }

            else -> {
                flush()
                same(text)
            }
        }
    }
    flush()
    same(a.subList(endA, a.size).joinToString(""))
    return refineReplacements(runs)
}

/**
 * Where a word was replaced by one it largely shares, mark only the
 * characters that differ.
 *
 * "ipsum." → "ipsumcxz." is three letters typed, not a word rewritten; and
 * where the two share only punctuation ("ipsumcxz." → ".") the shared part is
 * still worth keeping whole. Unrelated words are left whole, because marking
 * the accidental letters two different words have in common reads as noise.
 */
private fun refineReplacements(runs: List<DiffRun>): List<DiffRun> {
    val out = mutableListOf<DiffRun>()
    var index = 0
    while (index < runs.size) {
        val run = runs[index]
        val next = runs.getOrNull(index + 1)
        if (run.type == DiffRunType.Removed && next?.type == DiffRunType.Added) {
            refinePair(run.text, next.text).forEach { out.merge(it) }
            index += 2
        } else {
            out.merge(run)
            index += 1
        }
    }
    return out
}

/**
 * One removed word and the added one that replaced it, as runs.
 *
 * The shared prefix and suffix are kept only when they are worth keeping:
 * half the shorter word, or punctuation alone. Otherwise the two words are
 * unrelated and each stays whole.
 */
private fun refinePair(before: String, after: String): List<DiffRun> {
    val shorter = minOf(before.length, after.length)
    var prefix = 0
    while (prefix < shorter && before[prefix] == after[prefix]) prefix += 1
    var suffix = 0
    while (
        suffix < shorter - prefix &&
        before[before.length - 1 - suffix] == after[after.length - 1 - suffix]
    ) {
        suffix += 1
    }
    val shared = before.take(prefix) + before.takeLast(suffix)
    val worthIt = shared.isNotEmpty() &&
        (shared.length * 2 >= shorter || shared.none { it.isLetterOrDigit() })
    if (!worthIt) {
        return listOf(DiffRun(DiffRunType.Removed, before), DiffRun(DiffRunType.Added, after))
    }
    return listOf(
        DiffRun(DiffRunType.Same, before.take(prefix)),
        DiffRun(DiffRunType.Removed, before.substring(prefix, before.length - suffix)),
        DiffRun(DiffRunType.Added, after.substring(prefix, after.length - suffix)),
        DiffRun(DiffRunType.Same, before.takeLast(suffix)),
    )
}

/** Appends [run], folding it into the last one when they are the same kind. */
private fun MutableList<DiffRun>.merge(run: DiffRun) {
    if (run.text.isEmpty()) return
    val last = lastOrNull()
    if (last != null && last.type == run.type) {
        this[lastIndex] = last.copy(text = last.text + run.text)
    } else {
        this += run
    }
}

/** Words and the spaces between them, so a diff marks whole words. */
private fun tokensOf(text: String?): List<String> = TOKEN.findAll(text.orEmpty()).map { it.value }.toList()

private val TOKEN = Regex("""\s+|\S+""")

private const val MAX_WORD_GRID = 250_000L
