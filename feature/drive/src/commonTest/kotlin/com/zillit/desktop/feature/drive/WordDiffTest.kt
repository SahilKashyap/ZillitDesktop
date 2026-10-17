package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.domain.DiffRun
import com.zillit.desktop.feature.drive.domain.DiffRunType
import com.zillit.desktop.feature.drive.domain.wordDiff
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The word diff behind an edited paragraph — the cases from the web's
 * `versionHistoryUtils.test.js`, which are the ones real documents produced.
 */
class WordDiffTest {

    private fun same(text: String) = DiffRun(DiffRunType.Same, text)
    private fun removed(text: String) = DiffRun(DiffRunType.Removed, text)
    private fun added(text: String) = DiffRun(DiffRunType.Added, text)

    @Test
    fun `the words that changed in an edited paragraph are marked`() {
        assertEquals(
            listOf(same("call time "), removed("6:30"), added("7:00"), same(" sharp.")),
            wordDiff("call time 6:30 sharp.", "call time 7:00 sharp."),
        )
    }

    @Test
    fun `neighbouring edits join into one, and a pure addition stays one run`() {
        assertEquals(
            listOf(same("at "), removed("6:30 sharp"), added("7:00 exactly"), same(" today")),
            wordDiff("at 6:30 sharp today", "at 7:00 exactly today"),
        )
        assertEquals(
            listOf(same("Wrap by 7."), added(" No overtime.")),
            wordDiff("Wrap by 7.", "Wrap by 7. No overtime."),
        )
    }

    @Test
    fun `only the letters typed into or deleted from a word are marked`() {
        assertEquals(
            listOf(same("dignissim ipsum"), added("cxz"), same(". Ut")),
            wordDiff("dignissim ipsum. Ut", "dignissim ipsumcxz. Ut"),
        )
        assertEquals(
            listOf(same("dignissim "), removed("ipsumcxz"), same(". Ut")),
            wordDiff("dignissim ipsumcxz. Ut", "dignissim . Ut"),
        )
        assertEquals(
            listOf(same("Budget 2 crore"), added("s")),
            wordDiff("Budget 2 crore", "Budget 2 crores"),
        )
    }

    @Test
    fun `an unchanged paragraph is one run, and the empty cases hold`() {
        assertEquals(listOf(same("Same text")), wordDiff("Same text", "Same text"))
        assertEquals(listOf(added("New line")), wordDiff("", "New line"))
        assertEquals(listOf(removed("Old line")), wordDiff("Old line", ""))
        assertEquals(emptyList(), wordDiff(null, null))
    }

    /**
     * Whole paragraphs replaced: past the grid cap the middle is marked
     * wholesale rather than compared word by word, which is what keeps the
     * panel responsive on a thousand-word paragraph.
     */
    @Test
    fun `two long unrelated paragraphs are marked wholesale`() {
        val before = (1..600).joinToString(" ") { "alpha$it" }
        val after = (1..600).joinToString(" ") { "beta$it" }

        val runs = wordDiff(before, after)

        assertEquals(listOf(DiffRunType.Removed, DiffRunType.Added), runs.map { it.type })
        assertEquals(before, runs.first().text)
        assertEquals(after, runs.last().text)
    }
}
