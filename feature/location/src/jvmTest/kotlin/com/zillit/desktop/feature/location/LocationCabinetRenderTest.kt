package com.zillit.desktop.feature.location

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.location.cabinet.LocationCabinetViewer
import com.zillit.desktop.feature.location.domain.LocationDraft
import com.zillit.desktop.feature.location.domain.LocationInfo
import com.zillit.desktop.feature.location.domain.LocationMedia
import com.zillit.desktop.feature.location.domain.LocationRepository
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.LocationTransfer
import com.zillit.desktop.feature.location.domain.MediaAttachment
import com.zillit.desktop.feature.location.domain.PickedLocationFile
import kotlin.test.Test
import kotlin.test.assertEquals

/** The File Cabinet's location viewer composed for real: folders, a gallery, and nothing that writes. */
@OptIn(ExperimentalTestApi::class)
class LocationCabinetRenderTest {

    private val viewer = LocationCabinetViewer(
        repository = Fixture(),
        transfer = NoTransfer,
        isTelevision = { false },
        nowMillis = { 1_000L },
        loadImage = { _, _ -> null },
        resolveUser = { null },
    )

    @Test
    fun `the row serves location and opens directly`() {
        assertEquals("location", viewer.identifier)
        assertEquals(false, viewer.expands)
    }

    @Test
    fun `folders show under all three tabs and no upload, add or grouping controls`() = runComposeUiTest {
        setContent { ZillitTheme { viewer.Content(null, Modifier) } }
        waitForIdle()

        onNodeWithText("Old Town Hall").assertExists()
        onNodeWithText("Selected").assertExists()
        onNodeWithText("Shortlisted").assertExists()
        onNodeWithText("Published").assertExists()
        onNodeWithText("Add Link").assertDoesNotExist()
        onNodeWithText("Group By").assertDoesNotExist()
    }

    @Test
    fun `a gallery lists its records and offers neither select nor upload`() = runComposeUiTest {
        setContent { ZillitTheme { viewer.Content(null, Modifier) } }
        waitForIdle()

        onNodeWithText("Old Town Hall").performClick()
        waitForIdle()

        onNodeWithText("Old Town Hall · Sc 12", substring = true).assertExists()
        onNodeWithText("Close").assertExists()
        onNodeWithText("Select").assertDoesNotExist()
        onNodeWithText("Upload here").assertDoesNotExist()
    }

    private class Fixture : LocationRepository {
        override suspend fun info(status: LocationStatus, fileCabinet: Boolean) = ZillitResult.Success(
            listOf(LocationInfo("Old Town Hall", listOf("12"), emptyList(), listOf("Leeds"), 5L, false)),
        )

        override suspend fun media(
            status: LocationStatus, location: String?, sceneNumber: String?,
            episode: String?, beforeMs: Long, next: Boolean, fileCabinet: Boolean,
        ) = ZillitResult.Success(listOf(record()))

        override suspend fun create(draft: LocationDraft, attachment: MediaAttachment?,
            linkPreview: MediaAttachment?): ZillitResult<LocationMedia?> = ZillitResult.Success(null)
        override suspend fun update(id: String, draft: LocationDraft, linkPreview: MediaAttachment?) =
            ZillitResult.Success(Unit)
        override suspend fun delete(ids: List<String>, status: LocationStatus) = ZillitResult.Success(Unit)
        override suspend fun move(
            records: List<LocationMedia>,
            from: LocationStatus,
            to: LocationStatus,
        ) = ZillitResult.Success(Unit)
        override suspend fun pdf(ids: List<String>, includeDetails: Boolean) =
            ZillitResult.Failure(ZillitError.Unknown("no pdf"))

        private fun record() = LocationMedia(
            id = "r1", location = "Old Town Hall", sceneNumber = "12", episodes = emptyList(), city = "Leeds",
            address = "", description = "", contactName = "", email = "", phone = "", countryCode = "",
            link = "", attachment = null, linkAttachment = null, status = LocationStatus.Selected,
            uploadedBy = "u1", createdMs = 1L, updatedMs = 1L, deleted = false, discussion = false,
        )
    }

    private object NoTransfer : LocationTransfer {
        override suspend fun upload(file: PickedLocationFile) = ZillitResult.Failure(ZillitError.Unknown("no"))
        override suspend fun fetch(attachment: MediaAttachment) = ZillitResult.Failure(ZillitError.Unknown("no"))
        override suspend fun saveAndOpen(fileName: String, bytes: ByteArray) =
            ZillitResult.Failure(ZillitError.Unknown("no"))
    }
}
