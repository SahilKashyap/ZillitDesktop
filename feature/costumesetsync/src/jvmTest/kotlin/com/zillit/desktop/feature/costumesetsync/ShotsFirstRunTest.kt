package com.zillit.desktop.feature.costumesetsync

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
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
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.SyncFrame
import com.zillit.desktop.feature.costumesetsync.ui.SyncNav
import com.zillit.desktop.feature.costumesetsync.ui.SyncRoute
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
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test

/** Opt-in (SYNC_SHOTS=1): the first-run form in the web's states, light and dark, at 1240x800. */
@OptIn(ExperimentalTestApi::class)
class ShotsFirstRunTest {
    private class ScriptHost : SyncHost {
        override suspend fun pick(extensions: Set<String>, multiple: Boolean) =
            listOf(PickedFile("DN6 - Blue Revisions.fdx", ByteArray(8), "application/xml"))
        override suspend fun store(file: PickedFile): ZillitResult<StoredFile> =
            ZillitResult.Failure(ZillitError.Unknown("no store"))
        override suspend fun resolveUrl(media: String, bucket: String, region: String): String? = null
        override fun openUrl(url: String) = Unit
        override suspend fun save(suggestedName: String, bytes: ByteArray): Boolean = false
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private fun ctx(type: String?, canPost: Boolean): SyncCtx {
        val engine = MockEngine {
            respond(
                """{"status":1,"data":[]}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
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
        val typeJson = type?.let { ""","type":"$it"""" }.orEmpty()
        val record = """{"project_name":"DN6","my_role":"VIEWER","counts":{"scenes":0,"characters":0,"costumes":0,
            "actors":0,"members":1}$typeJson}"""
        return SyncCtx(
            api = SyncOnsetApi(client, config, projectId = { "p1" }),
            viewer = SyncViewer(resolved = true, enabled = true, canView = true, canPost = canPost, canDownload = true),
            project = SyncProject(Rec(Json.parseToJsonElement(record) as JsonObject), emptySet()),
            meta = Rec(Json.parseToJsonElement("{}") as JsonObject),
            nav = SyncNav(SyncRoute.parse("dashboard")),
            scope = CoroutineScope(Dispatchers.Unconfined),
            projectId = "p1",
            now = { 1_772_755_200_000L },
            host = ScriptHost(),
        )
    }

    private fun shot(
        name: String,
        type: String? = "FEATURE",
        canPost: Boolean = true,
        script: (ComposeUiTest.() -> Unit)? = null,
    ) {
        if (System.getenv("SYNC_SHOTS") == null) return
        for (dark in listOf(false, true)) {
            runSkikoComposeUiTest(size = Size(WIDTH.toFloat(), HEIGHT.toFloat())) {
                val context = ctx(type, canPost)
                setContent {
                    ZillitTheme(darkTheme = dark, animateThemeChange = false) {
                        CompositionLocalProvider(LocalSync provides context) {
                            Column(Modifier.size(WIDTH.dp, HEIGHT.dp).background(ZillitTheme.colors.canvas)) {
                                SyncFrame(null, 0, false, {}, {})
                            }
                        }
                    }
                }
                waitForIdle()
                script?.invoke(this)
                waitForIdle()
                val roots = onAllNodes(isRoot())
                val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
                val dir = File("build/shots").apply { mkdirs() }
                val file = File(dir, "fr_${name}_${if (dark) "dark" else "light"}.png")
                file.writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData()!!.bytes)
            }
        }
    }

    private fun ComposeUiTest.choose(text: String) {
        onNodeWithText(text).performClick()
        waitForIdle()
    }

    @Test fun typeKnown() = shot("type_known")
    @Test fun typeUnknown() = shot("type_unknown", type = null)
    @Test fun scriptPicked() = shot("script_picked") { choose("Drag and Drop File") }
    @Test fun prepAndWrap() = shot("prep_wrap") {
        choose("Drag and Drop File")
        choose("Add prep dates")
        choose("Add wrap dates")
    }
    @Test fun typeChosen() = shot("type_chosen", type = null) {
        choose("TV Series")
        choose("Drag and Drop File")
    }
    @Test fun noRights() = shot("no_rights", canPost = false)

    private companion object {
        const val WIDTH = 1240
        const val HEIGHT = 800
    }
}
