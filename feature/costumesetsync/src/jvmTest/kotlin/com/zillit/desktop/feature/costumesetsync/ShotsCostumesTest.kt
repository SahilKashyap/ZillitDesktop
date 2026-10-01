package com.zillit.desktop.feature.costumesetsync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.geometry.Size
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
import com.zillit.desktop.feature.costumesetsync.ui.screens.AlterationDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.CleaningDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.CostumePickerDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.DamageDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.MissingDialog
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

/** Opt-in (SYNC_SHOTS=1): the Costumes, Costume detail, Labels, Gallery and Dashboard screens against rich canned data. */
@OptIn(ExperimentalTestApi::class)
class ShotsCostumesTest {

    private fun costume(i: Int, name: String, status: String, category: String, extra: String = "") =
        """{"_id":"c$i","asset_number":"CST-00000$i","name":"$name","category":"$category","status":"$status","type":"Coat","color":"Red","size":"M",
        "location":"Warehouse","source":"PURCHASED","quantity":${if (i == 2) 3 else 1},"character":{"_id":"ch1","name":"Anna Vandermeer"}$extra}"""

    private val costumes = listOf(
        costume(1, "Red wool overcoat with a very long descriptive name to test truncation", "AVAILABLE", "CLOTHING"),
        costume(2, "Leather boots", "ISSUED", "FOOTWEAR"),
        costume(3, "Silver locket", "CLEANING", "JEWELLERY"),
        costume(4, "Pocket watch", "MISSING", "ACCESSORY"),
        costume(5, "Silk blouse", "DAMAGED", "CLOTHING"),
        costume(6, "Wedding dress", "ALTERATION", "CLOTHING"),
    ).joinToString(",")

    private val detail = """{"_id":"c1","asset_number":"CST-000001","name":"Red wool overcoat","category":"CLOTHING","status":"ISSUED",
        "type":"Coat","color":"Red","size":"M","brand":"Burberry","location":"Set 2","source":"RENTED","vendor":{"name":"Angels Costumes"},
        "purchase_cost":120,"rental_cost_per_day":15,"quantity":1,"fabric":"Wool","care_instructions":"Dry clean only","created":1772755200000,
        "notes":"Hem was taken up for the lead.",
        "character":{"_id":"ch1","name":"Anna Vandermeer"},
        "timeline":[{"at":1772755200000,"kind":"ISSUE","title":"ISSUE → Actor","detail":"note","by":"Sam"},
                    {"at":1772668800000,"kind":"CREATE","title":"Created","detail":"","by":"Jo"}],
        "change_items":[{"_id":"ci1","change_id":"chg1","wear_notes":"Muddy for 14","change":{"change_number":2,"name":"Muddy","character":{"name":"Anna"},
            "scene_characters":[{"scene":{"_id":"s1","number":"1","shoot_date":1772755200000}},{"scene":{"_id":"s2","number":"12"}}]}}],
        "rentals":[{"_id":"r1","vendor":{"name":"Angels"},"pickup_date":1772755200000,"return_date":1773755200000,"status":"ACTIVE"}],
        "damages":[{"_id":"d1","created":1772755200000,"description":"Torn lining","status":"OPEN"}],
        "missing":[{"_id":"m1","created":1772755200000,"last_seen_location":"Set 2","status":"OPEN"}],
        "fitting_items":[{"_id":"f1","fitting_id":"fit1","status":"SCHEDULED","notes":"hem","fitting":{"scheduled_at":1772755200000}}],
        "cleaning":[{"_id":"cl1","problem":"Mud","status":"IN_PROGRESS","expected_ready_at":1773755200000}],
        "alterations":[{"_id":"al1","issue":"Hem","required_work":"Shorten","status":"IN_PROGRESS","deadline":1773755200000}]}"""

    private val dashboard = """{"date":"2026-10-01","counts":{"costumes":142,"characters":82,"todays_scenes":2,"todays_costumes":9,"issued_today":3,"returned_today":1,
        "cleaning":4,"alteration":2,"missing":1,"damaged":0,"by_status":{"AVAILABLE":90,"ISSUED":30,"CLEANING":4,"MISSING":1},"emergencies_today":1,"fittings_today":2,"rentals_due":0},
        "todays_scenes":[{"_id":"s1","number":"12","name":"Opening chase","location":"Warehouse","time_of_day":"NIGHT","status":"PENDING","level":"MISSING",
        "characters":[{"character_id":"a","name":"Anna","level":"READY","change":"Change 1 - Muddy"},{"character_id":"b","name":"Bob","level":"MISSING","change":""}]},
        {"_id":"s2","number":"13","name":"Kitchen","location":"Set 2","time_of_day":"DAY","status":"READY","level":"READY","characters":[{"character_id":"c","name":"Cara","level":"PARTIAL","change":"Change 2"}]}],
        "priorities":[{"text":"Anna's coat is dirty","severity":"WARNING","link":"cleaning"},{"text":"Bob has no costume for scene 12","severity":"CRITICAL","link":"scenes/s1"},{"text":"Rentals due Friday","severity":"INFO"}]}"""

    private val photos = """[{"_id":"p1","media_type":"IMAGE","kind":"FRONT","label":"Front","attachment":{"name":"front.jpg","media":"k1"}},
        {"_id":"p2","media_type":"FILE","kind":"DOCUMENT","attachment":{"name":"receipt.pdf","content_subtype":"pdf","file_size":120000}},
        {"_id":"p3","media_type":"LINK","kind":"OTHER","url":"https://example.com/ref","title":"Moodboard"},
        {"_id":"p4","media_type":"FILE","kind":"DETAIL","attachment":{"name":"clip.mov","content_subtype":"mov"}}]"""

