package com.zillit.desktop.feature.costumesetsync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.v2.runComposeUiTest
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
import com.zillit.desktop.feature.costumesetsync.domain.TicketBoard
import com.zillit.desktop.feature.costumesetsync.ui.screens.CleaningRequestDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.EmergencyCleanDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.ScheduleFittingDialog
import com.zillit.desktop.feature.costumesetsync.ui.screens.TicketFormDialog
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

/** Opt-in: `SYNC_SHOTS=1` writes PNGs of the workflow screens (fittings, cleaning, tickets) to `build/shots/wf_*`. */
@OptIn(ExperimentalTestApi::class)
class ShotsWorkflowTest {
    private val now = 1_772_755_200_000L
    private val day = 86_400_000L

    private fun costume(id: String, asset: String, name: String, character: String) =
        """{"_id":"$id","asset_number":"$asset","name":"$name","category":"CLOTHING","status":"AVAILABLE",
            "type":"Coat","color":"Red","size":"M",
            "character":{"_id":"ch$id","name":"$character"}}"""

    private fun ticket(
        id: String,
        status: String,
        prio: String,
        emergency: Boolean,
        problem: String,
        who: String,
        eta: Long
    ) =
        """{"_id":"$id","status":"$status","priority":"$prio","is_emergency":$emergency,"problem":"$problem",
            "cleaning_type":"DRY_CLEANING",
            "costume":${costume(
                "k$id",
                "CST-00000$id",
                "Velvet evening gown",
                "Anna Karenina-Whitfield"
            )},"scene":{"_id":"s1","number":"12"},"take_number":2,
            "assigned_to":"${if (who.isBlank()) "" else "u1"}","assigned_to_name":"$who",
                "expected_ready_at":$eta,"created":${now - day / 3},"requested_by_name":"Sam Wardrobe",
            "pipeline":["REQUESTED","RECEIVED","CLEANING","DRYING","IRONING","QUALITY_CHECK","READY"],
            "logs":[{"created":${now - day / 4},"by_user_name":"Sam","to_status":"RECEIVED","note":"Picked up"}],
            "alternatives":[${costume("a1", "CST-000090", "Green gown", "Anna")}]}"""

    private val cleaning = (listOf(
        ticket("1", "REQUESTED", "NORMAL", false, "Wine stain on the hem", "", 0),
        ticket("2", "REQUESTED", "HIGH", false, "Mud on the cuffs after the river scene", "Priya", now + day),
        ticket("3", "RECEIVED", "NORMAL", false, "Sweat marks", "Priya", now + day),
        ticket("4", "CLEANING", "URGENT", true, "Blood splatter, reshoot at 2pm", "Marco", now + day / 8),
        ticket("5", "DRYING", "LOW", false, "General freshen", "Marco", now + 2 * day),
        ticket("6", "IRONING", "NORMAL", false, "Press creases", "Priya", now + day),
        ticket("7", "QUALITY_CHECK", "HIGH", false, "Check hem repair", "Sam", now + day),
        ticket(
            "8",
            "READY",
            "NORMAL",
            false,
            "Collar stain",
            "Sam",
            0
        ).replace("\"created\"", "\"completed_at\":$now,\"created\""),
    )).joinToString(",")

    private val fitting = """{"_id":"f1","status":"SCHEDULED","scheduled_at":${now + day},
        "location":"Wardrobe trailer, Pinewood Studios",
        "character":{"_id":"c1","name":"Anna Karenina-Whitfield"},"actor":{"_id":"a1","name":"Keira Knightley",
            "measurements":{"height":"170","chest":"84"}},
        "notes":"Bring the second corset","items":[
          {"_id":"i1","status":"FITTED","costume":${costume("k1", "CST-000001", "Red coat", "Anna")}},
          {"_id":"i2","status":"ALTERATION_REQUIRED","costume":${costume(
              "k2",
              "CST-000002",
              "Velvet gown",
              "Anna"
          )},"notes":"Take in the waist"},
          {"_id":"i3","status":"PENDING","costume":${costume("k3", "CST-000003", "Opera gloves", "Anna")}}]}"""

    private val fittings = "$fitting," + fitting
        .replace("\"f1\"", "\"f2\"")
        .replace("SCHEDULED", "COMPLETED")
        .replace("Keira Knightley", "Matthew Macfadyen")
        .replace("Anna Karenina-Whitfield", "Count Vronsky") + "," + fitting
            .replace("\"f1\"", "\"f3\"")
            .replace("SCHEDULED", "IN_PROGRESS")

    private fun alt(id: String, status: String) =
        """{"_id":"$id","status":"$status","priority":"HIGH","issue":"Waist too loose",
            "required_work":"Take in the waist by 2cm",
                "costume":${costume("k$id", "CST-00000$id", "Velvet gown", "Anna")},
            "character":{"name":"Anna Karenina-Whitfield","actor":{"name":"Keira Knightley"}},"tailor_name":"Dina",
                "deadline":${now + day},"created":${now - day},"notes":"For Thursday's fitting\nBring pins"}"""

    private fun damage(id: String, status: String) =
        """{"_id":"$id","status":"$status","description":"Torn seam under the arm",
            "costume":${costume("k$id", "CST-00000$id", "Tweed jacket", "Marco")},
            "created":${now - day},"scene":{"number":"12"},"take_number":3,"responsible":"STUNT",
                "estimated_repair_cost":45}"""

    private fun missing(id: String, status: String) =
        """{"_id":"$id","status":"$status","costume":${costume("k$id", "CST-00000$id", "Opera gloves", "Anna")},
            "last_seen_location":"Set 4","last_assigned_to":"Priya","last_scan_at":${now - day / 2},
                "created":${now - day},"notes":"Search the truck again"}"""

