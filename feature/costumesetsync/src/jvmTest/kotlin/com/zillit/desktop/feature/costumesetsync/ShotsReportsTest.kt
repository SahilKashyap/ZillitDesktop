package com.zillit.desktop.feature.costumesetsync

import androidx.compose.foundation.layout.Column
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.costumesetsync.data.SyncOnsetApi
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SyncProject
import com.zillit.desktop.feature.costumesetsync.domain.SyncViewer
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
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
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.SyncFrame
import org.jetbrains.skia.Image
import java.io.File
import kotlin.test.Test

/** Opt-in: `SYNC_SHOTS=1` writes PNGs of the shell, reports, continuity, vendors, budget and notifications. */
@OptIn(ExperimentalTestApi::class)
class ShotsReportsTest {
    private val costume = """{"_id":"c1","asset_number":"CST-000001","name":"Red wool overcoat","status":"AVAILABLE"}"""
    private val vendor = """{"_id":"v1","name":"Angels Costumiers","contact_name":"Pat Doyle","phone":"+44 20 7000 1111","email":"hire@angels.test","address":"1 Shaftesbury Ave"}"""
    private fun rental(i: Int, status: String, extra: String = "") =
        """{"_id":"r$i","costume":$costume,"vendor":$vendor,"pickup_date":1772755200000,"return_date":1773000000000,"rate_per_day":12.5,"status":"$status"$extra}"""

    private fun expense(i: Int, cat: String, amount: Int) =
        """{"_id":"e$i","description":"Line $i","category":"$cat","amount":$amount,"currency":"GBP","account_code":"3${i}00","account_name":"Wardrobe $i","payee":"Shop $i","date":1772755200000}"""

