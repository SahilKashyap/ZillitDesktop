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
import com.zillit.desktop.feature.scriptsupervision.domain.CharacterSection
import com.zillit.desktop.feature.scriptsupervision.ui.CastState
import com.zillit.desktop.feature.scriptsupervision.ui.LocalCast
import com.zillit.desktop.feature.scriptsupervision.ui.LocalNav
import com.zillit.desktop.feature.scriptsupervision.ui.LocalScenes
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.ScenesState
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsNav
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
import com.zillit.desktop.feature.scriptsupervision.ui.SsRoutes
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
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

    /**
     * One character, with both halves the screens read: the photos (the first
     * is primary by default), the aliases the script may use instead of the
     * name, the hand-made scene links, and a relationship.
     */
    private val character = """{"_id":"c1","name":"RAVI","number":"1","character_type":"principal",
        "aliases":["THE BOY"],"gender":"male","age":"12","actor_id":"a1","actor_name":"PRIYA RAO",
        "description":"The son.","scene_ids":["s1"],
        "photos":[{"url":"c/1.jpg","is_primary":true}],
        "relationships":[{"character_id":"c2","character_name":"RAVI (stunt)","type":"Stunt performer"}]}"""

    private val scene = """{"_id":"s1","scene_number":"14","scene_name":"INT. KITCHEN","int_ext":"INT",
        "day_night":"DAY","shooting_day":2,"location":"Unit base","status":"in_progress"}"""

    private fun reply(url: String): String {
        val path = url.substringBefore('?')
        val data = pagedReply(path) ?: castReply(path) ?: continuityReply(path) ?: otherReply(path) ?: "[]"
        return """{"status":1,"data":$data}"""
    }

    /** The two resources the service PAGES, which answer `{ data, total }`. */
    private fun pagedReply(path: String): String? = when {
        path.contains("/scenes/") -> """{"data":[$scene],"total":1}"""
        path.contains("/takes/") -> """{"data":[{"_id":"t1","scene_id":"s1","take_number":3,
            "slate_number":"14A","camera_roll":"A001","sound_roll":"S001","is_circled":true}],"total":1}"""
        else -> null
    }

    /**
     * The `/continuity/<folder>` records, which answer a plain array.
     *
     * Each carries the fields its screens key off, INCLUDING the ones the
     * service leaves out of a thin record, so a missing-key read shows here.
     */
    private fun castReply(path: String): String? = when {
        path.contains("/continuity/characters/detail/") -> character
        path.contains("/continuity/characters/") -> "[$character]"
        path.contains("/continuity/actors/") -> """[{"_id":"a1","name":"PRIYA RAO",
            "character_id":"c1","character_name":"RAVI","phone":"+91 90000 00000",
            "email":"priya@example.com","agent_name":"Agency","hair_color":"black",
            "eye_color":"brown","measurements":{"height":"170","chest":"90"},
            "allergies":["latex"],"tattoos":[{"location":"left wrist","description":"anchor"}]}]"""
        path.contains("/continuity/story-days/") -> """[{"_id":"d1","label":"Story Day 1",
            "order":1,"type":"normal","story_date":"Oct 5, 2026","time_span":"Continuous",
            "scene_numbers":["14"],"description":"The first morning."}]"""
        path.contains("/continuity/looks/") -> """[{"_id":"l1","name":"Day one blues",
            "look_number":"3","department":"costume","status":"established","character_id":"c1",
            "character_name":"RAVI","scene_ids":["s1"],"story_day_ids":["d1"],
            "reference_image_urls":["looks/key.jpg"],"continuity_notes":"Cuff turned up",
            "products":[{"name":"Base","brand":"B","shade":"2"}],
            "details":[{"type":"color","color":"#102030","text":"Shirt"}]}]"""
        else -> null
    }

    /**
     * The looks group's folders, and the twelve logs behind the catch-all.
     *
     * Split from [castReply] only to keep each `when` inside the gate's
     * complexity limit; together they are the web's one `crud(folder)`.
     */
    private fun continuityReply(path: String): String? = when {
        path.contains("/continuity/garments/") -> """[{"_id":"g1","name":"Blue shirt",
            "category":"top","size":"M","condition":"aged","source":"bought","look_id":"l1",
            "character_id":"c1","character_name":"RAVI","quantity":2,"cost":1200,
            "photo_urls":["g/1.jpg"],"image_url":"g/1.jpg"}]"""
        path.contains("/continuity/props-items/") -> """[{"_id":"p1","name":"Steel tiffin",
            "category":"prop","character_id":"c1","character_name":"RAVI","quantity":1,
            "cost":0,"photo_urls":[],"image_url":""}]"""
        path.contains("/continuity/props-wardrobe/") -> """[{"_id":"w1","scene_id":"s1",
            "character":"RAVI","wardrobe":"Blue shirt, sleeves up","makeup":"Sweat sheen",
            "props":[{"name":"Tiffin","state":"half open","notes":"left hand"}]}]"""
        path.contains("/continuity/boxes/") -> """[{"_id":"b1","box_number":"12",
            "label":"Ravi hero","department":"costume","status":"in_use","location":"Truck 2",
            "checked_out_to":"Asha","notes":"","contents":[{"name":"Shirt","quantity":2}]}]"""
        path.contains("/continuity/scene-notes/") -> """[{"_id":"n1","scene_id":"s1",
            "character_id":"c1","character_name":"RAVI","color":"#ef4444","text":"Cuff"}]"""
        path.contains("/continuity/scene-assets/") -> """[{"_id":"x1","scene_id":"s1",
            "category":"prop","name":"Tiffin","description":"","character_name":"RAVI"}]"""
        // No default template, so the quick log draws its own form rather
        // than handing over to the template log. Before the catch-all, which
        // would otherwise answer this with a log record.
        path.contains("/continuity/templates/") -> "[]"
        // Every other folder: the twelve logs, which are one screen behind a spec.
        path.contains("/continuity/") -> """[{"_id":"r1","scene_id":"s1","notes":"note",
            "camera":"A","lens_mm":35,"shot_size":"MS","sound_roll":"S001","track_number":2,
            "character":"ANNA","risk_level":"high","plate_type":"clean_plate","status":"captured",
            "preferred_take":3,"page_number":"12","pages_shot":"1 2/8","setups":4}]"""
        else -> null
    }

    /** The resources that are NOT continuity folders, and are each their own shape. */
    private fun otherReply(path: String): String? = when {
        // Its own resource, and the service filters these itself.
        path.contains("/continuity-photos/") -> """[{"_id":"ph1","url":"photos/1.jpg",
            "scene_id":"s1","scene_number":"14","take_number":"3","category":"wardrobe",
            "characters":["RAVI"],"caption":"Cuff detail","notes":""}]"""
        // The character hub: one answer carrying the record AND the counts
        // behind every tile.
        path.contains("/dashboard/character/") -> """{"character":$character,
            "counts":{"scenes":1,"script_days":1,"shooting_days":1,"costume_looks":1,
            "hair_looks":0,"makeup_looks":0,"wardrobe_items":1,"props_items":1}}"""
        path.contains("/upload/download-url") -> """{"presigned_url":""}"""
        // The production summary behind the Production tiles.
        path.contains("/dashboard/summary/") -> """{"counts":{"characters":1,"scenes":1,
            "story_days":1,"boxes":1,"shooting_days":1,"actors":1,"documents":1},"filmed_pct":12,
            "recent_activity":[{"_id":"h1","user_name":"Asha","action":"create","module":"scene_notes",
            "description":"Added a note","timestamp":1761250000000}]}"""
        path.contains("/breakdown/elements/") -> """[{"_id":"e1","name":"Steel tiffin",
            "category_slug":"props","description":"Hero prop","scene_count":9}]"""
        // The quick log asks for the scene's last take before it draws its
        // form: the take number and the carried camera settings come out of
        // it, so a canned one is what makes that screen testable at all.
        path.contains("/quick-log/last-take/") -> """{"_id":"t9","scene_id":"s1","take_number":4,
            "slate_number":"14A","camera_roll":"A001","sound_roll":"S001","lens_mm":35,
            "fps":25,"shutter_angle":180,"iso_ei":800,"color_temp":"5600K","nd_filter":"0.6",
            "t_stop":"T2.8"}"""
        else -> null
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

    /**
     * Composes [page], optionally at an inner position of it ([view]).
     *
     * The inner views are driven too, because the detail screens are where the
     * reads live — a character's section, a day's scenes, a look — and a page's
     * own list says nothing about whether those draw.
     */
    private fun draws(page: SsPage, view: SsView = SsView.Root) = runComposeUiTest {
        val ctx = context()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val scenes = ScenesState(scope, ctx).apply { reload() }
        val cast = CastState(scope, ctx).apply { reload() }
        val nav = SsNav().apply {
            this.page = page
            if (view != SsView.Root) go(view)
        }
        setContent {
            ZillitTheme {
                CompositionLocalProvider(
                    LocalSs provides ctx,
                    LocalScenes provides scenes,
                    LocalCast provides cast,
                    LocalNav provides nav,
                ) {
                    ZillitScrollColumn { Column { SsRoutes(page) { } } }
                }
            }
        }
        waitForIdle()
    }

    @Test
    fun `every page of the menu draws`() {
        SsPage.entries.forEach { draws(it) }
    }

    @Test
    fun `every page of a character draws`() {
        draws(SsPage.Characters, SsView.Character("c1"))
        CharacterSection.entries.forEach { section ->
            draws(SsPage.Characters, SsView.Section("c1", section))
        }
        // A story day's scenes, a shooting day's scenes, and the day keys that
        // mean "nothing has claimed this": the two the groupers produce.
        draws(SsPage.Characters, SsView.SectionDay("c1", CharacterSection.ScriptDays, "Story Day 1"))
        draws(SsPage.Characters, SsView.SectionDay("c1", CharacterSection.ScriptDays, "unassigned"))
        draws(SsPage.Characters, SsView.SectionDay("c1", CharacterSection.ShootingDays, "2"))
        draws(SsPage.Characters, SsView.SectionDay("c1", CharacterSection.ShootingDays, "0"))
        draws(SsPage.Characters, SsView.CharacterScene("c1", "s1"))
    }

    @Test
    fun `a look, a wardrobe item and a prop draw`() {
        // On the board that owns it, and from the character that wears it.
        draws(SsPage.CostumeLooks, SsView.Look("l1", "c1", "costume"))
        draws(SsPage.HairLooks, SsView.Look("l1", "c1", "hair"))
        draws(SsPage.Characters, SsView.Look("l1", "c1", "makeup"))
        draws(SsPage.Characters, SsView.WardrobeItem("g1"))
        draws(SsPage.Characters, SsView.Prop("p1"))
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }
}
