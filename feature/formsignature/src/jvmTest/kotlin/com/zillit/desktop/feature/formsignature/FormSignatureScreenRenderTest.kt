package com.zillit.desktop.feature.formsignature

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.formsignature.domain.DocumentSigner
import com.zillit.desktop.feature.formsignature.domain.FormSignatureViewer
import com.zillit.desktop.feature.formsignature.domain.SignDocument
import com.zillit.desktop.feature.formsignature.domain.SignatureBlock
import com.zillit.desktop.feature.formsignature.domain.StandardForm
import com.zillit.desktop.feature.formsignature.domain.StandardFormType
import com.zillit.desktop.feature.formsignature.domain.StoredDocument
import com.zillit.desktop.feature.formsignature.ui.DocumentsState
import com.zillit.desktop.feature.formsignature.ui.FormSignatureArea
import com.zillit.desktop.feature.formsignature.ui.FormSignatureScreen
import com.zillit.desktop.feature.formsignature.ui.FormSignatureUiState
import com.zillit.desktop.feature.formsignature.ui.SignaturesState
import com.zillit.desktop.feature.formsignature.ui.StandardFormsState
import kotlin.test.Test

/**
 * Composes the real screen on every area, with rows in the tables.
 *
 * `assertExists` rather than `assertIsDisplayed` for anything that can fall
 * below the small test window's fold.
 */
@OptIn(ExperimentalTestApi::class)
class FormSignatureScreenRenderTest {

    private val viewer = FormSignatureViewer(canView = true, canPost = true, ready = true)

    private fun pdf(name: String) = StoredDocument(media = "k/$name", name = name)

    private fun state(area: FormSignatureArea) = FormSignatureUiState(
        viewer = viewer,
        currentUserId = "me",
        area = area,
        standard = StandardFormsState(
            rows = listOf(
                StandardForm(
                    id = "f1",
                    serialNo = "3",
                    document = pdf("NDA.pdf"),
                    type = StandardFormType.Contract,
                    uploaderName = "Ada Producer",
                ),
            ),
        ),
        documents = DocumentsState(
            rows = listOf(
                SignDocument(
                    id = "d1",
                    document = pdf("Deal Memo.pdf"),
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
    )

    private fun compose(area: FormSignatureArea, assertion: androidx.compose.ui.test.ComposeUiTest.() -> Unit) {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    FormSignatureScreen(state = state(area), onEvent = {})
                }
            }
            assertion()
        }
    }

    @Test
    fun `the hub offers its three tiles`() = compose(FormSignatureArea.Hub) {
        onNodeWithText("Standard forms & contracts").assertExists()
        onNodeWithText("Documents for signature").assertExists()
        onNodeWithText("Signature block").assertExists()
    }

    @Test
    fun `the library lists its rows`() = compose(FormSignatureArea.StandardForms) {
        onNodeWithText("NDA.pdf").assertExists()
        onNodeWithText("Contract").assertExists()
        onNodeWithText("Ada Producer").assertExists()
        onNodeWithText("Upload document").assertExists()
    }

    @Test
    fun `the documents area counts signatures`() = compose(FormSignatureArea.Documents) {
        onNodeWithText("Deal Memo.pdf").assertExists()
        onNodeWithText("1 of 2 signed").assertExists()
        onNodeWithText("Upload & send").assertExists()
    }

    @Test
    fun `the signature area shows the block and offers initials`() =
        compose(FormSignatureArea.Signatures) {
            onNodeWithText("Signature", substring = false).assertExists()
            onNodeWithText("Not set up yet. You need this before you can sign anything.")
                .assertExists()
        }

    @Test
    fun `a blocked viewer gets the refusal`() {
        runComposeUiTest {
            setContent {
                ZillitTheme(darkTheme = false) {
                    FormSignatureScreen(
                        state = FormSignatureUiState(
                            viewer = FormSignatureViewer(
                                canView = false,
                                canPost = false,
                                ready = true,
                            ),
                        ),
                        onEvent = {},
                    )
                }
            }
            onNodeWithText(
                "You don’t have access to Documents & Signature on this production. " +
                    "Access is granted per tool, by the production’s admin.",
            ).assertExists()
        }
    }
}
