package com.zillit.desktop.feature.costumesetsync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import com.zillit.desktop.feature.costumesetsync.ui.screens.CharacterSelect
import com.zillit.desktop.feature.costumesetsync.ui.screens.DocumentViewerDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.NewSceneDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.NewVendorDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.ProjectDocuments
import com.zillit.desktop.feature.costumesetsync.ui.screens.ScheduleUploadDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.ScriptUpload
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test

/**
 * Opt-in (SYNC_SHOTS=1): editor row, script/schedule review, quick selects, document dialogs, breakdown at true widths.
 */
@OptIn(ExperimentalTestApi::class)
class ShotsScenes2Test {
    private companion object {
        const val SYNOPSIS =
            "Anna argues with her brother over the sale of the farm and storms out into the yard, in the rain."
    }

    private val meta = """{"costume_statuses":["AVAILABLE"],"costume_categories":["CLOTHING"],
    "costume_sources":["PURCHASED"],
        "standard_locations":["Warehouse"],
        "finance_roles":["ADMIN"],
        "costume_types":{"CLOTHING":["Coat"]},
        "cleaning_types":["DRY_CLEANING"],
        "media_types":["IMAGE"],"int_ext":["INT","EXT","INT/EXT"],"times_of_day":["DAY","NIGHT","DAWN"],
        "character_types":["LEAD","SUPPORTING","BACKGROUND"]}"""

    private fun charactersJson(): String = (1..12).joinToString(",", "[", "]") { i ->
        val names = listOf("Anna Marlowe", "Bartholomew Finch", "Brother", "Cara", "The Publisher", "Doctor Quill")
        val name = names[i % 6] +
            if (i > 6) " $i" else ""
        val actor = if (i % 2 == 0) {
            ""","actor":{"_id":"a${i % 3}","name":"Actor ${i % 3}"},"actor_id":"a${i % 3}""""
        } else {
            ""
        }
        """{"_id":"ch$i","name":"$name","type":"SUPPORTING","cast_number":$i$actor,"counts":{"scenes":${i % 4 + 1},
        "changes":${i % 3},"costumes":${i % 5}}}"""
    }

    private fun actorsJson(): String = (0..2).joinToString(",", "[", "]") { i -> """{"_id":"a$i","name":"Actor $i"}""" }

    private fun scenesJson(): String = (1..14).joinToString(",", "[", "]") { i ->
        val chars = (1..(i % 4 + 1)).joinToString(",") { k ->
            """{"_id":"sc$i-$k","character_id":"ch$k",
            "character":{"name":"${listOf("Anna Marlowe", "Bartholomew Finch", "Brother", "Cara")[k - 1]}",
            "cast_number":$k},
            "readiness":"${listOf("READY", "PARTIAL", "MISSING")[k % 3]}"${
                if (k % 2 == 0) ""","change":{"_id":"cg$k","number":$k,"change_number":$k,"name":"Change $k"}""" else ""
            }}"""
        }
        """{"_id":"s$i","number":"$i","name":"Scene $i","status":"${if (i % 9 == 0) "SHOT" else "PLANNED"}",
        "time_of_day":"${if (i % 2 == 0) "DAY" else "NIGHT"}","int_ext":"INT","location":"Kitchen of the farm house $i",
            "script_day":"Day ${i / 3 + 1}",
            "synopsis":"$SYNOPSIS",
            "revision":"White","revised_at":1,"readiness":"PARTIAL","shoot_date":1772755200000,"characters":[$chars]}"""
    }

    private val docs = """[{"_id":"d1","kind":"SCRIPT","source":"ZILLIT",
    "file_name":"The Long Farewell - Script v3.pdf","latest":true,"created":1772755200000,"revision":"Pink",
        "attachment":{"name":"The Long Farewell - Script v3.pdf"},"sheet_date":"2026-03-06"},
        {"_id":"d2","kind":"SCRIPT","source":"UPLOAD","file_name":"Farewell_white.fdx","created":1771755200000,
        "revision":"White","attachment":{"name":"Farewell_white.fdx"}}]"""

