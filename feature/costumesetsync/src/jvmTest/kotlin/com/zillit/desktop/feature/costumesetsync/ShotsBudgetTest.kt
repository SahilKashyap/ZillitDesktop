package com.zillit.desktop.feature.costumesetsync

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import com.zillit.desktop.feature.costumesetsync.ui.screens.CleaningList
import com.zillit.desktop.feature.costumesetsync.ui.screens.RecordActions
import com.zillit.desktop.feature.costumesetsync.ui.DateTimeInput
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

/** Opt-in: `SYNC_SHOTS=1` writes PNGs of the Budget sheet, the Sink list view and the shared inputs to `build/shots/bg_*`. */
@OptIn(ExperimentalTestApi::class)
class ShotsBudgetTest {
    private val now = 1_772_755_200_000L
    private val day = 86_400_000L

    private fun exp(id: String, code: String, acct: String, desc: String, cat: String, amt: Double, payee: String = "", q: Double? = null, unit: String = "", mult: Double = 1.0, rate: Double? = null, scene: String = "", ch: String = "") =
        """{"_id":"$id","account_code":"$code","account_name":"$acct","description":"$desc","category":"$cat","amount":$amt,"payee":"$payee","currency":"GBP","date":${now - day},"created":${now - day},
            ${if (q != null) "\"quantity\":$q,\"unit\":\"$unit\",\"multiplier\":$mult,\"rate\":$rate," else ""}"scene_id":"$scene","character_id":"$ch"}"""

    private val expenses = listOf(
        exp("e1", "3000", "Wardrobe Supervisor", "Supervisor weekly rate", "LABOUR", 4800.0, "Dina Marsh", 4.0, "Wks", 1.0, 1200.0, "s1", "c1"),
        exp("e2", "3000", "Wardrobe Supervisor", "Overtime Saturday", "LABOUR", 640.5, "Dina Marsh", 8.0, "Hrs", 1.5, 53.375, "s1", "c1"),
        exp("e3", "3000", "Wardrobe Supervisor", "Assistant day rate", "LABOUR", 1500.0, "Priya Nair", 5.0, "Days", 1.0, 300.0, "s2", "c2"),
        exp("e4", "3200", "Costume Rental", "Victorian corset hire", "RENTAL", 980.0, "Angels Costumes", 2.0, "Wks", 1.0, 490.0, "s2", "c1"),
        exp("e5", "3200", "Costume Rental", "Velvet gown hire, long description that wraps onto the second line of the cell", "RENTAL", 2240.0, "", 4.0, "Wks", 1.0, 560.0, "", "c2"),
        exp("e6", "3400", "Dry Cleaning", "", "LAUNDRY", 312.4, "", null, "", 1.0, null, "s1", ""),
        exp("e7", "", "", "Petty cash buttons", "OTHER", 24.0, "", 1.0, "Lot", 1.0, 24.0, "", ""),
    ).joinToString(",")

    private fun ticket(id: String, status: String, prio: String, emergency: Boolean, problem: String, who: String, eta: Long) =
        """{"_id":"$id","status":"$status","priority":"$prio","is_emergency":$emergency,"problem":"$problem","cleaning_type":"DRY_CLEANING",
            "costume":{"_id":"k$id","asset_number":"CST-00000$id","name":"Velvet evening gown"},"scene":{"_id":"s1","number":"12"},
            "assigned_to_name":"$who","expected_ready_at":$eta,"created":${now - day / 3},"completed_at":${if (status == "READY") now else 0}}"""

    private val tickets = listOf(
        ticket("1", "REQUESTED", "NORMAL", false, "Wine stain on the hem", "", 0),
        ticket("2", "CLEANING", "HIGH", false, "Mud on the cuffs after the river scene", "Priya", now + day),
        ticket("4", "CLEANING", "URGENT", true, "Blood splatter, reshoot at 2pm", "Marco", now + day / 8),
        ticket("5", "DRYING", "LOW", false, "General freshen", "Marco", now + 2 * day),
        ticket("8", "READY", "NORMAL", false, "Collar stain", "Sam", 0),
    )

