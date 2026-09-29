package com.zillit.desktop.feature.auth

import com.zillit.desktop.feature.auth.data.ProjectDto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Productions scheduled for deletion: listed, badged, and still enterable.
 *
 * They used to be dropped with the deleted and the disabled, which left a
 * desktop-only admin no way to reach the one screen that can call the deletion
 * off. The phones list them for exactly that reason.
 */
class MarkedDeleteTest {

    private fun dto(
        markDeleted: Boolean = false,
        deleted: Boolean = false,
        enabled: Boolean = true,
    ) = ProjectDto(
        projectId = "p1",
        name = "Blade Runner 2049",
        enabled = enabled,
        deleted = deleted,
        markDeleted = markDeleted,
    )

    @Test
    fun `a production marked for deletion is still listed and still openable`() {
        val project = dto(markDeleted = true).toDomain()

        assertTrue(dto(markDeleted = true).isSelectable, "it must survive the list filter")
        assertTrue(project!!.isMarkedDeleted)
        assertTrue(project.isOpenable, "the admin who cancels the deletion can only do it from inside")
    }

    /** The two that genuinely must not be shown are unaffected. */
    @Test
    fun `deleted and disabled productions are still dropped`() {
        assertFalse(dto(deleted = true).isSelectable)
        assertFalse(dto(enabled = false).isSelectable)
    }
}