    private val parsedScript = """{"file":"The Long Farewell - Script v3.pdf","format":"pdf","first_upload":false,
    "file_token":"tok","stats":{"cues":14},
        "warnings":["Page 41 looked scanned; 2 scenes were read from images and may be incomplete."],
        "scenes":[
         {
             "number":"1",
             "name":"Kitchen - Night",
             "location":"Kitchen of the farm house",
             "int_ext":"INT",
             "time_of_day":"NIGHT",
             "script_day":"Day 1",
             "pages":"2 3/8",
             "synopsis":"$SYNOPSIS",
             "characters":["ANNA","BROTHER"],
             "change":"new"
         },
         {"number":"2","name":"Yard - Day","location":"Farm yard","int_ext":"EXT","time_of_day":"DAY",
         "script_day":"Day 2","pages":"1","synopsis":"Rain. Anna crosses to the barn.","characters":["ANNA",
         "THE PUBLISHER","CROWD"],"change":"updated","previous_revision":"White",
          "previous":{"int_ext":"INT","location":"Farm barn","time_of_day":"NIGHT","script_day":"Day 1","pages":"2",
          "synopsis":"Anna crosses to the barn."}},
         {"number":"3","name":"Barn - Day","location":"Barn","int_ext":"INT","time_of_day":"DAY","script_day":"Day 2",
         "pages":"4/8","synopsis":"A quiet beat.","characters":["ANNA"],"change":"unchanged",
          "previous":{"int_ext":"INT","location":"Barn","time_of_day":"DAY","script_day":"Day 2","pages":"4/8",
          "synopsis":"A quiet beat."}},
         {"number":"4","name":"Barn - Day","location":"Barn","int_ext":"INT","time_of_day":"DAY","script_day":"Day 2",
         "pages":"1 2/8","synopsis":"Dialogue moved.","characters":["ANNA","BROTHER"],"change":"updated",
         "previous_revision":"White",
          "previous":{"int_ext":"INT","location":"Barn","time_of_day":"DAY","script_day":"Day 2","pages":"1 2/8",
          "synopsis":"Dialogue moved."}},
         {"number":"4A","name":"Omitted","location":"","int_ext":"","time_of_day":"","script_day":"","pages":"",
         "synopsis":"","characters":[],"change":"updated","status":"OMITTED"}],
        "characters":[{"name":"ANNA","scenes":12,"lines":88},{"name":"BROTHER","scenes":5,"lines":31},
        {"name":"THE PUBLISHER","scenes":2,"lines":9},{"name":"CROWD","scenes":1,"lines":1}],
        "existing_characters":[{"name":"Anna","cast_number":1},{"name":"Brother"}]}"""

    private val parsedSchedule = """{"file":"Shooting schedule v2.pdf","format":"pdf","days":3,"day_number":2,
    "file_token":"t2","known_cast_numbers":true,
        "warnings":["Day 3 lists a scene (44) twice; the first was used."],
        "scenes":[
         {"number":"1","_id":"s1","exists":true,"status":"PLANNED","current_shoot_date":1772755200000,"current":{
             "int_ext":"INT","location":"Kitchen"
         },
          "read":{
              "int_ext":"INT",
              "location":"Kitchen of the farm house",
              "time_of_day":"NIGHT",
              "name":"Kitchen",
              "pages":"2 3/8",
              "script_day":"Day 1",
              "description":"Anna argues with her brother"
          },
          "fills":["pages","script_day"],
          "cast":[{"_id":"ch1","cast_number":1},{"_id":"ch2","cast_number":2}],
          "date":"2026-03-07"},
         {"number":"2","_id":"s2","exists":true,"status":"PLANNED","current_shoot_date":0,"current":{
             "int_ext":"EXT","location":"Yard"
         },
          "read":{"int_ext":"EXT","location":"Yard","time_of_day":"DAY","name":"Yard","pages":"1"},
          "fills":[],
          "cast":[],
          "date":"2026-03-07"},
         {
             "number":"3",
             "_id":"s3",
             "exists":true,
             "status":"PLANNED",
             "current_shoot_date":0,
             "current":{"int_ext":"INT","location":"Barn"},
             "read":{},
             "fills":[],
             "cast":[],
             "date":""
         },
         {"number":"44","exists":false,"read":{"int_ext":"EXT",
         "location":"A very long location name that should cut off with an ellipsis at the edge of the column",
         "time_of_day":"DAY","name":"Long","pages":"3","script_day":"Day 4",
         "description":"A long description of the scene that goes on and on"},"fills":[],"cast":[{"_id":null,
         "cast_number":9}],"date":"2026-03-08"}]}"""

