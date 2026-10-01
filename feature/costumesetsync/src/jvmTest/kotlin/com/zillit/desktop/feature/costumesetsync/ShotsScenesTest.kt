package com.zillit.desktop.feature.costumesetsync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.costumesetsync.data.SyncOnsetApi
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SyncProject
import com.zillit.desktop.feature.costumesetsync.domain.SyncViewer
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.SyncFrame
import com.zillit.desktop.feature.costumesetsync.ui.SyncNav
import com.zillit.desktop.feature.costumesetsync.ui.SyncRoute
import com.zillit.desktop.feature.costumesetsync.ui.screens.ActorFormDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.BreakdownRowDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.EditCharacterDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.NewChangeDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.NewCharacterDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.RowDialogData
import com.zillit.desktop.feature.costumesetsync.ui.screens.ProjectDocuments
import com.zillit.desktop.feature.costumesetsync.ui.screens.ScheduleUploadDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.ScriptUploadDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.rememberScheduleUpload
import com.zillit.desktop.feature.costumesetsync.ui.screens.rememberScriptUpload
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

/** Opt-in (`SYNC_SHOTS=1`): the scene/character/actor screens against ~80 characters and ~40 scenes. */
@OptIn(ExperimentalTestApi::class)
class ShotsScenesTest {
    private val meta = """{"costume_statuses":["AVAILABLE"],"costume_categories":["CLOTHING"],"costume_sources":["PURCHASED"],
        "standard_locations":["Warehouse"],"finance_roles":["ADMIN"],"costume_types":{"CLOTHING":["Coat"]},"cleaning_types":["DRY_CLEANING"],
        "media_types":["IMAGE"],"int_ext":["INT","EXT"],"character_types":["LEAD","SUPPORTING","BACKGROUND"]}"""

    private fun charactersJson(): String = (1..82).joinToString(",", "[", "]") { i ->
        val name = listOf("A Series Of Quick Cuts", "A Series Of Short Cuts", "A. N. Other Publisher #$i", "Ale", "Anna Marlowe", "Bartholomew Finch")[i % 6] + if (i > 6) " $i" else ""
        val cast = if (i <= 3) ""","cast_number":$i""" else ""
        val actor = if (i % 7 == 0) ""","actor":{"_id":"a${i % 3}","name":"Actor ${i % 3}"}""" else ""
        """{"_id":"ch$i","name":"$name","type":"SUPPORTING"$cast$actor,"age":${if (i % 5 == 0) 34 else 0},"counts":{"scenes":${i % 4 + 1},"changes":${i % 3},"costumes":${i % 5}}}"""
    }

    private fun actorsJson(): String = (0..2).joinToString(",", "[", "]") { i ->
        """{"_id":"a$i","name":"Actor $i","phone":"+44 7700 90000$i","agency":"Agency","characters":[{"_id":"ch${i + 1}","name":"Anna"}],"measurements":{"chest":"40","waist":"32"}}"""
    }

    private fun scenesJson(): String = (1..40).joinToString(",", "[", "]") { i ->
        val chars = (1..(i % 4 + 1)).joinToString(",") { k ->
            """{"_id":"sc$i-$k","character_id":"ch$k","character":{"name":"Character $k","cast_number":$k},"readiness":"${listOf("READY", "PARTIAL", "MISSING")[k % 3]}"${if (k % 2 == 0) ""","change":{"_id":"cg$k","number":$k,"name":"Change $k"}""" else ""}}"""
        }
        """{"_id":"s$i","number":"$i","name":"Scene $i","status":"${if (i % 9 == 0) "SHOT" else "PLANNED"}","time_of_day":"${if (i % 2 == 0) "DAY" else "NIGHT"}","int_ext":"INT","location":"Kitchen of the farm house $i",
            "synopsis":"Anna argues with her brother over the sale of the farm and storms out into the yard, in the rain.","revision":"White","revised_at":1,"readiness":"PARTIAL","shoot_date":1772755200000,"characters":[$chars]}"""
    }

