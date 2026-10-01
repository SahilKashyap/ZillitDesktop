package com.zillit.desktop.feature.costumesetsync

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
import com.zillit.desktop.feature.costumesetsync.data.SyncOnsetApi
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SyncProject
import com.zillit.desktop.feature.costumesetsync.domain.SyncViewer
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.SyncNav
import com.zillit.desktop.feature.costumesetsync.ui.SyncRoute
import com.zillit.desktop.feature.costumesetsync.ui.SyncRoutes
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
import kotlin.test.Test

/**
 * Every route of the tool, composed against a canned service. It cannot say a
 * screen is right; it says none of them throws when it first draws (the
 * infinite-constraint and empty-data crashes compile fine and only show here).
 */
@OptIn(ExperimentalTestApi::class)
class RoutesSmokeTest {

    private val costume = """{"_id":"c1","asset_number":"CST-000001","name":"Red coat","category":"CLOTHING",
    "status":"AVAILABLE",
        "type":"Coat","color":"Red","size":"M","location":"Warehouse","character":{"_id":"ch1","name":"Anna"},
        "timeline":[{"at":1772755200000,"kind":"ISSUE","title":"ISSUE → Actor","detail":"note","by":"Sam"}],
        "change_items":[],"rentals":[],"damages":[],"missing":[],"fitting_items":[],"cleaning":[],"alterations":[]}"""

    private fun reply(url: String): String {
        val path = url.substringBefore('?')
        val last = path.substringAfterLast('/')
        val data = when {
            path.endsWith("/meta") -> META
            path.endsWith("/dashboard") ->
                """{"date":"2026-10-01","counts":{"costumes":1,"characters":1,"by_status":{"AVAILABLE":1}},
                    "todays_scenes":[{"_id":"s1","number":"1","name":"Opening","status":"PENDING","level":"READY",
                    "characters":[{"name":"Anna","level":"READY"}]}],
                    "priorities":[{"text":"Anna's coat is dirty","severity":"WARNING"}]}"""
            path.endsWith("/costumes") -> """{"items":[$costume],"total":1,"page_size":50}"""
            path.endsWith("/costumes/c1") || last == "x1" -> costume
            last == "projects" || path.substringAfter("/projects/").count { it == '/' } == 0 ->
                """{"project_name":"Demo","counts":{"scenes":1}}"""
            last.length == 2 || last == "book" -> "{}"
            else -> "[]"
        }
        return """{"status":1,"data":$data}"""
    }

    internal fun ctx(route: String): SyncCtx {
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
        val meta = Rec(Json.parseToJsonElement(META) as JsonObject)
        val projectJson = Json.parseToJsonElement("""{"project_name":"Demo","my_role":"ADMIN","currency":"GBP"}""")
            as JsonObject
        return SyncCtx(
            api = SyncOnsetApi(client, config, projectId = { "p1" }),
            viewer = SyncViewer(resolved = true, enabled = true, canView = true, canPost = true, canDownload = true),
            project = SyncProject(Rec(projectJson), setOf("ADMIN")),
            meta = meta,
            nav = SyncNav(SyncRoute.parse(route)),
            scope = CoroutineScope(Dispatchers.Unconfined),
            projectId = "p1",
            now = { 1_772_755_200_000L },
        )
    }

    private fun draws(route: String) = runComposeUiTest {
        val context = ctx(route)
        setContent {
            ZillitTheme {
                CompositionLocalProvider(LocalSync provides context) {
                    ZillitScrollColumn { Column { SyncRoutes(context.nav.current) } }
                }
            }
        }
        waitForIdle()
    }

    @Test fun dashboard() = draws("dashboard")
    @Test fun breakdown() = draws("breakdown")
    @Test fun scenes() = draws("scenes")
    @Test fun sceneDetail() = draws("scenes/x1")
    @Test fun changeDetail() = draws("changes/x1")
    @Test fun characters() = draws("characters")
    @Test fun characterDetail() = draws("characters/x1")
    @Test fun actors() = draws("actors")
    @Test fun costumes() = draws("costumes")
    @Test fun costumeDetail() = draws("costumes/c1")
    @Test fun cleaning() = draws("cleaning")
    @Test fun cleaningDetail() = draws("cleaning/x1")
    @Test fun fittings() = draws("fittings")
    @Test fun fittingDetail() = draws("fittings/x1")
    @Test fun alterations() = draws("alterations")
    @Test fun damages() = draws("damages")
    @Test fun missing() = draws("missing")
    @Test fun labels() = draws("labels")
    @Test fun vendors() = draws("vendors")
    @Test fun continuityOnSet() = draws("continuity")
    @Test fun continuityBook() = draws("continuity/book")
    @Test fun reports() = draws("reports")
    @Test fun budget() = draws("budget")
    @Test fun gallery() = draws("gallery")
    @Test fun notifications() = draws("notifications")

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private companion object {
        const val META = """{"costume_statuses":["AVAILABLE","ISSUED"],"costume_categories":["CLOTHING","FOOTWEAR"],
            "costume_sources":["PURCHASED","RENTED"],"standard_locations":["Warehouse"],"finance_roles":["ADMIN"],
            "costume_types":{"CLOTHING":["Coat"]},"cleaning_types":["DRY_CLEANING"],
            "media_types":["IMAGE","FILE","LINK"]}"""
    }
}