    private fun reply(url: String): String {
        val path = url.substringBefore('?')
        val data = when {
            path.endsWith("/meta") -> meta
            path.endsWith("/characters") -> charactersJson()
            path.endsWith("/actors") -> actorsJson()
            path.endsWith("/scenes") -> scenesJson()
            path.endsWith("/vendors") -> """[{"_id":"v1","name":"Angels Costumes"},{"_id":"v2","name":"Bermans"}]"""
            path.endsWith("/documents") -> docs
            path.endsWith("/scenes/parse-script") -> parsedScript
            path.endsWith("/schedule/parse") -> parsedSchedule
            path.substringAfterLast('/') == "projects" ||
                path.substringAfter("/projects/").count { it == '/' } == 0 ->
                """{"project_name":"Demo","counts":{"scenes":1}}"""
            else -> "[]"
        }
        return """{"status":1,"data":$data}"""
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private fun ctx(route: String): SyncCtx {
        val engine = MockEngine {
            respond(reply(it.url.toString()), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
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
            viewer = SyncViewer(resolved = true, enabled = true, canView = true, canPost = true, canDownload = true),
            project = SyncProject(
                obj("""{"project_name":"Demo","my_role":"ADMIN","currency":"GBP"}"""),
                setOf("ADMIN"),
            ),
            meta = obj(meta), nav = SyncNav(SyncRoute.parse(route)), scope = CoroutineScope(Dispatchers.Unconfined),
            projectId = "p1", now = { 1_772_755_200_000L },
        )
    }

    private fun recs(json: String): List<Rec> = (Json.parseToJsonElement(json) as JsonArray)
        .map { Rec(it as JsonObject) }

    /** [steps] drives the UI (clicks) after first composition; [content] composes a dialog beside the frame. */
    private fun shot(
        name: String, route: String, width: Int = 1240, height: Int = 800, frame: Boolean = true,
        steps: (SkikoComposeUiTest.() -> Unit)? = null, content: (@Composable (SyncCtx) -> Unit)? = null,
    ) {
        if (System.getenv("SYNC_SHOTS") == null) return
        runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
            val context = ctx(route)
            setContent {
                ZillitTheme(animateThemeChange = false) {
                    CompositionLocalProvider(LocalSync provides context) {
                        Column(Modifier.size(width.dp, height.dp)) {
                            if (frame) SyncFrame(null, 0, true, {}, {})
                            content?.let { Box(Modifier.size(width.dp, height.dp)) { it(context) } }
                        }
                    }
                }
            }
            waitForIdle()
            steps?.invoke(this)
            waitForIdle()
            // Network answers land on another thread; let them in, then paint.
            repeat(3) {
                Thread.sleep(300)
                mainClock.advanceTimeBy(100)
                waitForIdle()
            }
            val roots = onAllNodes(isRoot())
            val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
            val dir = File("build/shots").apply { mkdirs() }
            File(dir, "sc2_$name.png").writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData()!!.bytes)
        }
    }

    private fun SkikoComposeUiTest.click(text: String) {
        onNodeWithText(text).performClick()
        waitForIdle()
    }

    // -- breakdown ------------------------------------------------------------------------
    @Test fun breakdown() = shot("breakdown", "breakdown", height = 700)
    @Test fun breakdownWide() = shot("breakdown_wide", "breakdown", width = 1900, height = 700)
    @Test fun scenesCollapsed() = shot("scenes", "scenes", height = 700)

