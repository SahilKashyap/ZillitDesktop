@file:Suppress("MaxLineLength", "LongParameterList") // Wire bodies read best on one line.

package com.zillit.desktop.feature.selectstills

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.config.AppConfig
import com.zillit.desktop.core.config.Environment
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.network.ApiClient
import com.zillit.desktop.core.network.HttpClientFactory
import com.zillit.desktop.feature.selectstills.data.StillsRepositoryImpl
import com.zillit.desktop.feature.selectstills.domain.Decision
import com.zillit.desktop.feature.selectstills.domain.FaceEdit
import com.zillit.desktop.feature.selectstills.domain.FaceState
import com.zillit.desktop.feature.selectstills.domain.MemberDraft
import com.zillit.desktop.feature.selectstills.domain.PhotoFilters
import com.zillit.desktop.feature.selectstills.domain.PhotoKind
import com.zillit.desktop.feature.selectstills.domain.PhotoStatus
import com.zillit.desktop.feature.selectstills.domain.PublicState
import com.zillit.desktop.feature.selectstills.domain.Recognition
import com.zillit.desktop.feature.selectstills.domain.ReviewTab
import com.zillit.desktop.feature.selectstills.domain.ViewerScope
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The wire contract: what this client sends `zillit_selectstills`, and what it
 * makes of the answers.
 *
 * Pinned rather than trusted, because every field is optional on the wire and
 * a renamed one would come back as its default — silently, which is the one
 * failure a screen cannot show.
 */
class StillsWireShapeTest {

    private class Sent(val method: HttpMethod, val url: String, val body: JsonObject?, val raw: String?) {
        val query: Map<String, String>
            get() = url.substringAfter('?', "").split('&').filter { it.isNotBlank() }
                .associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    private val sent = mutableListOf<Sent>()