    private fun reply(url: String): String {
        val path = url.substringBefore('?')
        val data = when {
            path.endsWith("/meta") -> META
            path.endsWith("/reports/budget") -> """{"expenses":[$expenses],"by_category":{"LABOUR":6940.5,"RENTAL":3220,"LAUNDRY":312.4},"inventory_value":58450,"rental_committed":3220}"""
            path.endsWith("/scenes") -> """[{"_id":"s1","number":"12","name":"River crossing","location":"Thames bank"},{"_id":"s2","number":"14","name":"Ballroom"}]"""
            path.endsWith("/characters") -> """[{"_id":"c1","name":"Anna Karenina-Whitfield","cast_number":"1","actor":{"name":"Keira"}},{"_id":"c2","name":"Count Vronsky","cast_number":"2"}]"""
            path.endsWith("/vendors") -> "[]"
            path.endsWith("/comments/counts") -> """{"e1":3,"e4":1}"""
            path.endsWith("/comments") -> """[{"_id":"m1","user_id":"me","user_name":"Sam Wardrobe","body":"Can we get the rental invoice?","created":${now - 3_600_000}},
                {"_id":"m2","user_id":"u2","user_name":"Priya Nair","body":"Sent it over this morning.\nCheck your inbox.","created":${now - 600_000}}]"""
            else -> "[]"
        }
        return """{"status":1,"data":$data}"""
    }

