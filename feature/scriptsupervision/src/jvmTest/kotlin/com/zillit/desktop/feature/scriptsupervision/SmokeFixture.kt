package com.zillit.desktop.feature.scriptsupervision

import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.scriptsupervision.data.ScriptSupervisionApi
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptSupervisionViewer
import com.zillit.desktop.feature.scriptsupervision.ui.CastState
import com.zillit.desktop.feature.scriptsupervision.ui.ScenesState
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsNav
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
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

/**
 * The canned Continuity Script Supervision service, and the state a screen
 * needs around it.
 *
 * One fixture rather than one per test: it is a hundred lines of JSON shaped
 * to the service's real answers, and a second copy would drift from this one
 * the first time a screen started reading a new field.
 *
 * Every record carries the fields its screens key off INCLUDING the ones the
 * service leaves out of a thin record, so a missing-key read shows up here
 * rather than on somebody's screen.
 */
class SmokeFixture {

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
        "day_night":"DAY","shooting_day":2,"location":"Unit base","status":"in_progress",
        "page_count_eighths":12,"set_name":"KITCHEN","scene_description":"Ravi comes home.",
        "script_text":["INT. KITCHEN - DAY","RAVI picks up the tiffin.","bicycle leans on the wall."]}"""

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
        // The dashboard's own two lists. The matching notes carry a
        // resolved one and an open high-priority one, so both halves of the
        // two-part bar and the high-priority tile have something to show.
        // Deliberately UNEVEN: prop_match has four notes to continuity_error's
        // one, so the dashboard's shared category axis is visible in a render
        // rather than taken on trust — with equal totals every bar fills its
        // track and a per-row normalisation bug looks identical to the fix.
        path.contains("/continuity/matching-notes/") -> """[{"_id":"m1","scene_id":"s1",
            "category":"prop_match","resolved":true,"priority":"low"},
            {"_id":"m3","scene_id":"s1","category":"prop_match","resolved":true,"priority":"low"},
            {"_id":"m4","scene_id":"s1","category":"prop_match","resolved":true,"priority":"low"},
            {"_id":"m5","scene_id":"s1","category":"prop_match","resolved":false,"priority":"low"},
            {"_id":"m2","scene_id":"s1","category":"continuity_error","resolved":false,"priority":"high"}]"""
        path.contains("/continuity/scene-notes/") -> """[{"_id":"n1","scene_id":"s1",
            "character_id":"c1","character_name":"RAVI","color":"#ef4444","text":"Cuff"}]"""
        path.contains("/continuity/scene-assets/") -> """[{"_id":"x1","scene_id":"s1",
            "category":"prop","name":"Tiffin","description":"","character_name":"RAVI"}]"""
        // No default template, so the quick log draws its own form rather
        // than handing over to the template log. Before the catch-all, which
        // would otherwise answer this with a log record.
        // The lined script's coverage. Deliberately shaped to exercise the
        // page's three rules at once: 1A appears TWICE (one setup, two
        // stretches, so it must keep one colour), 2B is off screen (dashed),
        // and the spans OVERLAP, which is the normal case — two cameras on
        // the same words. Before the catch-all, which answers a log record.
        path.contains("/continuity/lined-script/") -> """[
            {"_id":"ls1","scene_id":"s1","shot_number":"1A","shot_size":"WS",
             "character_focus":"RAVI","shot_description":"Ravi enters",
             "page_number":"14","line_start":1,"line_end":12,"line_type":"straight",
             "total_takes":4,"preferred_take":3,"circled_takes":[3,4],"notes":"Watch the tiffin"},
            {"_id":"ls2","scene_id":"s1","shot_number":"2B","shot_size":"MCU",
             "character_focus":"AMMA","shot_description":"Reverse, off screen",
             "page_number":"14","line_start":5,"line_end":20,"line_type":"squiggly",
             "total_takes":2,"preferred_take":0,"circled_takes":[],"notes":""},
            {"_id":"ls3","scene_id":"s1","shot_number":"1A","shot_size":"WS",
             "character_focus":"RAVI","shot_description":"Ravi enters",
             "page_number":"14","line_start":25,"line_end":40,"line_type":"straight",
             "total_takes":1,"preferred_take":1,"circled_takes":[1],"notes":""},
            {"_id":"ls4","scene_id":"s1","shot_number":"3C","shot_size":"CU",
             "character_focus":"RAVI","shot_description":"Insert, the tiffin",
             "page_number":"15","line_start":30,"line_end":55,"line_type":"straight",
             "total_takes":0,"preferred_take":0,"circled_takes":[],"notes":""}]"""
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
        // The audit trail: paged as `{ logs, total }`, with one entry that
        // CAN be restored (previous_state + a restorable module) and one
        // that cannot, so both branches of the Restore column draw.
        path.contains("/audit/") -> """{"total":2,"logs":[
            {"_id":"a1","timestamp":"2026-10-23 18:02:11","action":"delete","module":"sound_report",
             "record_id":"64f0aa11bb22cc33dd44ee55","user_name":"Asha","description":"Deleted a sound report",
             "previous_state":{"notes":"old"},"changes":{"notes":{"from":"old","to":""}}},
            {"_id":"a2","timestamp":"2026-10-23 17:40:02","action":"update","module":"character",
             "record_id":"64f0aa11bb22cc33dd44ee66","user_name":"Ravi","description":"Renamed a character"}]}"""
        // The production summary behind the Production tiles.
        // Not AI despite the path: the service works these out from the
        // coverage lines. Two kinds, because the page shades them differently.
        path.contains("/ai/coverage-gaps/") -> """[
            {"from_line":56,"to_line":70,"gap_type":"no_coverage"},
            {"from_line":21,"to_line":24,"gap_type":"off_screen_only"}]"""
        path.contains("/dashboard/summary/") -> """{"counts":{"characters":1,"scenes":1,
            "story_days":1,"boxes":1,"shooting_days":1,"actors":1,"documents":1},"filmed_pct":12,
            "recent_activity":[{"_id":"h1","user_name":"Asha","action":"create","module":"scene_notes",
            "description":"Added a note","timestamp":1761250000000}]}"""
        path.contains("/breakdown/elements/") -> """[{"_id":"e1","name":"Steel tiffin",
            "category_slug":"props","description":"Hero prop","scene_count":9}]"""
        // One scene's breakdown: the scene, its categories and every tag,
        // including a SUGGESTED one so the review dialog has a subject.
        path.contains("/breakdown/sheet/") -> """{"scene":$scene,
            "categories":[{"category":{"_id":"c1","slug":"props","name":"Props","color":"#2563eb"},
            "tags":[{"_id":"tg1","element_id":"e1","element_name":"Steel tiffin",
            "tagged_text":"tiffin","line_index":1,"char_start":18,"char_end":24,"status":"confirmed"},
            {"_id":"tg2","element_id":"e2","element_name":"Bicycle","tagged_text":"bicycle",
            "line_index":2,"char_start":0,"char_end":7,"status":"suggested"}]},
            {"category":{"_id":"c2","slug":"vehicles","name":"Vehicles","color":"#dc2626"},"tags":[]}]}"""
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


    val ctx: SsCtx = context()

    val scenes: ScenesState = ScenesState(SCOPE, ctx).apply { reload() }

    val cast: CastState = CastState(SCOPE, ctx).apply { reload() }

    /** A nav parked on [page], optionally at an inner position of it. */
    fun nav(page: SsPage, view: SsView = SsView.Root): SsNav = SsNav().apply {
        this.page = page
        if (view != SsView.Root) go(view)
    }

    private class Factory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
        override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
    }

    private companion object {
        val SCOPE = CoroutineScope(Dispatchers.Unconfined)
    }
}