    private fun repository(
        status: HttpStatusCode = HttpStatusCode.OK,
        services: Map<ZillitService, String> = ZillitService.entries.associateWith { "https://${it.name.lowercase()}.test" },
        answer: (Sent) -> String,
    ): StillsRepositoryImpl {
        val engine = MockEngine { request: HttpRequestData ->
            val raw = (request.body as? TextContent)?.text
            val call = Sent(request.method, request.url.toString(), raw?.let { Json.parseToJsonElement(it) as? JsonObject }, raw)
            sent += call
            respond(answer(call), status, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return StillsRepositoryImpl(
            apiClient = ApiClient(
                httpClient = HttpClientFactory.create({ StillsMockEngineFactory(engine) }),
                headerProvider = { _, _, _, _ -> emptyMap() },
            ),
            config = AppConfig(environment = Environment.Develop, services = services, realtime = emptyMap()),
        )
    }

    private fun ok(data: String, message: String? = null) =
        """{"status":1${message?.let { ",\"message\":\"$it\"" }.orEmpty()},"data":$data}"""

    private fun <T> ZillitResult<T>.unwrap(): T = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> error("the call failed: $error")
    }

    private val last get() = sent.last()

    // -- the reader --------------------------------------------------------------------

    @Test
    fun `me carries the reader's standing, their clients and the upload limits`() = runTest {
        val repo = repository {
            ok(
                """
                {"user_id":"u1","can_post":true,"is_admin":false,"scope":"all",
                 "agent_for":[{"member_id":"m1","name":"Anna Bell",
                   "allowance":{"1":{"limit":2,"used":1,"remaining":1},
                                "6+":{"limit":null,"used":0,"remaining":null}}}],
                 "settings":{"attested":true,"viewer_scope":"cleared",
                             "default_discard_limits":{"1":3},
                             "thresholds":{"auto":82,"suggest":65,"margin":8}},
                 "storage_supported":true,"region_supported":true,
                 "upload":{"types":["image/jpeg","image/webp"],"max_bytes":52428800},
                 "queue":{"waiting":7}}
                """.trimIndent(),
            )
        }
        val me = repo.me().unwrap()

        assertEquals("u1", me.userId)
        assertTrue(me.canPost)
        assertFalse(me.isAdmin)
        assertEquals(ViewerScope.All, me.scope)
        assertTrue(me.usable)
        assertEquals(7, me.queueWaiting)
        assertEquals(listOf("image/jpeg", "image/webp"), me.upload.types)
        assertEquals(52_428_800L, me.upload.maxBytes)

        val client = me.agentFor.single()
        assertEquals("Anna Bell", client.name)
        assertEquals(2, client.allowance["1"]?.limit)
        assertEquals(1, client.allowance["1"]?.remaining)
        // A null limit is "uncapped", not zero — the whole allowance rule turns on it.
        assertNull(client.allowance["6+"]?.limit)

        assertTrue(me.settings.attested)
        assertEquals(ViewerScope.Cleared, me.settings.viewerScope)
        assertEquals(mapOf("1" to 3), me.settings.defaultDiscardLimits)
        assertEquals(82, me.settings.thresholds.auto)
        assertEquals(8, me.settings.thresholds.margin)
        assertEquals("https://stillkills.test/api/v2/still-kills/me", last.url)
    }

    @Test
    fun `a scope or a state this build does not know reads as the safest default`() = runTest {
        val repo = repository { ok("""{"scope":"everything-ever"}""") }
        // Not "all": an unknown scope must not widen what somebody is shown.
        assertEquals(ViewerScope.Cleared, repo.me().unwrap().scope)
    }

    // -- the cast ----------------------------------------------------------------------

    @Test
    fun `a members answer is wrapped under members, and approval defaults to needed`() = runTest {
        val repo = repository {
            ok(
                """
                {"members":[
                  {"id":"m1","name":"Anna Bell","character_name":"Mira",
                   "headshots":[{"id":"h1","url":"https://s/h1"}],
                   "agent_user_id":"u9","discard_limits":{"2":1},
                   "recognition":"suggest_only","photo_count":12,"is_my_client":true,
                   "created":1760000000000,"learned_faces":3},
                  {"id":"m2","name":"Nobody"}]}
                """.trimIndent(),
            )
        }
        val members = repo.members().unwrap()
        assertEquals(2, members.size)

        val anna = members.first()
        assertEquals("Mira", anna.characterName)
        assertEquals("https://s/h1", anna.headshotUrl)
        assertEquals("u9", anna.agentUserId)
        assertEquals(mapOf("2" to 1), anna.discardLimits)
        assertEquals(Recognition.SuggestOnly, anna.recognition)
        assertEquals(12, anna.photoCount)
        assertTrue(anna.isMyClient)
        assertEquals(3, anna.learnedFaces)

        // An absent `approval_required` means somebody DOES have to approve:
        // reading it as false would publish photos nobody cleared.
        assertTrue(members[1].approvalRequired)
        assertEquals(Recognition.Auto, members[1].recognition)
    }

    @Test
    fun `enrolling without an agent sends a null, never a blank id`() = runTest {
        val repo = repository { ok("""{"id":"m1","name":"Anna"}""") }
        repo.createMember(
            MemberDraft(
                name = "  Anna Bell  ",
                approvalRequired = true,
                agentUserId = null,
                consent = true,
                discardLimits = mapOf("1" to 2),
            ),
        )
        val body = last.raw.orEmpty()
        assertTrue(body.contains(""""name":"Anna Bell""""))
        assertTrue(body.contains(""""agent_user_id":null"""))
        assertTrue(body.contains(""""discard_limits":{"1":2}"""))
        assertTrue(body.contains(""""consent":true"""))
    }

    @Test
    fun `nobody has to approve, so no allowance and no agent are sent`() = runTest {
        val repo = repository { ok("""{"id":"m1","name":"Anna"}""") }
        repo.createMember(
            MemberDraft(name = "Anna", approvalRequired = false, agentUserId = "u9", discardLimits = mapOf("1" to 2)),
        )
        val body = last.raw.orEmpty()
        assertFalse(body.contains("discard_limits"))
        // An agent is meaningless without approval, so none is named.
        assertTrue(body.contains(""""agent_user_id":null"""))
    }

    @Test
    fun `an update sends only what changed`() = runTest {
        val repo = repository { ok("""{"id":"m1","name":"Anna"}""") }
        repo.updateMember("m1", name = "Anna B", characterName = null, recognition = null, approvalRequired = false)
        assertEquals("""{"name":"Anna B","approval_required":false}""", last.raw)
    }

    @Test
    fun `force sends the override as a query parameter, as the web does`() = runTest {
        val repo = repository { ok("{}") }
        repo.removeMember("m1", force = true)
        assertEquals("true", last.query["force"])
        repo.removeMember("m1", force = false)
        assertNull(last.query["force"])
    }

    @Test
    fun `a member removed while named in photos refuses with 409`() = runTest {
        val repo = repository(status = HttpStatusCode.Conflict) {
            """{"status":0,"message":"still_kills_member_in_use","data":{"photos":4}}"""
        }
        val error = (repo.removeMember("m1") as ZillitResult.Failure).error as ZillitError.Http
        assertEquals(409, error.status)
        assertEquals("still_kills_member_in_use", error.serverMessage)
    }

    // -- photos ------------------------------------------------------------------------

    @Test
    fun `a photo page carries the cursor and when its links stop working`() = runTest {
        val repo = repository {
            ok(
                """
                {"photos":[
                  {"id":"p1","sort_at":1760000000,"rev":4,"status":"done","thumb_url":"https://s/p1",
                   "original_name":"A001.jpg","public_state":"pending","has_approvals":true,
                   "people":3,"names":["Anna","Ben"],"more_names":1,"unnamed":2},
                  {"id":"p2","status":"failed","error_code":"too_large"}],
                 "has_more":true,"next":"cursor-9","links_expire_at":1760003600000}
                """.trimIndent(),
            )
        }
        val page = repo.photos(PhotoFilters(kind = PhotoKind.Group), limit = 60).unwrap()

        assertTrue(page.hasMore)
        assertEquals("cursor-9", page.next)
        assertEquals(1_760_003_600_000L, page.linksExpireAt)

        val first = page.photos.first()
        assertEquals(PhotoStatus.Done, first.status)
        assertEquals(PublicState.Pending, first.publicState)
        assertTrue(first.hasApprovals)
        assertEquals(listOf("Anna", "Ben"), first.names)
        assertEquals(1, first.moreNames)
        assertEquals(2, first.unnamed)
        assertEquals(4, first.rev)

        assertEquals(PhotoStatus.Failed, page.photos[1].status)
        assertEquals("too_large", page.photos[1].errorCode)

        // The filters ride the query, with the paging limit beside them.
        assertEquals("true", last.query["group"])
        assertEquals("60", last.query["limit"])
    }

    @Test
    fun `photos asked for by id ride the ids parameter, comma separated`() = runTest {
        val repo = repository { ok("""{"photos":[]}""") }
        repo.photos(PhotoFilters(), limit = 200, ids = listOf("a", "b"))
        assertEquals("a%2Cb", last.query["ids"])
    }

    @Test
    fun `a single photo carries its faces, their boxes and the approval rows`() = runTest {
        val repo = repository {
            ok(
                """
                {"photo":{
                  "id":"p1","status":"done","width":6000,"height":4000,
                  "preview_url":"https://s/preview","original_name":"A001.jpg",
                  "shoot_label":"day-12","created":1760000000000,"uploaded_by":"u3",
                  "public_state":"pending","people":2,"unnamed":1,"truncated":true,"section":"2",
                  "faces":[
                    {"id":"f1","member_id":"m1","name":"Anna Bell","state":"matched","similarity":91.5,
                     "box":{"l":0.1,"t":0.2,"w":0.3,"h":0.4},"crop_url":"https://s/f1",
                     "candidates":[{"member_id":"m2","name":"Ada","similarity":72}],"has_vector":true},
                    {"id":"f2","state":"suggested","member_id":"m3","name":"Ben","similarity":68},
                    {"id":"f3","manual":true,"member_id":"m4","name":"Cal","state":"tagged"}],
                  "approvals":[
                    {"member_id":"m1","name":"Anna Bell","state":"pending","can_decide":true,"agent_user_id":"u9"},
                    {"member_id":"m4","name":"Cal","state":"rejected","note":"eyes closed","has_agent":false}]},
                 "links_expire_at":1760003600000}
                """.trimIndent(),
            )
        }
        val answer = repo.photo("p1").unwrap()
        val photo = answer.photo

        assertEquals(6000, photo.width)
        assertEquals("day-12", photo.shootLabel)
        assertEquals("u3", photo.uploadedBy)
        assertTrue(photo.truncated)
        assertEquals("2", photo.section)
        assertEquals(1_760_003_600_000L, answer.linksExpireAt)

        val matched = photo.faces[0]
        assertEquals(FaceState.Matched, matched.state)
        assertEquals(91.5f, matched.similarity)
        assertEquals(0.1f, matched.box?.left)
        assertEquals(0.4f, matched.box?.height)
        assertTrue(matched.hasVector)
        assertTrue(matched.isNamed)
        assertEquals("Ada", matched.candidates.single().name)

        // A proposal is NOT a name: the photo is held until a person says so.
        assertFalse(photo.faces[1].isNamed)
        // A person added by hand has no box to point at.
        assertTrue(photo.faces[2].manual)
        assertNull(photo.faces[2].box)

        val mine = photo.approvals[0]
        assertTrue(mine.canDecide)
        assertEquals("u9", mine.agentUserId)
        // An absent `has_agent` means they have one.
        assertTrue(mine.hasAgent)

        val theirs = photo.approvals[1]
        assertEquals(Decision.Rejected, theirs.state)
        assertEquals("eyes closed", theirs.note)
        assertFalse(theirs.canDecide)
        assertFalse(theirs.hasAgent)
    }

    @Test
    fun `a summary counts the sections, the gaps and the shoots`() = runTest {
        val repo = repository {
            ok(
                """
                {"total":90,"by_section":{"1":10,"2":20},"needs_names":4,"no_people":1,
                 "failed":2,"processing":3,"approved":40,"pending":30,"blocked":20,
                 "members":[{"member_id":"m1","name":"Anna","photos":12}],
                 "shoots":["day-11","day-12"]}
                """.trimIndent(),
            )
        }
        val summary = repo.summary(shoot = "  day-12 ").unwrap()
        assertEquals(90, summary.total)
        assertEquals(20, summary.bySection["2"])
        assertEquals(40, summary.forState(PublicState.Approved))
        assertEquals(20, summary.forState(PublicState.Blocked))
        assertEquals(12, summary.members?.single()?.photos)
        assertEquals(listOf("day-11", "day-12"), summary.shoots)
        assertEquals("day-12", last.query["shoot"])
    }

    // -- faces -------------------------------------------------------------------------

    @Test
    fun `a face edit sends exactly one of the three things it can say`() = runTest {
        val repo = repository { ok("""{"photo":{"id":"p1"}}""") }

        repo.setFace("p1", "f1", FaceEdit.Name("m1"))
        assertEquals("""{"member_id":"m1"}""", last.raw)

        repo.setFace("p1", "f1", FaceEdit.NotCast)
        assertEquals("""{"dismiss":true}""", last.raw)

        repo.setFace("p1", "f1", FaceEdit.Clear)
        assertEquals("""{"clear":true}""", last.raw)
    }

    @Test
    fun `find-similar answers a face per photo, with its match`() = runTest {
        val repo = repository {
            ok("""{"items":[{"photo_id":"p2","face_id":"f7","similarity":88.25,"crop_url":"https://s/c"}]}""")
        }
        val item = repo.findSimilar("p1", "f1").unwrap().single()
        assertEquals("p2:f7", item.key)
        assertEquals(88.25f, item.similarity)
    }

    @Test
    fun `naming a set of faces says member_id, and setting them aside says dismiss`() = runTest {
        val repo = repository { ok("""{"results":[{"photo_id":"p1","face_id":"f1","ok":true}]}""") }

        val named = repo.applyToFaces(listOf("p1" to "f1"), memberId = "m1").unwrap()
        assertTrue(named.value.single().ok)
        assertTrue(last.raw.orEmpty().contains(""""member_id":"m1""""))

        repo.applyToFaces(listOf("p1" to "f1"), memberId = null)
        assertTrue(last.raw.orEmpty().contains(""""dismiss":true"""))
    }

    // -- approvals ---------------------------------------------------------------------

    @Test
    fun `the queue answers its tabs' counts, the reader's rows and the others'`() = runTest {
        val repo = repository {
            ok(
                """
                {"photos":[
                  {"id":"p1","thumb_url":"https://s/p1","people":3,"section":"3","discard_is_free":true,
                   "my_rows":[{"member_id":"m1","name":"Anna","state":"pending","can_decide":true}],
                   "others":[{"member_id":"m2","name":"Ben","state":"rejected"}]}],
                 "counts":{"pending":4,"approved":9,"rejected":2,"all":15},
                 "clients":[{"member_id":"m1","name":"Anna","allowance":{"3":{"limit":1,"used":1,"remaining":0}}}],
                 "has_more":false}
                """.trimIndent(),
            )
        }
        val page = repo.review(ReviewTab.Pending, member = "m1", limit = 60).unwrap()

        assertEquals(4, page.counts?.pending)
        assertEquals(15, page.counts?.all)
        assertEquals(0, page.clients?.single()?.allowance?.get("3")?.remaining)

        val photo = page.photos.single()
        assertTrue(photo.discardIsFree)
        assertEquals("3", photo.section)
        assertTrue(photo.myRows.single().canDecide)
        assertEquals(Decision.Rejected, photo.others.single().state)

        assertEquals("pending", last.query["tab"])
        assertEquals("m1", last.query["member"])
    }

    @Test
    fun `a decision answers the photo as it now stands and the fresh allowances`() = runTest {
        val repo = repository {
            ok(
                """
                {"photo":{"id":"p1","status":"done","public_state":"blocked",
                  "approvals":[{"member_id":"m1","state":"rejected","can_decide":true}]},
                 "allowances":[{"member_id":"m1","name":"Anna",
                                "allowance":{"3":{"limit":2,"used":2,"remaining":0}}}]}
                """.trimIndent(),
                message = "still_kills_decision_saved",
            )
        }
        val wrote = repo.decide("p1", "m1", Decision.Rejected, note = "eyes closed").unwrap()

        // The service's own words carry the toast: this app authors none.
        assertEquals("still_kills_decision_saved", wrote.message)
        assertEquals(PublicState.Blocked, wrote.value.photo?.publicState)
        assertEquals(0, wrote.value.allowances?.single()?.allowance?.get("3")?.remaining)
        assertEquals(HttpMethod.Put, last.method)
        val body = last.raw.orEmpty()
        assertTrue(body.contains(""""state":"rejected""""))
        assertTrue(body.contains(""""note":"eyes closed""""))
    }

    @Test
    fun `a note is left out when there is none, as the web leaves it out`() = runTest {
        val repo = repository { ok("""{"photo":null}""") }
        repo.decide("p1", "m1", Decision.Approved)
        assertFalse(last.raw.orEmpty().contains("note"))
    }

    @Test
    fun `a photo the decision took out of sight comes back as a present null`() = runTest {
        val repo = repository { ok("""{"photo":null}""") }
        assertNull(repo.decide("p1", "m1", Decision.Approved).unwrap().value.photo)
    }

    // -- uploads -----------------------------------------------------------------------

    @Test
    fun `presigned uploads answer each file on its own`() = runTest {
        val repo = repository {
            ok(
                """
                {"items":[
                  {"unique_id":"u-1","photo_id":"p1","url":"https://store/1","headers":{"Content-Type":"image/jpeg"}},
                  {"unique_id":"u-2","refused":"still_kills_file_type_unsupported"},
                  {"unique_id":"u-3","duplicate_of":"p9"},
                  {"unique_id":"u-4","photo_id":"p4","uploaded":true}]}
                """.trimIndent(),
            )
        }
        val links = repo.presignUploads(
            files = listOf(
                com.zillit.desktop.feature.selectstills.domain.UploadDeclaration(
                    uniqueId = "u-1",
                    name = "A001.jpg",
                    type = "image/jpeg",
                    size = 42,
                    fingerprint = "abc.42",
                ),
            ),
            shootLabel = "day-12",
            batchId = "b1",
            batchOffset = 0,
            allowDuplicates = false,
            projectId = "proj-1",
        ).unwrap()

        assertEquals("https://store/1", links[0].url)
        assertEquals("image/jpeg", links[0].headers["Content-Type"])
        assertEquals("still_kills_file_type_unsupported", links[1].refused)
        assertEquals("p9", links[2].duplicateOf)
        assertTrue(links[3].uploaded)

        val body = last.raw.orEmpty()
        assertTrue(body.contains(""""shoot_label":"day-12""""))
        assertTrue(body.contains(""""batch_id":"b1""""))
        assertTrue(body.contains(""""fingerprint":"abc.42""""))
        assertTrue(body.contains(""""allow_duplicates":false"""))
    }

    @Test
    fun `a file with no fingerprint simply does not declare one`() = runTest {
        val repo = repository { ok("""{"items":[]}""") }
        repo.presignUploads(
            files = listOf(
                com.zillit.desktop.feature.selectstills.domain.UploadDeclaration("u-1", "a.jpg", "image/jpeg", 1),
            ),
            shootLabel = "",
            batchId = "b",
            batchOffset = 0,
            allowDuplicates = false,
            projectId = null,
        )
        assertFalse(last.raw.orEmpty().contains("fingerprint"))
    }

    @Test
    fun `confirming an upload answers what arrived and what did not`() = runTest {
        val repo = repository { ok("""{"accepted":["p1","p2"],"missing":["p3"]}""") }
        val confirmed = repo.completeUploads(listOf("p1", "p2", "p3"), projectId = "proj-1").unwrap()
        assertEquals(setOf("p1", "p2"), confirmed.accepted)
        assertEquals(setOf("p3"), confirmed.missing)
    }

    // -- settings ----------------------------------------------------------------------

    @Test
    fun `settings send only the slices that changed`() = runTest {
        val repo = repository { ok("""{"viewer_scope":"all"}""") }
        repo.updateSettings(viewerScope = ViewerScope.All)
        assertEquals("""{"viewer_scope":"all"}""", last.raw)

        repo.updateSettings(
            thresholds = com.zillit.desktop.feature.selectstills.domain.Thresholds(auto = 80, suggest = 60, margin = 5),
        )
        assertEquals("""{"thresholds":{"auto":80,"suggest":60,"margin":5}}""", last.raw)
    }

    // -- the envelope rule -------------------------------------------------------------

    @Test
    fun `a 200 that says status 0 is a refusal, and its key is what the toast shows`() = runTest {
        val repo = repository { """{"status":0,"message":"still_kills_allowance_spent","data":{}}""" }
        val error = (repo.decide("p1", "m1", Decision.Rejected) as ZillitResult.Failure).error as ZillitError.Http
        assertEquals("still_kills_allowance_spent", error.serverMessage)
    }

    /** `AppConfig.apiV2` throws for a missing service, and a throw in a view model's coroutine ends the app. */
    @Test
    fun `an environment without the service fails instead of throwing`() = runTest {
        val repo = repository(services = mapOf(ZillitService.Core to "https://core.test")) { ok("{}") }

        assertIs<ZillitResult.Failure>(repo.me())
        assertIs<ZillitResult.Failure>(repo.photos(PhotoFilters(), limit = 60))
        assertIs<ZillitResult.Failure>(repo.decide("p1", "m1", Decision.Approved))
        assertTrue(sent.isEmpty())
    }
}

private class StillsMockEngineFactory(private val engine: MockEngine) : HttpClientEngineFactory<MockEngineConfig> {
    override fun create(block: MockEngineConfig.() -> Unit): HttpClientEngine = engine
}