    private fun reply(url: String): String {
        val path = url.substringBefore('?')
        val last = path.substringAfterLast('/')
        val data = when {
            path.endsWith("/meta") -> META
            path.endsWith("/dashboard") -> dashboard
            path.endsWith("/costumes") -> """{"items":[$costumes],"total":140,"page_size":50}"""
            path.endsWith("/costumes/c1") || last == "c1" -> detail
            path.endsWith("/characters") -> """[{"_id":"ch1","name":"Anna Vandermeer"},{"_id":"ch2","name":"Bob"}]"""
            path.endsWith("/scenes") -> """[{"_id":"s1","number":"1"},{"_id":"s2","number":"12"}]"""
            path.endsWith("/photos/gallery") -> photos
            path.endsWith("/photos") -> photos
            last == "projects" || path.substringAfter("/projects/").count { it == '/' } == 0 -> """{"project_name":"Demo","counts":{"scenes":1}}"""
            last.length == 2 || last == "book" -> "{}"
            else -> "[]"
        }
        return """{"status":1,"data":$data}"""
    }

    private fun ctx(route: String): SyncCtx {
        val engine = MockEngine { request ->
            respond(reply(request.url.toString()), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient(httpClient = HttpClientFactory.create({ Factory(engine) }), headerProvider = { _, _, _, _ -> emptyMap() })
        val config = AppConfig(Environment.Develop, ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" }, emptyMap())
        val meta = Rec(Json.parseToJsonElement(META) as JsonObject)
        return SyncCtx(
            api = SyncOnsetApi(client, config, projectId = { "p1" }),
            viewer = SyncViewer(resolved = true, enabled = true, canView = true, canPost = true, canDownload = true),
            project = SyncProject(Rec(Json.parseToJsonElement("""{"project_name":"Demo Production","my_role":"ADMIN","currency":"GBP","shooting_day":4,"current_location":"London"}""") as JsonObject), setOf("ADMIN")),
            meta = meta,
            nav = SyncNav(SyncRoute.parse(route)),
            scope = CoroutineScope(Dispatchers.Unconfined),
            projectId = "p1",
            now = { 1_772_755_200_000L },
        )
    }

    private fun shotOf(name: String, route: String, width: Int = 1240, height: Int = 800, content: (@Composable () -> Unit)? = null) {
        if (System.getenv("SYNC_SHOTS") == null) return
        runSkikoComposeUiTest(size = Size(width.toFloat(), height.toFloat())) {
            val context = ctx(route)
            setContent {
                ZillitTheme(animateThemeChange = false) {
                    CompositionLocalProvider(LocalSync provides context) {
                        Column(Modifier.size(width.dp, height.dp)) {
                            if (content == null) SyncFrame(null, 0, true, {}, {}) else Box(Modifier.size(width.dp, height.dp)) { content() }
                        }
                    }
                }
            }
            waitForIdle()
            val roots = onAllNodes(isRoot())
            val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
            val dir = File("build/shots").apply { mkdirs() }
            File(dir, "cs_$name.png").writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData()!!.bytes)
        }
    }

    @Test fun dashboard() = shotOf("dashboard", "dashboard", height = 1000)
    @Test fun costumes() = shotOf("costumes", "costumes")
    @Test fun costumesNarrow() = shotOf("costumes_1000", "costumes", 1000)
    @Test fun detail() = shotOf("detail", "costumes/c1", height = 1500)
    @Test fun labels() = shotOf("labels", "labels?ids=c1,c2")
    @Test fun gallery() = shotOf("gallery", "gallery")
    @Test fun costumeForm() = shotOf("form", "costumes") {
        com.zillit.desktop.feature.costumesetsync.ui.screens.CostumeFormDialog(open = true, onClose = {})
    }

    private fun dlg(name: String, route: String = "costumes/c1", content: @Composable () -> Unit) = shotOf(name, route, content = content)

    @Test fun dlgEmergency() = dlg("dlg_emergency") { CleaningDialog(costume, true, "", "", {}, { _, _ -> }) }
    @Test fun dlgCleaning() = dlg("dlg_cleaning") { CleaningDialog(costume, false, "", "", {}, { _, _ -> }) }
    @Test fun dlgDamage() = dlg("dlg_damage") { DamageDialog(costume, "", "", {}, {}) }
    @Test fun dlgAlteration() = dlg("dlg_alteration") { AlterationDialog(costume, {}, {}) }
    @Test fun dlgMissing() = dlg("dlg_missing") { MissingDialog(costume, {}, {}) }
    @Test fun dlgPicker() = dlg("dlg_picker", "costumes") { CostumePickerDialog(true, {}, {}, characterId = "ch1") }
    @Test fun dashboardNarrow() = shotOf("dashboard_1000", "dashboard", 1000)
    @Test fun detailNarrow() = shotOf("detail_1000", "costumes/c1", 1000, 1500)
    @Test fun labelsNarrow() = shotOf("labels_1000", "labels?ids=c1,c2", 1000)
    @Test fun galleryNarrow() = shotOf("gallery_1000", "gallery", 1000)

    private val costume: Rec get() = Rec(Json.parseToJsonElement(detail) as JsonObject)

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private companion object {
        const val META = """{"costume_statuses":["AVAILABLE","ISSUED","CLEANING"],"costume_categories":["CLOTHING","FOOTWEAR"],
            "costume_sources":["PURCHASED","RENTED"],"standard_locations":["Warehouse","Set 2"],"finance_roles":["ADMIN"],
            "costume_types":{"CLOTHING":["Coat"]},"cleaning_types":["DRY_CLEANING"],"media_types":["IMAGE","FILE","LINK"]}"""
    }
}