    private fun ctx(route: String, withSheet: Boolean = false): SyncCtx {
        val engine = MockEngine { request ->
            respond(reply(request.url.toString()), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
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
            api = SyncOnsetApi(client, config, projectId = { "p1" }, multipart = if (withSheet) com.zillit.desktop.feature.costumesetsync.data.MultipartSender { _, _, _, _, _ ->
                com.zillit.desktop.core.common.ZillitResult.Success(com.zillit.desktop.feature.costumesetsync.data.Answer(Json.parseToJsonElement(SHEET), null))
            } else null),
            viewer = SyncViewer(resolved = true, enabled = true, canView = true, canPost = true, canDownload = true),
            project = SyncProject(Rec(Json.parseToJsonElement("""{"project_name":"Demo","my_role":"ADMIN","currency":"GBP"}""") as JsonObject), setOf("ADMIN")),
            meta = Rec(Json.parseToJsonElement(META) as JsonObject),
            nav = SyncNav(SyncRoute.parse(route)),
            scope = CoroutineScope(Dispatchers.Unconfined),
            projectId = "p1",
            currentUserId = "me",
            now = { now },
            host = if (withSheet) object : com.zillit.desktop.feature.costumesetsync.domain.SyncHost by com.zillit.desktop.feature.costumesetsync.domain.NoHost {
                override suspend fun pick(extensions: Set<String>, multiple: Boolean) = listOf(com.zillit.desktop.feature.costumesetsync.domain.PickedFile("budget.xlsx", ByteArray(1), "application/octet-stream"))
            } else com.zillit.desktop.feature.costumesetsync.domain.NoHost,
        )
    }

    private fun shot(name: String, route: String, width: Dp = 1240.dp, height: Dp = 800.dp, withSheet: Boolean = false, after: (androidx.compose.ui.test.SkikoComposeUiTest.() -> Unit)? = null, content: (@Composable () -> Unit)? = null) {
        if (System.getenv("SYNC_SHOTS") == null) return
        androidx.compose.ui.test.runSkikoComposeUiTest(size = androidx.compose.ui.geometry.Size(width.value, height.value)) {
            val context = ctx(route, withSheet)
            setContent {
                ZillitTheme(animateThemeChange = false) {
                    CompositionLocalProvider(LocalSync provides context) {
                        Box(Modifier.size(width, height)) {
                            if (content != null) content() else SyncFrame(null, 0, true, {}, {})
                        }
                    }
                }
            }
            waitForIdle()
            after?.invoke(this)
            waitForIdle()
            val roots = onAllNodes(isRoot())
            val image = roots[roots.fetchSemanticsNodes().size - 1].captureToImage()
            val dir = File("build/shots").apply { mkdirs() }
            File(dir, "bg_$name.png").writeBytes(Image.makeFromBitmap(image.asSkiaBitmap()).encodeToData()!!.bytes)
        }
    }

    @Test fun budgetAll() = shot("budget_all", "budget")
    @Test fun budgetAccounts() = shot("budget_accounts", "budget?tab=accounts", height = 1000.dp)
    @Test fun budgetFull() = shot("budget_full", "budget?tab=full", height = 1400.dp)
    @Test fun budgetNarrow() = shot("budget_narrow", "budget", 1000.dp)

    @Test fun budgetLineDialog() = shot("dlg_line", "budget", 1240.dp, 900.dp) {
        val ctx = LocalSync.current
        com.zillit.desktop.feature.costumesetsync.ui.screens.BudgetLineDialog(
            true, null,
            com.zillit.desktop.feature.costumesetsync.ui.screens.BudgetLineContext("GBP", listOf("LABOUR", "RENTAL", "LAUNDRY", "OTHER"), emptyList(), emptyList(), emptyList(), emptyList()),
            com.zillit.desktop.feature.costumesetsync.ui.screens.BudgetFormHolder(), {}, {},
        )
    }
    @Test fun budgetUploadEmpty() = shot("dlg_upload_empty", "budget", 1240.dp, 700.dp) {
        com.zillit.desktop.feature.costumesetsync.ui.screens.BudgetUpload(true, {}, {}, "GBP")
    }

    @Test fun budgetUploadPreview() = shot("dlg_upload_preview", "budget", 1240.dp, 800.dp, withSheet = true, after = {
        onNodeWithText("Choose file").performClick()
    }) {
        com.zillit.desktop.feature.costumesetsync.ui.screens.BudgetUpload(true, {}, {}, "GBP")
    }

    @Test fun cleaningList() = shot("cleaning_list", "cleaning") {
        Column(Modifier.padding(24.dp)) {
            CleaningList(tickets.map { Rec(Json.parseToJsonElement(it) as JsonObject) }, "")
        }
    }

    @Test fun dateTime() = shot("datetime", "cleaning", 700.dp, 200.dp) {
        var date by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("2026-03-07") }
        var time by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("17:30") }
        Column(Modifier.padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            DateTimeInput(date, time, { date = it }, { time = it }, "Deadline")
            DateTimeInput("", "", { date = it }, { time = it }, "Empty")
        }
    }

    @Test fun recordActions() = shot("record_actions", "cleaning", 700.dp, 160.dp) {
        Column(Modifier.padding(24.dp)) {
            RecordActions("EXPENSE", "e1", "Supervisor", "summary", count = 3)
            RecordActions("EXPENSE", "e9", "Supervisor", "summary")
        }
    }

    @Test fun chat() = shot("chat", "cleaning", 900.dp, 700.dp) {
        com.zillit.desktop.feature.costumesetsync.ui.screens.RecordChatDialog("EXPENSE", "e1", "Supervisor weekly rate", {}, {})
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private companion object {
        const val SHEET = """{"file_name":"budget.xlsx","sheets":["Wardrobe"],"skipped_sheets":[],"lines":[
            {"sheet":"Wardrobe","row":2,"account_code":"3000","account_name":"Wardrobe Supervisor","description":"Supervisor weekly","category":"LABOUR","amount":4800,"currency":"GBP","scene":"12","scene_id":"s1","character":"Anna","character_id":"c1","vendor":"","department":"WARDROBE"},
            {"sheet":"Wardrobe","row":3,"account_code":"3200","account_name":"Costume Rental","description":"Velvet gown hire","category":"RENTAL","amount":2240,"currency":"GBP","scene":"99","scene_id":"","character":"","vendor":"Angels Costumes","vendor_id":"","department":"WARDROBE"},
            {"sheet":"Wardrobe","row":4,"description":"Grand total","category":"OTHER","amount":7040,"is_total":true,"department":"WARDROBE"}]}"""
        const val META = """{"costume_statuses":["AVAILABLE"],"expense_categories":["LABOUR","RENTAL","LAUNDRY","OTHER"],"finance_roles":["ADMIN"],
            "priorities":["LOW","NORMAL","HIGH","URGENT"],"cleaning_types":["DRY_CLEANING"]}"""
    }
}
