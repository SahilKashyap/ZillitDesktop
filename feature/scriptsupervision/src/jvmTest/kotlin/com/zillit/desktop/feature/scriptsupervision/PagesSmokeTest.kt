package com.zillit.desktop.feature.scriptsupervision

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.scriptsupervision.data.ScriptSupervisionApi
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptSupervisionViewer
import com.zillit.desktop.feature.scriptsupervision.ui.LocalScenes
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.ScenesState
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
import com.zillit.desktop.feature.scriptsupervision.ui.SsRoutes
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
import kotlin.test.Test

/**
 * Every page of the tool, composed against a canned service.
 *
 * It cannot say a screen is right; it says none of them throws when it first
 * draws — which is the class of fault this app's tables keep producing and
 * which compiles perfectly: a `ZillitDataTable` measured against infinite
 * constraints inside a scrolling page, and a screen reading a field the
 * service left out. Both only show here.
 *
 * Driven off [SsPage] rather than a hand-written list, so a page added to the
 * menu without a screen fails this test rather than a reader's window.
 */
@OptIn(ExperimentalTestApi::class)
class PagesSmokeTest {

    private val scene = """{"_id":"s1","scene_number":"14","scene_name":"INT. KITCHEN","int_ext":"INT",
        "day_night":"DAY","shooting_day":2,"location":"Unit base","status":"in_progress"}"""

    private fun reply(url: String): String {
        val path = url.substringBefore('?')
        val data = when {
            path.contains("/scenes/") -> """{"data":[$scene],"total":1}"""
            path.contains("/takes/") -> """{"data":[{"_id":"t1","scene_id":"s1","take_number":3,
                "slate_number":"14A","camera_roll":"A001","sound_roll":"S001","is_circled":true}],"total":1}"""
            // Every continuity folder answers a plain array, newest first.
            path.contains("/continuity/") -> """[{"_id":"r1","scene_id":"s1","notes":"note",
                "camera":"A","lens_mm":35,"shot_size":"MS","sound_roll":"S001","track_number":2,
                "character":"ANNA","risk_level":"high","plate_type":"clean_plate","status":"captured",
                "preferred_take":3,"page_number":"12","pages_shot":"1 2/8","setups":4}]"""
            else -> "[]"
        }
        return """{"status":1,"data":$data}"""
    }

    private fun context(): SsCtx {
        val engine = MockEngine { request ->
            respond(
                reply(request.url.toString()),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = ApiClient(
            httpClient = HttpClientFactory.create({ Factory(engine) }),
            headerProvider = { _, _, _, _ -> emptyMap() },
        )
        val config = AppConfig(
            environment = Environment.Develop,
            services = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
            realtime = emptyMap(),
        )
        return SsCtx(
            api = ScriptSupervisionApi(client, config, projectId = { "p1" }),
            viewer = ScriptSupervisionViewer(
                resolved = true,
                enabled = true,
                canView = true,
                canPost = true,
                canDownload = true,
            ),
            scope = CoroutineScope(Dispatchers.Unconfined),
            projectId = "p1",
        )
    }

    private fun draws(page: SsPage) = runComposeUiTest {
        val ctx = context()
        val scenes = ScenesState(CoroutineScope(Dispatchers.Unconfined), ctx).apply { reload() }
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalSs provides ctx, LocalScenes provides scenes) {
                    ZillitScrollColumn { Column { SsRoutes(page) } }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `every page of the menu draws`() {
        SsPage.entries.forEach { draws(it) }
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }
}
