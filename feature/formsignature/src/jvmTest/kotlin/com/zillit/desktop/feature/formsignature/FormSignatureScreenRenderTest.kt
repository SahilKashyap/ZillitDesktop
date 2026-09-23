package com.zillit.desktop.feature.formsignature

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.formsignature.domain.ChatMember
import com.zillit.desktop.feature.formsignature.domain.ChatUnit
import com.zillit.desktop.feature.formsignature.domain.DocumentSigner
import com.zillit.desktop.feature.formsignature.domain.FormBadgeLeaf
import com.zillit.desktop.feature.formsignature.domain.FormSignatureBadges
import com.zillit.desktop.feature.formsignature.domain.FormSignatureUnread
import com.zillit.desktop.feature.formsignature.domain.FormSignatureViewer
import com.zillit.desktop.feature.formsignature.domain.SignDocument
import com.zillit.desktop.feature.formsignature.domain.SignDocumentTab
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.domain.SignerOption
import com.zillit.desktop.feature.formsignature.ui.FormSignatureEvent
import com.zillit.desktop.feature.formsignature.domain.StandardForm
import com.zillit.desktop.feature.formsignature.domain.StandardFormType
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import com.zillit.desktop.feature.formsignature.ui.ChatState
import com.zillit.desktop.feature.formsignature.ui.DetailSource
import com.zillit.desktop.feature.formsignature.ui.DetailState
import com.zillit.desktop.feature.formsignature.ui.DocumentsState
import com.zillit.desktop.feature.formsignature.ui.DrawState
import com.zillit.desktop.feature.formsignature.ui.FormSignScreen
import com.zillit.desktop.feature.formsignature.ui.FormSignatureScreen
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState
import com.zillit.desktop.feature.formsignature.ui.SignaturesState
import com.zillit.desktop.feature.formsignature.ui.StandardFormsState
import kotlin.test.Test

/**
 * Composes the real screen on every surface, with rows in the tables.
 *
 * `assertExists` rather than `assertIsDisplayed` for anything that can fall
 * below the small test window's fold.
 */
@OptIn(ExperimentalTestApi::class)
class FormSignatureScreenRenderTest {

    private val viewer = FormSignatureViewer(canView = true, canPost = true, isAdmin = true, ready = true)

    private fun pdf(name: String) = StoredDocument(media = "k/$name", name = name)

    private fun state(screen: FormSignScreen) = FormSignatureUiState(
        viewer = viewer,
        currentUserId = "me",
        screen = screen,
        unread = FormSignatureUnread(
            listOf(
                FormBadgeLeaf(FormSignatureBadges.UNIT_GENERAL, FormSignatureBadges.LEVEL_ALL_FORMS, "f1", 2),
                FormBadgeLeaf(FormSignatureBadges.UNIT_FOR_SIGNATURE, FormSignatureBadges.LEVEL_RECEIVED, "", 1),
            ),
        ),
        standard = StandardFormsState(
            rows = listOf(
                StandardForm(
                    id = "f1",
                    serialNo = "3",
                    document = pdf("NDA.pdf"),
                    type = StandardFormType.Contract,
                    uploaderName = "Ada Producer",
                    createdOn = 1_760_000_000_000L,
                ),
            ),
        ),
        documents = DocumentsState(
            tab = SignDocumentTab.Uploaded,
            rows = listOf(
                SignDocument(
                    id = "d1",
                    document = pdf("Deal Memo.pdf"),
                    uploadedBy = "u2",
                    signers = listOf(
                        DocumentSigner(userId = "me", fullName = "Me", signed = false),
                        DocumentSigner(userId = "u2", fullName = "Them", signed = true),
                    ),
                ),
            ),
        ),
        signatures = SignaturesState(
            blocks = listOf(SignatureBlock(id = "s1", isSignature = true, name = "Full")),
        ),
        chat = ChatState(unit = ChatUnit(id = "unit1", members = listOf(ChatMember("me", enabled = true)))),
    )

