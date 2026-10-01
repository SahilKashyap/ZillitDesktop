package com.zillit.desktop.feature.costumesetsync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.costumesetsync.data.SyncOnsetApi
import com.zillit.desktop.feature.costumesetsync.domain.PickedFile
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.StoredFile
import com.zillit.desktop.feature.costumesetsync.domain.SyncHost
import com.zillit.desktop.feature.costumesetsync.domain.SyncProject
import com.zillit.desktop.feature.costumesetsync.domain.SyncViewer
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.SyncNav
import com.zillit.desktop.feature.costumesetsync.ui.SyncRoute
import com.zillit.desktop.feature.costumesetsync.ui.screens.DocumentViewerDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.ProjectDocuments
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The kept-document viewer's file pane against a generated PDF and a Final Draft file. The first test always runs
 * (the viewer shows the PDF's page count once the bytes have been rendered); the shots are opt-in (SYNC_SHOTS=1).
 */
@OptIn(ExperimentalTestApi::class)
class ShotsDocsTest {
    private val meta = """{"int_ext":["INT","EXT"],"times_of_day":["DAY","NIGHT"],"finance_roles":["ADMIN"]}"""

    private fun scenes(): String = (1..8).joinToString(",", "[", "]") { i ->
        """{"_id":"s$i","number":"$i","name":"Scene $i","status":"PLANNED","int_ext":"INT",
        "location":"Kitchen of the farm house $i","shoot_date":1772755200000}"""
    }

    private fun doc(id: String, name: String, latest: Boolean, created: Long, extra: String = "") =
        """{"_id":"$id","kind":"SCRIPT","source":"${if (latest) "ZILLIT" else "UPLOAD"}","file_name":"$name",
        "latest":$latest,"created":$created,"revision":"${if (latest) "Pink" else "White"}",
        "attachment":{"name":"$name","signed_url":"https://files.test/$id"}$extra}"""

    private fun docsJson(vararg docs: String) = docs.joinToString(",", "[", "]")

    /** What the files hold, by address: the host's `fetch`. */
    private class FileHost(private val files: Map<String, ByteArray>) : SyncHost {
        override suspend fun pick(extensions: Set<String>, multiple: Boolean): List<PickedFile> = emptyList()
        override suspend fun store(file: PickedFile): ZillitResult<StoredFile> =
            ZillitResult.Failure(ZillitError.Unknown("no store"))
        override suspend fun resolveUrl(media: String, bucket: String, region: String): String? = null
        override fun openUrl(url: String) = Unit
        override suspend fun save(suggestedName: String, bytes: ByteArray): Boolean = false
        override suspend fun fetch(url: String): ByteArray? = files[url]
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private fun ctx(docs: String, host: SyncHost, canDownload: Boolean = true): SyncCtx {
        val engine = MockEngine {
            val path = it.url.toString().substringBefore('?')
            val data = when {
                path.endsWith("/documents") -> docs
                path.endsWith("/scenes") -> scenes()
                path.endsWith("/meta") -> meta
                else -> "[]"
            }
            respond(
                """{"status":1,"data":$data}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = ApiClient(
            HttpClientFactory.create({ Factory(engine) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        )
        val config = AppConfig(
            Environment.Develop,
            ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
            emptyMap(),
        )
        fun obj(s: String) = Rec(Json.parseToJsonElement(s) as JsonObject)
        return SyncCtx(
            api = SyncOnsetApi(client, config, projectId = { "p1" }),
            viewer = SyncViewer(
                resolved = true, enabled = true, canView = true, canPost = true, canDownload = canDownload,
            ),
            project = SyncProject(obj("""{"project_name":"Demo","my_role":"ADMIN"}"""), setOf("ADMIN")),
            meta = obj(meta),
            nav = SyncNav(SyncRoute.parse("breakdown")),
            scope = CoroutineScope(Dispatchers.Unconfined),
            projectId = "p1",
            now = { 1_772_755_200_000L },
            host = host,
        )
    }

    private fun recs(json: String): List<Rec> =
        (Json.parseToJsonElement(json) as JsonArray).map { Rec(it as JsonObject) }

    /** Pumps the clock while the mock and the renderer answer on their own threads, until [text] is on screen. */
    private fun SkikoComposeUiTest.await(text: String, then: Int = 4) {
        val end = System.currentTimeMillis() + AWAIT_MS
        while (System.currentTimeMillis() < end && onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            Thread.sleep(POLL_MS)
            mainClock.advanceTimeBy(POLL_MS)
            waitForIdle()
        }
        assertTrue(
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty(),
            "never saw \"$text\": " + onAllNodes(isRoot()).let { r ->
                (0 until r.fetchSemanticsNodes().size).joinToString { k -> r[k].printToString(DUMP) }
            },
        )
        repeat(then) {
            Thread.sleep(POLL_MS)
            mainClock.advanceTimeBy(POLL_MS)
            waitForIdle()
        }
    }

    private fun SkikoComposeUiTest.snap(name: String) {
        val roots = onAllNodes(isRoot())
        val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
        val dir = File("build/shots").apply { mkdirs() }
        File(dir, "docs_$name.png").writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData()!!.bytes)
    }

    private fun view(
        name: String,
        docs: String,
        files: Map<String, ByteArray>,
        waitFor: String,
        canDownload: Boolean = true,
        steps: (SkikoComposeUiTest.() -> Unit)? = null,
    ) = runSkikoComposeUiTest(size = Size(1240f, 900f)) {
        val context = ctx(docs, FileHost(files), canDownload)
        setContent {
            ZillitTheme(animateThemeChange = false) {
                CompositionLocalProvider(LocalSync provides context) {
                    Box(Modifier.size(1240.dp, 900.dp)) {
                        val d = remember { ProjectDocuments(context, "SCRIPT") }
                        LaunchedEffect(Unit) { d.reload() }
                        DocumentViewerDialog(true, "SCRIPT", d, recs(scenes()), {}, {})
                    }
                }
            }
        }
        await(waitFor)
        steps?.invoke(this)
        if (System.getenv("SYNC_SHOTS") != null) snap(name)
    }

    private val script = docsJson(doc("d1", "The Long Farewell - Script v3.pdf", true, 1_772_755_200_000L))

    @Test
    fun viewerRendersThePdfsPagesInThePane() = view(
        "pdf",
        script,
        mapOf("https://files.test/d1" to samplePdf(12)),
        waitFor = "Page 1 of 12",
    )

    @Test
    fun viewerZoomsAndFitsWidth() = view(
        "pdf_zoom",
        script,
        mapOf("https://files.test/d1" to samplePdf(3)),
        waitFor = "Page 1 of 3",
    ) {
        repeat(3) { onNodeWithContentDescription("Zoom in").performClick(); waitForIdle() }
        await("200%")
        if (System.getenv("SYNC_SHOTS") != null) snap("pdf_zoom_200")
        onNodeWithContentDescription("Fit to width").performClick()
        await("100%")
        assertTrue(onAllNodesWithContentDescription("Fit to width").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun viewerShowsAFinalDraftFileAsText() {
        val fdx = """<FinalDraft><Content>
            <Paragraph Type="Scene Heading"><Text>Int. Kitchen - Night</Text></Paragraph>
            <Paragraph Type="Action"><Text>Anna stands at the sink, looking out into the rain.</Text></Paragraph>
            <Paragraph Type="Character"><Text>Anna</Text></Paragraph>
            <Paragraph Type="Dialogue"><Text>It never stops, does it.</Text></Paragraph>
            </Content></FinalDraft>"""
        view(
            "fdx",
            docsJson(doc("d2", "Farewell_white.fdx", true, 1_771_755_200_000L)),
            mapOf("https://files.test/d2" to fdx.encodeToByteArray()),
            waitFor = "INT. KITCHEN - NIGHT",
        )
    }

    @Test
    fun viewerKeepsTheOpenFallbackForWhatItCannotShow() = view(
        "other",
        docsJson(doc("d3", "Schedule.xlsx", true, 1_771_755_200_000L)),
        mapOf("https://files.test/d3" to byteArrayOf(0x50, 0x4B, 3, 4)),
        waitFor = "Open in new tab",
    )

    @Test
    fun viewerWithoutDownloadRightsHasNoOpenButtonOnAnUnshowableFile() = view(
        "other_locked",
        docsJson(doc("d3", "Schedule.xlsx", true, 1_771_755_200_000L)),
        mapOf("https://files.test/d3" to byteArrayOf(0x50, 0x4B, 3, 4)),
        waitFor = "This file type can't be shown here.",
        canDownload = false,
    )

    @Test
    fun viewerSaysSoWhenTheFileWillNotCome() = view(
        "failed",
        script,
        emptyMap(),
        waitFor = "This file could not be opened here.",
    )

    private companion object {
        const val DUMP = 12
        const val AWAIT_MS = 15_000L
        const val POLL_MS = 100L
    }
}