    // -- editor ---------------------------------------------------------------------------
    @Test fun editAll() = shot("editall", "scenes", height = 800, steps = { click("Edit All") })
    @Test fun editAllNarrow() = shot(
        "editall_1000",
        "scenes",
        width = 1000,
        height = 800,
        steps = { click("Edit All") },
    )
    @Test fun editSinglePick() = shot("editsingle", "scenes", steps = { click("Edit Single") })
    @Test fun breakdownAdd() = shot("breakdown_add", "breakdown", steps = { click("Add") })

    // -- script / schedule ----------------------------------------------------------------
    @Test fun scriptPick() = shot(
        "script_pick",
        "breakdown",
        frame = false,
        steps = { await("Farewell_white.fdx") },
    ) { c ->
        val d = loaded(c, "SCRIPT")
        ScriptUploadDialog(true, rememberScriptUpload(true, d, 0, {}, {}), d, {})
    }
    /**
     * The document list already answered (the mock replies on another thread, which the test clock does not wait for).
     */
    @Composable
    private fun loaded(c: SyncCtx, kind: String): ProjectDocuments = androidx.compose.runtime.remember {
        ProjectDocuments(c, kind).also {
            it.reload()
            Thread.sleep(800)
        }
    }

    private fun SkikoComposeUiTest.await(text: String) {
        // Real time: the mock engine answers on another thread, which the test clock does not wait for.
        val end = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < end && onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()) {
            Thread.sleep(100)
            waitForIdle()
        }
        waitForIdle()
    }

    private fun scriptReview(name: String, tab: String, more: (SkikoComposeUiTest.() -> Unit)? = null) {
        var up: ScriptUpload? = null
        shot(
            name, "breakdown", 1240, 900, frame = false,
            steps = {
                await("Scenes (5)")
                up?.tab = tab
                waitForIdle()
                more?.invoke(this)
            },
        ) { c ->
            val d = ProjectDocuments(c, "SCRIPT")
            val upload = rememberScriptUpload(true, d, 0, {}, {})
            up = upload
            LaunchedEffect(Unit) { upload.pickDoc(recs(docs).first()) }
            ScriptUploadDialog(true, upload, d, {})
        }
    }
    @Test fun scriptReviewScenes() = scriptReview("script_scenes", "scenes")
    @Test fun scriptReviewEdit() = scriptReview("script_edit", "scenes") {
        onAllNodesWithText("Edit")[0].performClick(); waitForIdle()
    }
    @Test fun scriptReviewChars() = scriptReview("script_chars", "characters")
    @Test fun scheduleReview() = shot(
        "sched",
        "breakdown",
        1240,
        900,
        frame = false,
        steps = { await("Apply to 4 scenes") },
    ) { c ->
        val d = ProjectDocuments(c, "SCHEDULE")
        val upload = rememberScheduleUpload(true, "SCHEDULE", d, recs(scenesJson()), {}, {})
        LaunchedEffect(Unit) { upload.pickDoc(recs(docs).first()) }
        ScheduleUploadDialog(true, upload, d, {})
    }
    @Test fun schedulePick() = shot(
        "sched_pick",
        "breakdown",
        frame = false,
        steps = { await("Farewell_white.fdx") },
    ) { c ->
        val d = loaded(c, "SCHEDULE")
        ScheduleUploadDialog(true, rememberScheduleUpload(true, "SCHEDULE", d, recs(scenesJson()), {}, {}), d, {})
    }

    // -- quick selects, documents ---------------------------------------------------------
    @Test fun newScene() = shot("newscene", "breakdown", frame = false) { NewSceneDialog(true, {}, {}) }
    @Test fun newVendor() = shot("newvendor", "breakdown", frame = false) { NewVendorDialog(true, {}, {}) }
    @Test fun viewer() = shot("viewer", "breakdown", 1240, 900, frame = false, steps = { await("Close") }) { c ->
        val d = ProjectDocuments(c, "SCRIPT")
        LaunchedEffect(Unit) { d.reload() }
        DocumentViewerDialog(true, "SCRIPT", d, recs(scenesJson()), {}, {})
    }
    @Test fun characterSelect() = shot(
        "charselect",
        "breakdown",
        600,
        400,
        frame = false,
        steps = { click("Character") },
    ) { CharacterSelect("ch1", {}, "Character") }
}
