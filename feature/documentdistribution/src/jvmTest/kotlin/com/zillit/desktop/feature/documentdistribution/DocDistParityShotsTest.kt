package com.zillit.desktop.feature.documentdistribution

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.documentdistribution.domain.Contact
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryFolder
import com.zillit.desktop.feature.documentdistribution.domain.MediaKind
import com.zillit.desktop.feature.documentdistribution.domain.Recipient
import com.zillit.desktop.feature.documentdistribution.ui.ComposerStage
import com.zillit.desktop.feature.documentdistribution.ui.ComposerState
import com.zillit.desktop.feature.documentdistribution.ui.DocDistDestination
import com.zillit.desktop.feature.documentdistribution.ui.DocDistScreen
import com.zillit.desktop.feature.documentdistribution.ui.DocDistUiState
import com.zillit.desktop.feature.documentdistribution.ui.MergeState
import com.zillit.desktop.feature.documentdistribution.domain.DistributionList
import java.io.File
import kotlin.test.Test

/**
 * Opt-in pictures of the two surfaces the 2026-10 web parity pass changed, for
 * eyeballing in both themes: `DOCDIST_SHOTS=<dir>`.
 *
 * - the library's **full-bleed toolbar** (the web's `.dd-topcard`, 8abfe538c);
 * - the composer's Preview, whose body is the **email's white page** in both
 *   themes (the web's `.dd-html-body`, 3e04c287d).
 *
 * Both are layout and colour, which no assertion reads well — same idiom as
 * Invoices' `shell screenshots when asked for`. The canvas is painted because
 * the dark theme's tone colours are translucent and composite wrongly over
 * `ImageComposeScene`'s white default.
 */
class DocDistParityShotsTest {

    private val viewer = DocDistViewer(
        userId = "u1",
        userEmail = "coordinator@example.com",
        canView = true,
        canPost = true,
        canDownload = true,
        ready = true,
    )

    private val pdf = LibraryDocument(
        id = "doc-1",
        name = "Call Sheet Day 12.pdf",
        folderId = "f1",
        contentType = "application/pdf",
        mediaKind = MediaKind.Document,
        sizeBytes = 248_000,
        documentDate = "2026-10-20",
    )

    private fun library() = DocDistUiState(
        viewer = viewer,
        destination = DocDistDestination.Library,
        folders = listOf(
            LibraryFolder(id = "f1", name = "Call Sheets", description = "Every unit, every day"),
            LibraryFolder(id = "f2", name = "Day 12", parentId = "f1"),
        ),
        currentFolderId = "f1",
        documents = listOf(pdf),
        totalDocuments = 1,
        contacts = listOf(Contact("ada@x.co", "Ada Lovelace", "1st AD")),
        infoBannerDismissed = true,
    )

    private fun composerPreview() = library().copy(
        composer = ComposerState(
            open = true,
            stage = ComposerStage.Preview,
            subject = "Call Sheet — Day 12",
            body = "Morning all,\n\nDay 12's call sheet is attached. Unit base moves to " +
                "the Dockside lot — leave earlier than usual.\n\nThanks,\nAda",
            to = listOf(Recipient("ada@x.co", "Ada Lovelace"), Recipient("grace@x.co", "Grace Hopper")),
            attachments = listOf(pdf),
            watermarked = setOf(pdf.id),
        ),
    )

    @Test
    fun `parity screenshots when asked for`() {
        val dir = System.getenv("DOCDIST_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        val merge = library().copy(
            lists = listOf(
                DistributionList("l1", "Full unit", listOf(Recipient("ada@x.co", "Ada Lovelace"))),
                DistributionList("l2", "Heads of department", emptyList()),
            ),
            merge = MergeState(documents = listOf(pdf), folderName = "Call Sheets"),
            contacts = listOf(Contact("vendor@hire.co", "Hire Co", "Equipment")),
        )
        listOf(
            "library" to library(),
            "composer-preview" to composerPreview(),
            "merge" to merge,
        ).forEach { (name, state) ->
            listOf(false, true).forEach { dark ->
                val scene = ImageComposeScene(width = 2880, height = 1700, density = Density(2f)) {
                    ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                        Box(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
                            DocDistScreen(state = state, onEvent = {})
                        }
                    }
                }
                // Frame 0 lies about scrollables, and the dialog shell fades in.
                repeat(3) { scene.render(it * 500_000_000L) }
                val image = scene.render(2_000_000_000L)
                File(dir, "docdist-$name-${if (dark) "dark" else "light"}.png")
                    .writeBytes(image.encodeToData()!!.bytes)
                scene.close()
            }
        }
    }
}