    private fun reply(url: String): String {
        val path = url.substringBefore('?')
        val data = when {
            path.endsWith("/meta") -> META
            path.endsWith("/vendors") -> "[$vendor]"
            path.endsWith("/rentals") -> "[${rental(1, "BOOKED", ""","is_overdue":true""")},${rental(2, "PICKED_UP", ""","due_soon":true""")},${rental(3, "RETURNED")}]"
            path.endsWith("/notifications") -> """{"unread":2,"items":[
                {"_id":"n1","title":"Anna's coat needs cleaning","body":"Marked dirty after scene 4","severity":"WARNING","read":false,"created":1772755000000},
                {"_id":"n2","title":"Fitting tomorrow","body":"","severity":"INFO","read":true,"created":1772700000000}]}"""
            path.endsWith("/reports/budget") -> """{"inventory_value":1200,"rental_committed":300,"by_category":{"PURCHASE":400},
                "expenses":[${expense(1, "PURCHASE", 400)},${expense(2, "LAUNDRY", 120)},${expense(3, "RENTAL", 300)}]}"""
            path.endsWith("/reports/daily") -> """{"project":{"name":"Demo","shooting_day":3},"summary":{"scenes":2,"returned":1,"cleaning":3,"cleaning_completed":1,"alteration":1,"damaged":0,"missing":1,"spend":55},
                "scenes":[{"_id":"s1","number":"4","name":"Kitchen","location":"Set A","status":"SHOT","characters":[{"name":"Anna"},{"name":"Ben"}]}],
                "cleaning":[{"_id":"cl1","costume":$costume,"problem":"Wine stain","cleaning_type":"DRY_CLEANING","status":"IN_PROGRESS","is_emergency":true},
                            {"_id":"cl2","costume":$costume,"problem":"Mud","cleaning_type":"DRY_CLEANING","status":"COMPLETED"}],
                "alterations":[{"_id":"a1","costume":$costume,"issue":"Hem","required_work":"Take up 2cm","status":"PENDING"}],
                "missing":[{"_id":"m1","costume":$costume,"last_seen_location":"Truck 2"}]}"""
            path.endsWith("/reports/inventory") -> """[{"asset":"CST-000001","name":"Red wool overcoat","category":"CLOTHING","type":"Coat","size":"M","character":"Anna","source":"PURCHASED","vendor":"","purchase_cost":90,"status":"AVAILABLE","location":"Warehouse","quantity":1}]"""
            path.endsWith("/scenes") -> """[{"_id":"s1","number":"4","name":"Kitchen","int_ext":"INT","location":"Set A","time_of_day":"DAY","shoot_date":1772755200000,"status":"PENDING",
                "characters":[{"_id":"sc1","character_id":"ch1","character":{"name":"Anna","actor":{"name":"Zoe"}},"readiness":"READY","change":{"_id":"cg1","change_number":1,"name":"Look 1","items":[{"costume":{"name":"Red coat","status":"AVAILABLE"}}]}},
                              {"_id":"sc2","character_id":"ch2","character":{"name":"Ben"},"readiness":"MISSING"}]}]"""
            path.endsWith("/characters") -> "[]"
            else -> "{}"
        }
        return """{"status":1,"data":$data}"""
    }

    private fun ctx(route: String, fresh: Boolean = false): SyncCtx {
        val engine = MockEngine { request ->
            respond(reply(request.url.toString()), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = ApiClient(httpClient = HttpClientFactory.create({ Factory(engine) }), headerProvider = { _, _, _, _ -> emptyMap() })
        val config = AppConfig(Environment.Develop, ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" }, emptyMap())
        return SyncCtx(
            api = SyncOnsetApi(client, config, projectId = { "p1" }),
            viewer = SyncViewer(resolved = true, enabled = true, canView = true, canPost = true, canDownload = true),
            project = SyncProject(Rec(Json.parseToJsonElement(if (fresh) """{"project_name":"Demo","my_role":"ADMIN","currency":"GBP","type":"FEATURE","counts":{"scenes":0,"characters":0,"costumes":0,"actors":0}}""" else """{"project_name":"Demo","my_role":"ADMIN","currency":"GBP"}""") as JsonObject), setOf("ADMIN")),
            meta = Rec(Json.parseToJsonElement(META) as JsonObject),
            nav = SyncNav(SyncRoute.parse(route)),
            scope = CoroutineScope(Dispatchers.Unconfined),
            projectId = "p1",
            now = { 1_772_755_200_000L },
        )
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private fun shot(route: String, width: Int = 1240, name: String = route, fresh: Boolean = false) {
        if (System.getenv("SYNC_SHOTS") == null) return
        runComposeUiTest {
            val context = ctx(route, fresh)
            setContent {
                ZillitTheme(animateThemeChange = false) {
                    CompositionLocalProvider(LocalSync provides context) {
                        Column(Modifier.size(width.dp, 800.dp)) { SyncFrame(null, 0, !fresh, {}, {}) }
                    }
                }
            }
            waitForIdle()
            val image = onRoot().captureToImage()
            val dir = File("build/shots").apply { mkdirs() }
            File(dir, "r_" + name.replace('/', '_').substringBefore('?') + "_$width.png")
                .writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData()!!.bytes)
        }
    }

    @Test fun firstRun() = shot("dashboard", name = "firstrun", fresh = true)
    @Test fun vendors() = shot("vendors")
    @Test fun vendorsNarrow() = shot("vendors", 1000)
    @Test fun reports() = shot("reports")
    @Test fun continuity() = shot("continuity")
    @Test fun book() = shot("continuity/book")
    @Test fun budget() = shot("budget")
    @Test fun notifications() = shot("notifications")

    private companion object {
        const val META = """{"costume_statuses":["AVAILABLE","ISSUED"],"costume_categories":["CLOTHING","FOOTWEAR"],"costume_sources":["PURCHASED","RENTED"],
            "standard_locations":["Warehouse"],"finance_roles":["ADMIN"],"costume_types":{"CLOTHING":["Coat"]},"cleaning_types":["DRY_CLEANING"],
            "media_types":["IMAGE","FILE","LINK"],"expense_categories":["PURCHASE","LAUNDRY","RENTAL"]}"""
    }
}