    private fun reply(url: String): String {
        val path = url.substringBefore('?')
        val last = path.substringAfterLast('/')
        val data = when {
            path.endsWith("/meta") -> META
            path.endsWith("/cleaning") -> """{"items":[$cleaning],"pipeline":["REQUESTED","RECEIVED","CLEANING",
                "DRYING","IRONING","QUALITY_CHECK","READY"]}"""
            path.contains("/cleaning/") -> ticket(
                "4",
                "CLEANING",
                "URGENT",
                true,
                "Blood splatter, reshoot at 2pm",
                "Marco",
                now + day / 8
            )
            path.endsWith("/fittings") -> "[$fittings]"
            path.contains("/fittings/") -> fitting
            path.endsWith("/alterations") -> """{"items":[${alt("1", "REQUESTED")},${alt("2", "IN_PROGRESS")},${alt("3", "COMPLETED")}],
                "pipeline":["REQUESTED","IN_PROGRESS","FITTING","COMPLETED"]}"""
            path.endsWith("/damages") -> """{"items":[${damage("1", "OPEN")},${damage("2", "REPAIRING")}]}"""
            path.endsWith("/missing") -> """{"items":[${missing("1", "OPEN")},${missing("2", "FOUND")}]}"""
            path.endsWith("/costumes") -> """{"items":[${costume("k1", "CST-000001", "Red coat", "Anna")},
                ${costume("k2", "CST-000002", "Velvet gown", "Anna")}],"total":2,"page_size":50}"""
            path.endsWith("/characters") -> """[{"_id":"c1","name":"Anna Karenina-Whitfield",
                "actor":{"name":"Keira"}},{"_id":"c2","name":"Count Vronsky"}]"""
            last == "projects" || path.substringAfter("/projects/").count { it == '/' } == 0 ->
                """{"project_name":"Demo","counts":{"scenes":1}}"""
            else -> "[]"
        }
        return """{"status":1,"data":$data}"""
    }

    private fun ctx(route: String): SyncCtx {
        val engine = MockEngine { request ->
            respond(
                reply(request.url.toString()),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
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
        return SyncCtx(
            api = SyncOnsetApi(client, config, projectId = { "p1" }),
            viewer = SyncViewer(resolved = true, enabled = true, canView = true, canPost = true, canDownload = true),
            project = SyncProject(Rec(Json.parseToJsonElement("""{"project_name":"Demo","my_role":"ADMIN",
                "currency":"GBP"}""") as JsonObject), setOf("ADMIN")),
            meta = Rec(Json.parseToJsonElement(META) as JsonObject),
            nav = SyncNav(SyncRoute.parse(route)),
            scope = CoroutineScope(Dispatchers.Unconfined),
            projectId = "p1",
            now = { now },
        )
    }

    private fun shot(name: String, route: String, width: Dp = 1240.dp, overlay: @Composable () -> Unit = {}) {
        if (System.getenv("SYNC_SHOTS") == null) return
        runComposeUiTest {
            val context = ctx(route)
            setContent {
                ZillitTheme(animateThemeChange = false) {
                    CompositionLocalProvider(LocalSync provides context) {
                        Box(Modifier.size(width, 800.dp)) {
                            SyncFrame(null, 0, true, {}, {})
                            overlay()
                        }
                    }
                }
            }
            waitForIdle()
            val roots = onAllNodes(isRoot())
            val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
            val dir = File("build/shots").apply { mkdirs() }
            File(dir, "wf_$name.png").writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData()!!.bytes)
        }
    }

    @Test fun cleaningBoard() = shot("cleaning", "cleaning")
    @Test fun cleaningNarrow() = shot("cleaning_narrow", "cleaning", 1000.dp)
    @Test fun cleaningDetail() = shot("cleaning_detail", "cleaning/4")
    @Test fun fittings() = shot("fittings", "fittings")
    @Test fun fittingDetail() = shot("fitting_detail", "fittings/f1")
    @Test fun alterations() = shot("alterations", "alterations")
    @Test fun damages() = shot("damages", "damages")
    @Test fun missing() = shot("missing", "missing")
    @Test fun cleaningRequest() = shot("dlg_cleaning_request", "cleaning") { CleaningRequestDialog(true, {}, {}) }
    @Test fun emergency() = shot("dlg_emergency", "cleaning") { EmergencyCleanDialog(true, {}, {}) }
    @Test fun fittingForm() = shot("dlg_fitting", "fittings") { ScheduleFittingDialog(true, {}, emptyList(), {}, {}) }
    @Test fun sendRequest() = shot("dlg_send_request", "fittings") {
        com.zillit.desktop.feature.costumesetsync.ui.screens.SendRequestDialog(
            true,
            {},
            "Send a request about this fitting",
            "FITTING",
            "f1",
            "Fitting · Anna",
            "Fitting: Anna\n7 Mar 2026\n\nCan you confirm?"
        )
    }
    @Test fun alterationForm() = shot("dlg_alteration", "alterations") {
        TicketFormDialog(TicketBoard.Alterations, true, {}, {})
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private companion object {
        const val META = """{"costume_statuses":["AVAILABLE","ISSUED"],"costume_categories":["CLOTHING","FOOTWEAR"],
            "costume_sources":["PURCHASED","RENTED"],"standard_locations":["Warehouse"],"finance_roles":["ADMIN"],
            "costume_types":{"CLOTHING":["Coat"]},"cleaning_types":[
                "DRY_CLEANING",
                "WASH",
                "STEAM"
            ],"media_types":["IMAGE","FILE","LINK"],
            "fitting_statuses":["SCHEDULED","IN_PROGRESS","COMPLETED","CANCELLED"],"priorities":["LOW","NORMAL","HIGH",
                "URGENT"]}"""
    }
}
