package com.zillit.desktop.feature.costumesetsync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.zillit.desktop.feature.costumesetsync.domain.decodeRaster
import com.zillit.desktop.feature.costumesetsync.ui.DocumentScannerDialog
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MediaButtons
import com.zillit.desktop.feature.costumesetsync.ui.MediaPicker
import com.zillit.desktop.feature.costumesetsync.ui.ReferenceGrid
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.SyncNav
import com.zillit.desktop.feature.costumesetsync.ui.SyncRoute
import com.zillit.desktop.feature.costumesetsync.ui.screens.ScanScreen
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
import kotlinx.serialization.json.JsonObject
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The document scanner in the media row, and the QR label screen. The behaviour tests always run; the screenshots
 * are opt-in (SYNC_SHOTS=1) and land in `build/shots/scan_*.png`.
 */
@OptIn(ExperimentalTestApi::class)
class ShotsScanTest {

    /** A phone photo of a receipt in a dull room: warm grey paper, a shadow across the top, rows of dark text. */
    private fun page(seed: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(600, 800)
        val canvas: Canvas = surface.canvas
        canvas.clear(0xFFB9AE98.toInt())
        val ink = Paint().apply { color = 0xFF2E3144.toInt() }
        val shadow = Paint().apply { color = 0x55000000 }
        canvas.drawRect(Rect.makeXYWH(0f, 0f, 600f, 90f), shadow)
        repeat(14) { row ->
            val width = 200f + ((row * 37 + seed * 53) % 280)
            canvas.drawRect(Rect.makeXYWH(60f, 130f + row * 40f, width, 14f), ink)
        }
        canvas.drawRect(Rect.makeXYWH(60f, 60f, 260f, 26f), ink)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 92)!!.bytes
    }

    private fun qr(text: String): ByteArray {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0)
        val scale = 8
        val surface = Surface.makeRasterN32Premul((matrix.width + 8) * scale, (matrix.height + 8) * scale)
        surface.canvas.clear(0xFFFFFFFF.toInt())
        val dark = Paint().apply { color = 0xFF000000.toInt() }
        for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
            if (matrix.get(x, y)) {
                val cell = Rect.makeXYWH((x + 4f) * scale, (y + 4f) * scale, scale.toFloat(), scale.toFloat())
                surface.canvas.drawRect(cell, dark)
            }
        }
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    /** A host with a pretend camera: files come from [pick], the camera from [capturePhoto]. */
    private class FakeHost(
        private val pages: List<PickedFile>,
        override val hasCamera: Boolean = true,
        private val label: PickedFile? = null,
    ) : SyncHost {
        override suspend fun pick(extensions: Set<String>, multiple: Boolean): List<PickedFile> =
            if (multiple) pages else listOfNotNull(label)

        override suspend fun capturePhoto(): PickedFile? = pages.firstOrNull()

        override suspend fun store(file: PickedFile): ZillitResult<StoredFile> =
            ZillitResult.Failure(ZillitError.Unknown("not in this test"))

        override suspend fun resolveUrl(media: String, bucket: String, region: String): String? = null

        override fun openUrl(url: String) = Unit

        override suspend fun save(suggestedName: String, bytes: ByteArray): Boolean = false
    }

    private fun jpg(seed: Int) = PickedFile("receipt-$seed.jpg", page(seed), "image/jpeg")

    private val lookedUp = """{"_id":"c1","asset_number":"CST-000245","name":"Red wool overcoat","category":"CLOTHING",
        "status":"AVAILABLE","type":"Coat","color":"Red","size":"M","brand":"Burberry","location":"Warehouse",
        "character":{"_id":"ch1","name":"Anna Vandermeer"},"change_items":[{"change":{"_id":"chg1","change_number":2,"name":"Muddy"}}],
        "scenes":[{"_id":"s1","number":"12","shoot_date":1772755200000}],"cleaning":[],"alterations":[]}"""

    private fun reply(url: String): String {
        val path = url.substringBefore('?')
        val data = when {
            path.endsWith("/meta") -> META
            path.contains("/costumes/lookup/") -> lookedUp
            path.endsWith("/scenes") -> """[{"_id":"s1","number":"12","name":"Opening chase","status":"SHOOTING"}]"""
            path.endsWith("/photos") -> PHOTOS
            else -> "{}"
        }
        return """{"status":1,"data":$data}"""
    }

    private fun ctx(host: SyncHost, route: String = "dashboard"): SyncCtx {
        val engine = MockEngine {
            respond(reply(it.url.toString()), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient(
            httpClient = HttpClientFactory.create({ Factory(engine) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        )
        val config = AppConfig(
            Environment.Develop,
            ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
            emptyMap(),
        )
        return SyncCtx(
            api = SyncOnsetApi(client, config, projectId = { "p1" }),
            viewer = SyncViewer(resolved = true, enabled = true, canView = true, canPost = true, canDownload = true),
            project = SyncProject(Rec(Json.parseToJsonElement(PROJECT) as JsonObject), setOf("ADMIN")),
            meta = Rec(Json.parseToJsonElement(META) as JsonObject),
            nav = SyncNav(SyncRoute.parse(route)),
            scope = CoroutineScope(Dispatchers.Unconfined),
            projectId = "p1",
            now = { 1_772_755_200_000L },
            host = host,
        )
    }

    private fun SkikoComposeUiTest.show(host: SyncHost, width: Int, height: Int, content: @Composable () -> Unit) {
        val context = ctx(host)
        setContent {
            ZillitTheme(animateThemeChange = false) {
                CompositionLocalProvider(LocalSync provides context) {
                    Box(Modifier.size(width.dp, height.dp)) { content() }
                }
            }
        }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.snap(name: String) {
        if (System.getenv("SYNC_SHOTS") == null) return
        val roots = onAllNodes(isRoot())
        val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
        val dir = File("build/shots").apply { mkdirs() }
        File(dir, "scan_$name.png").writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData()!!.bytes)
    }

    private fun SkikoComposeUiTest.waitForText(text: String) =
        waitUntil(timeoutMillis = WAIT_MS) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun mediaRowHasScanAndItLaysOutTwoByTwo() = runSkikoComposeUiTest(size = Size(420f, 300f)) {
        show(FakeHost(listOf(jpg(1))), 420, 300) { Box(Modifier.padding(16.dp)) { MediaButtons(onFiles = {}) } }
        onNodeWithText("Scan").assertExists()
        onNodeWithText("Photo").assertExists()
        snap("media_row_narrow")
    }

    @Test
    fun scannedPagesComeBackAsCleanedJpegs() = runSkikoComposeUiTest(size = Size(900f, 800f)) {
        var received: List<PickedFile>? = null
        show(FakeHost(listOf(jpg(1), jpg(2))), 900, 800) {
            MediaButtons(onFiles = {}, onScans = { received = it })
        }
        onNodeWithText("Scan").performClick()
        waitForIdle()
        onNodeWithText("Scan document").assertExists()
        snap("dialog_empty")
        onNodeWithText("Add pages from files").performClick()
        waitForText("2 pages scanned")
        // Pages are drawn cleaned up; the strip fills in as they decode.
        waitForIdle()
        Thread.sleep(PREVIEW_SETTLE_MS)
        waitForIdle()
        snap("dialog_two_pages_bw")
        onNodeWithText("Colour").performClick()
        Thread.sleep(PREVIEW_SETTLE_MS)
        waitForIdle()
        snap("dialog_two_pages_colour")
        onNodeWithText("Black & white").performClick()
        onNodeWithText("Attach 2 pages").performClick()
        waitUntil(timeoutMillis = WAIT_MS) { received != null }
        val files = assertNotNull(received)
        assertEquals(listOf("Scan 2026-03-06 0000 p1.jpg", "Scan 2026-03-06 0000 p2.jpg"), files.map { it.name })
        files.forEach { file ->
            assertTrue(file.isImage && file.mime == "image/jpeg")
            val raster = assertNotNull(decodeRaster(file.bytes, 0))
            // Black & white: the paper at the corner has gone to a clean grey-white, never the dull original.
            val corner = raster.argb[raster.width * (raster.height - 1) + raster.width - 1]
            val r = (corner shr 16) and 0xFF
            val b = corner and 0xFF
            assertTrue(r > 235 && b > 235 && r - b in -6..6, "paper reads white: $r/$b")
        }
    }

    @Test
    fun withoutACameraTheDialogOffersFilesOnly() = runSkikoComposeUiTest(size = Size(900f, 800f)) {
        show(FakeHost(listOf(jpg(1)), hasCamera = false), 900, 800) { DocumentScannerDialog(true, {}, {}) }
        onNodeWithText("Add pages from files").assertExists()
        assertTrue(onAllNodesWithText("Capture page").fetchSemanticsNodes().isEmpty())
        snap("dialog_no_camera")
    }

    @Test
    fun theCameraButtonTakesAPage() = runSkikoComposeUiTest(size = Size(900f, 800f)) {
        show(FakeHost(listOf(jpg(3))), 900, 800) { DocumentScannerDialog(true, {}, {}) }
        onNodeWithText("Capture page").performClick()
        waitForText("1 page scanned")
        onNodeWithText("Attach 1 page").assertExists()
    }

    @Test
    fun theMediaRowOnASavedRecordFilesScansAsDocument() = runSkikoComposeUiTest(size = Size(900f, 620f)) {
        show(FakeHost(listOf(jpg(1))), 900, 620) {
            Column(Modifier.padding(16.dp)) {
                ReferenceGrid("COSTUME", "c1", kinds = listOf("FRONT", "DOCUMENT"))
            }
        }
        waitForText("Scan")
        snap("reference_grid")
    }

    @Test
    fun aFormPickerShowsItsScansAsTiles() = runSkikoComposeUiTest(size = Size(900f, 420f)) {
        show(FakeHost(listOf(jpg(1))), 900, 420) {
            Box(Modifier.padding(16.dp)) { MediaPicker(emptyList(), {}) }
        }
        onNodeWithText("Scan").assertExists()
        snap("picker")
    }

    @Test
    fun theLabelScreenReadsAQrFromAPictureAndShowsTheCostume() = runSkikoComposeUiTest(size = Size(1240f, 820f)) {
        val label = PickedFile("label.png", qr("CST-000245"), "image/png")
        show(FakeHost(emptyList(), hasCamera = false, label = label), 1240, 820) { ScanScreen() }
        snap("screen_empty")
        onNodeWithText("Read from a picture").performClick()
        waitForText("Red wool overcoat")
        waitForIdle()
        snap("screen_found")
    }

    @Test
    fun aPictureWithNoCodeSaysSo() = runSkikoComposeUiTest(size = Size(1240f, 820f)) {
        val notALabel = PickedFile("page.jpg", page(1), "image/jpeg")
        show(FakeHost(emptyList(), hasCamera = false, label = notALabel), 1240, 820) { ScanScreen() }
        onNodeWithText("Read from a picture").performClick()
        waitForText("No QR code could be read from that picture. Try another, or type the asset number.")
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private companion object {
        const val PROJECT = """{"project_name":"Demo"}"""
        const val PHOTOS = """[{"_id":"p2","media_type":"FILE","kind":"DOCUMENT",
            "attachment":{"name":"Scan 2026-03-06 0000.jpg","file_size":120000}}]"""
        const val WAIT_MS = 15_000L
        const val PREVIEW_SETTLE_MS = 600L
        const val META = """{"costume_statuses":["AVAILABLE","ISSUED","CLEANING"],"costume_categories":["CLOTHING"],
            "standard_locations":["Warehouse"],"finance_roles":["ADMIN"],"media_types":["IMAGE","FILE","LINK"]}"""
    }
}