    private fun compose(
        state: FormSignatureUiState,
        assertion: androidx.compose.ui.test.ComposeUiTest.() -> Unit,
    ) {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    FormSignatureScreen(state = state, onEvent = {})
                }
            }
            assertion()
        }
    }

    @Test
    fun `the hub offers the web's three tiles, sorted, with the guide for admins`() =
        compose(state(FormSignScreen.Tiles)) {
            onNodeWithText("Standard Documents").assertExists()
            onNodeWithText("Documents for Signature").assertExists()
            onNodeWithText("Set/Edit Signature Block").assertExists()
            onNodeWithText("Documents & Signature Guide").assertExists()
        }

    @Test
    fun `a pending member sees only the signature block tile`() =
        compose(state(FormSignScreen.Tiles).copy(viewer = viewer.copy(isPending = true))) {
            onAllNodesWithText("Standard Documents").assertCountEquals(0)
            onNodeWithText("Set/Edit Signature Block").assertExists()
        }

    @Test
    fun `standard documents lists the row with its actions and the chat button`() =
        compose(state(FormSignScreen.StandardDocuments)) {
            onNodeWithText("NDA.pdf").assertExists()
            onNodeWithText("Ada Producer").assertExists()
            onNodeWithText("Contract").assertExists()
            onNodeWithText("Add to My Downloads").assertExists()
            onNodeWithText("Chat with Users").assertExists()
            onNodeWithText("Upload Document").assertExists()
        }

    @Test
    fun `documents for signature shows the notes and the segments`() =
        compose(state(FormSignScreen.DocumentsForSignature)) {
            onNodeWithText("Deal Memo.pdf").assertExists()
            onNodeWithText("Them").assertExists()
            onNodeWithText("Send for signature").assertExists()
            onNodeWithText("Received for signature").assertExists()
            onNodeWithText("Fully signed Document").assertExists()
            onNodeWithText("Note 1:").assertExists()
        }

    @Test
    fun `the signature block shows the saved card and offers the missing initials`() =
        compose(state(FormSignScreen.SignatureBlock)) {
            onNodeWithText("Full").assertExists()
            onNodeWithText("Add Initials").assertExists()
            onAllNodesWithText("Add Signature").assertCountEquals(0)
        }

    @Test
    fun `the pad page names what it draws`() =
        compose(state(FormSignScreen.DrawSignature).copy(draw = DrawState(isSignature = false))) {
            onNodeWithText("Add Initials").assertExists()
            onNodeWithText("Save Initials").assertExists()
        }

    @Test
    fun `a received document offers download, print and send`() =
        compose(
            state(FormSignScreen.Detail).copy(
                detail = DetailState(
                    source = DetailSource.ForSignature(SignDocumentTab.Received),
                    documentId = "d1",
                    title = "Deal Memo.pdf",
                    stored = pdf("Deal Memo.pdf"),
                    loadingPages = false,
                    placeholderFlow = true,
                    canSign = true,
                ),
            ),
        ) {
            onNodeWithText("Download in device").assertExists()
            onNodeWithText("Print").assertExists()
            onNodeWithText("Send Document").assertExists()
            onAllNodesWithText("Add Signature").assertCountEquals(0)
        }

    @Test
    fun `the discussion room shows its Select User control for an admin`() =
        compose(state(FormSignScreen.Chat)) {
            onNodeWithText("Select User").assertExists()
        }

    @Test
    fun `a blocked viewer gets the refusal`() =
        compose(state(FormSignScreen.Tiles).copy(viewer = FormSignatureViewer(canView = false, ready = true))) {
            onNodeWithText("You don’t have access to Documents & Signature on this project. " +
                "Access is granted per tool, by the project’s admin.").assertExists()
        }

    @Test
    fun `a row in the receiver picker chooses that person`() {
        val picked = mutableListOf<FormSignatureEvent>()
        val chatState = state(FormSignScreen.Chat).let {
            it.copy(chat = it.chat.copy(pickingReceiver = true, options = listOf(SignerOption("u9", "Peach Android"))))
        }
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) { FormSignatureScreen(state = chatState, onEvent = { picked += it }) }
            }
            onNodeWithText("Peach Android").performClick()
            val expected = FormSignatureEvent.ChooseReceiver(SignerOption("u9", "Peach Android"))
            kotlin.test.assertEquals(listOf<FormSignatureEvent>(expected), picked)
        }
    }
}
