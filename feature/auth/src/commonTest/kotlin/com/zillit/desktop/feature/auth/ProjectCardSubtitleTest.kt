package com.zillit.desktop.feature.auth

import com.zillit.desktop.core.localization.LabelDictionary
import com.zillit.desktop.core.localization.LabelKind
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.ui.subtitle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What the production picker prints under a production's name.
 *
 * `GET project` answers in translation keys, not display names — verified
 * against QA, where every card on the picker read `feature_label` because this
 * line printed the wire value as it came.
 */
class ProjectCardSubtitleTest {

    private val loaded = LabelDictionary.Empty.with(
        LabelKind.Labels,
        mapOf(
            "feature_label" to "Feature Film",
            "entertainment_industry_label" to "Entertainment Industry",
        ),
    )

    private fun project(
        subType: String? = null,
        type: String? = null,
        parentName: String? = null,
    ) = Project(
        id = "p1",
        name = "The Project",
        code = "EN1234",
        type = type,
        region = null,
        parentName = parentName,
        subType = subType,
    )

    @Test
    fun `the sub-type key becomes its translation`() {
        assertEquals("Feature Film", project(subType = "feature_label").subtitle(loaded))
    }

    @Test
    fun `a raw key never reaches the card, even with nothing loaded`() {
        // The picker is on screen before the dictionaries finish loading, so
        // the fallback is what most people see first. It must still be words.
        assertEquals("Feature", project(subType = "feature_label").subtitle(LabelDictionary.Empty))
    }

    @Test
    fun `the type stands in when there is no sub-type`() {
        // `Project.type` is `project_type_id` — the filterable id, which is
        // key-shaped too.
        assertEquals("Entertainment", project(type = "entertainment").subtitle(LabelDictionary.Empty))
        assertEquals(
            "Entertainment Industry",
            project(type = "entertainment_industry_label").subtitle(loaded),
        )
    }

    @Test
    fun `a type the server already resolved is left alone`() {
        // Some rows come back with the display name rather than the key. It has
        // a space in it, so it is prose and must not be re-cased.
        assertEquals(
            "Entertainment Industry",
            project(type = "Entertainment Industry").subtitle(LabelDictionary.Empty),
        )
    }

    @Test
    fun `a sub-project names its parent first`() {
        assertEquals(
            "in Season One · Feature Film",
            project(subType = "feature_label", parentName = "Season One").subtitle(loaded),
        )
    }

    @Test
    fun `a production with neither still says something`() {
        assertEquals("Project", project().subtitle(loaded))
    }
}
