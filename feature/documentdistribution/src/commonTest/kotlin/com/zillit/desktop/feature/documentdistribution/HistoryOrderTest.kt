package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.data.decodeDistributions
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * History is ordered by this client, not by the server.
 *
 * `/distributions` returned a send made minutes earlier *below* rows from a
 * fortnight before (verified live 2026-08-11), so a History whose top row was
 * the last thing you sent could not be relied on. One page of fifty is cheap to
 * sort, and the order is the whole point of the screen.
 *
 * The sort itself lives in `DocDistViewModel.loadHistory`; this pins the
 * ordering rule against decoded rows so the intent survives a refactor of it.
 */
class HistoryOrderTest {

    private fun ordered(json: String) =
        decodeDistributions(json).sortedByDescending { it.sentAt ?: Long.MIN_VALUE }

    @Test
    fun `the newest send comes first whatever order the server used`() {
        val rows = ordered(
            """
            [
              {"_id":"old","subject":"Two weeks ago","created":1753000000000},
              {"_id":"new","subject":"Minutes ago","created":1754000000000},
              {"_id":"mid","subject":"Last week","created":1753500000000}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf("new", "mid", "old"), rows.map { it.id })
    }

    @Test
    fun `a send with no timestamp sorts last, not first`() {
        // An absent stamp is unknown. Floating it to the top would put the
        // least informative row exactly where the most recent one belongs.
        val rows = ordered(
            """
            [
              {"_id":"unstamped","subject":"No date"},
              {"_id":"stamped","subject":"Dated","created":1754000000000}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf("stamped", "unstamped"), rows.map { it.id })
    }

    @Test
    fun `an already-ordered page is left alone`() {
        val rows = ordered(
            """
            [
              {"_id":"a","created":1754000000000},
              {"_id":"b","created":1753000000000}
            ]
            """.trimIndent(),
        )

        assertEquals(listOf("a", "b"), rows.map { it.id })
    }
}