    private fun reply(url: String): String {
        val path = url.substringBefore('?')
        val data = when {
            path.endsWith("/meta") -> meta
            path.endsWith("/characters") -> charactersJson()
            path.endsWith("/actors") -> actorsJson()
            path.endsWith("/scenes") -> scenesJson()
            path.endsWith("/scenes/s1/readiness") -> READINESS
            path.endsWith("/scenes/s1") -> SCENE_ONE
            path.endsWith("/characters/ch1") -> CHARACTER_ONE
            path.endsWith("/changes/cg1") -> CHANGE_ONE
            path.endsWith("/changes") -> "[$CHANGE_ONE]"
            path.substringAfterLast('/') == "projects" || path.substringAfter("/projects/").count { it == '/' } == 0 -> """{"project_name":"Demo","counts":{"scenes":1}}"""
            else -> "[]"
        }
        return """{"status":1,"data":$data}"""
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private fun ctx(route: String): SyncCtx {
        val engine = MockEngine { respond(reply(it.url.toString()), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val client = ApiClient(HttpClientFactory.create({ Factory(engine) }), headerProvider = { _, _, _, _ -> emptyMap() })
        val config = AppConfig(Environment.Develop, ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" }, emptyMap())
        fun obj(s: String) = Rec(Json.parseToJsonElement(s) as JsonObject)
        return SyncCtx(
            api = SyncOnsetApi(client, config, projectId = { "p1" }),
            viewer = SyncViewer(resolved = true, enabled = true, canView = true, canPost = true, canDownload = true),
            project = SyncProject(obj("""{"project_name":"Demo","my_role":"ADMIN","currency":"GBP"}"""), setOf("ADMIN")),
            meta = obj(meta), nav = SyncNav(SyncRoute.parse(route)), scope = CoroutineScope(Dispatchers.Unconfined),
            projectId = "p1", now = { 1_772_755_200_000L },
        )
    }

    private fun render(name: String, width: Dp = 1240.dp, height: Dp = 800.dp, route: String? = null, body: (@Composable (SyncCtx) -> Unit)? = null) {
        if (System.getenv("SYNC_SHOTS") == null) return
        runDesktopComposeUiTest(width.value.toInt(), height.value.toInt()) {
            val context = ctx(route ?: "dashboard")
            setContent {
                ZillitTheme(animateThemeChange = false) {
                    CompositionLocalProvider(LocalSync provides context) {
                        Column(Modifier.size(width, height)) { SyncFrame(null, 0, true, {}, {})
                            body?.invoke(context) }
                    }
                }
            }
            waitForIdle()
            val roots = onAllNodes(androidx.compose.ui.test.isRoot())
            val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
            val dir = File("build/shots").apply { mkdirs() }
            File(dir, "sc_$name.png").writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData()!!.bytes)
        }
    }

    @Test fun characters() = render("characters", route = "characters")
    @Test fun charactersNarrow() = render("characters_narrow", width = 1000.dp, route = "characters")
    @Test fun scenes() = render("scenes", route = "scenes")
    @Test fun breakdown() = render("breakdown", route = "breakdown")
    @Test fun breakdownNarrow() = render("breakdown_narrow", width = 1000.dp, route = "breakdown")
    @Test fun breakdownWide() = render("breakdown_wide", width = 2300.dp, height = 700.dp, route = "breakdown")
    @Test fun scenesWide() = render("scenes_wide", width = 2300.dp, height = 700.dp, route = "scenes")
    private fun recs(json: String): List<Rec> = (Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonArray).map { Rec(it as JsonObject) }
    private fun rec(json: String) = Rec(Json.parseToJsonElement(json) as JsonObject)

    @Test fun dialogActor() = render("dlg_actor", route = "actors") { ActorFormDialog(open = true, onClose = {}, editing = null, onSaved = {}) }
    @Test fun dialogChange() = render("dlg_change", route = "actors") { NewChangeDialog(true, {}, rec(CHARACTER_ONE)) }
    @Test fun dialogEditCharacter() = render("dlg_editchar", route = "actors") { EditCharacterDialog(true, {}, rec(CHARACTER_ONE), recs(actorsJson()), {}) }
    @Test fun dialogNewCharacter() = render("dlg_newchar", route = "actors") { NewCharacterDialog(true, {}, {}) }
    @Test fun dialogBreakdownRow() = render("dlg_row", route = "breakdown") {
        BreakdownRowDialog(true, null, RowDialogData(recs(scenesJson()), recs(charactersJson()), recs(actorsJson()), false, listOf("INT", "EXT")), {}, {})
    }
    @Test fun dialogScriptUpload() = render("dlg_script", route = "breakdown") { ctx ->
        val docs = ProjectDocuments(ctx, "SCRIPT")
        ScriptUploadDialog(true, rememberScriptUpload(true, docs, 0, {}, {}), docs, {})
    }
    @Test fun dialogScheduleUpload() = render("dlg_schedule", route = "breakdown") { ctx ->
        val docs = ProjectDocuments(ctx, "SCHEDULE")
        ScheduleUploadDialog(true, rememberScheduleUpload(true, "SCHEDULE", docs, recs(scenesJson()), {}, {}), docs, {})
    }
    @Test fun actors() = render("actors", route = "actors")
    @Test fun sceneDetail() = render("scene_detail", route = "scenes/s1")
    @Test fun changeDetail() = render("change_detail", route = "changes/cg1")
    @Test fun characterDetail() = render("character_detail", route = "characters/ch1")

    private companion object {
        const val SCENE_ONE = """{"_id":"s1","number":"1","name":"Opening","status":"PLANNED","time_of_day":"DAY","int_ext":"INT","location":"Farm kitchen","script_day":"Day 1","pages":"2 3/8","revision":"White",
            "synopsis":"Anna argues with her brother over the sale of the farm and storms out into the yard, in the rain.","shoot_date":1772755200000,"has_script":true,
            "characters":[{"_id":"sc1","character_id":"ch1","change_id":"cg1","character":{"name":"Anna","changes":[{"_id":"cg1","change_number":1,"name":"Farm coat"}]}}],
            "continuity":[{"character_id":"ch1","character":{"name":"Anna"},"take_number":1,"notes":"n"},{"character_id":"ch1","character":{"name":"Anna"},"take_number":2}],
            "cues":[{"_id":"q1","kind":"CONDITION","status":"SUGGESTED","text":"Muddy boots","quote":"she tramps in mud","source":"READER","confidence":"LOW","character":{"_id":"ch1","name":"Anna"}},
                    {"_id":"q2","kind":"CHANGE","status":"ACCEPTED","text":"Rain coat","source":"AI","character_name":"Anna"}],
            "cleaning":[{"_id":"cl1","status":"OPEN","priority":"HIGH","problem":"Mud on hem","costume":{"asset_number":"CST-000001","name":"Red coat"}}]}"""
        const val READINESS = """{"overall":"PARTIAL","characters":[{"scene_character_id":"sc1","level":"PARTIAL","character":{"_id":"ch1","name":"Anna","actor":{"name":"Actor 1"}},
            "change":{"_id":"cg1","change_number":1,"name":"Farm coat"},"items":[{"costume_id":"c1","asset_number":"CST-000001","name":"Red coat","level":"READY","location":"Warehouse","wear_notes":"muddy"},
            {"costume_id":"c2","asset_number":"CST-000002","name":"Boots","level":"MISSING"}],"blockers":[]}]}"""
        const val CHANGE_ONE = """{"_id":"cg1","change_number":1,"name":"Farm coat","character":{"_id":"ch1","name":"Anna"},"description":"Worn in the yard","status":"ACTIVE",
            "scenes":[{"_id":"s1","number":"1","name":"Opening"}],"costumes":[{"_id":"c1","asset_number":"CST-000001","name":"Red coat","category":"CLOTHING","status":"AVAILABLE"}],"items":[]}"""
        const val CHARACTER_ONE = """{"_id":"ch1","name":"Anna","type":"LEAD","age":34,"cast_number":1,"description":"The farmer's daughter","actor":{"_id":"a1","name":"Actor 1","phone":"+44 7700 900001","agency":"Agency","measurements":{"chest":"40"}},
            "counts":{"scenes":4,"changes":1,"costumes":2,"scene_characters":4},"scenes":[{"_id":"s1","number":"1","name":"Opening","shoot_date":1772755200000,"change":{"_id":"cg1","change_number":1,"name":"Farm coat"}}],
            "changes":[{"_id":"cg1","change_number":1,"name":"Farm coat","status":"ACTIVE","costumes":[]}],"costumes":[{"_id":"c1","asset_number":"CST-000001","name":"Red coat","category":"CLOTHING","status":"AVAILABLE"}],"fittings":[],"details":[]}"""
    }
}
