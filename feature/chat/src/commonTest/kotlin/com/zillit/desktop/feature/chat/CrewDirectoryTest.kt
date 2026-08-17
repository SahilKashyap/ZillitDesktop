package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.NO_DEPARTMENT
import com.zillit.desktop.feature.chat.domain.byDepartment
import com.zillit.desktop.feature.chat.domain.searchCrew
import com.zillit.desktop.feature.chat.domain.sortedRecents
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The directory's two rules: search that reaches roles, and call-sheet order.
 */
class CrewDirectoryTest {

    private val crew = listOf(
        CrewContact("u1", "Vidya Pixel", designation = "Gaffer", department = "Lighting"),
        CrewContact("u2", "Aisha Khan", designation = "First AC", department = "Camera"),
        CrewContact("u3", "Sunil k Gautam", designation = "DIT", department = "Camera"),
        CrewContact("u4", "Zara Free", designation = null, department = null),
    )

    @Test
    fun `search reaches name, role and department, case-blind`() {
        assertEquals(listOf("u1"), crew.searchCrew("vidya").map { it.userId })
        assertEquals(listOf("u1"), crew.searchCrew("GAFFER").map { it.userId })
        assertEquals(listOf("u2", "u3"), crew.searchCrew("camera").map { it.userId })
        assertEquals(4, crew.searchCrew("  ").size, "blank matches everyone")
        assertEquals(0, crew.searchCrew("zz").size)
    }

    @Test
    fun `departments read like a call sheet`() {
        val sections = crew.byDepartment()

        assertEquals(listOf("Camera", "Lighting", NO_DEPARTMENT), sections.map { it.first })
        assertEquals(
            listOf("Aisha Khan", "Sunil k Gautam"),
            sections.first().second.map { it.fullName },
            "people alphabetical within a department",
        )
        assertEquals(listOf("Zara Free"), sections.last().second.map { it.fullName })
    }

    @Test
    fun `no department-less crew, no trailing section`() {
        val sections = crew.take(3).byDepartment()
        assertEquals(listOf("Camera", "Lighting"), sections.map { it.first })
    }

    @Test
    fun `recents sort by known activity, strangers keep server order behind`() {
        val ids = listOf("a", "b", "c", "d")
        val newest = mapOf("b" to 10L, "d" to 20L)

        assertEquals(listOf("d", "b", "a", "c"), sortedRecents(ids, newest))
        assertEquals(ids, sortedRecents(ids, emptyMap()), "no activity, no reorder")
    }
}
