package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.data.readNotice
import com.zillit.desktop.feature.home.data.readReadBy
import com.zillit.desktop.feature.home.data.toDto
import com.zillit.desktop.feature.home.data.toForwardDto
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.NoticeKind
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.domain.parseGeoInput
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading one post off the notice feed.
 *
 * Typed decoding failed against QA on an HTTP 200. A board carries posts
 * written by several client versions over the life of a production, so a
 * field's type is a hope — and one odd row must cost that row, not the board.
 */
class NoticeReaderTest {

    private fun read(json: String) = readNotice(Json.parseToJsonElement(json)) { "decrypted:$it" }

    @Test
    fun `a well-formed row reads`() {
        val notice = read(
            """{"_id":"n1","message":"CIPHER","name":"Aisha","sender":"u1",
               "created":1700000000000,"edited":true,"pinned":0}""",
        )!!

        assertEquals("n1", notice.id)
        assertEquals("decrypted:CIPHER", notice.body)
        assertEquals("Aisha", notice.authorName)
        assertEquals(1_700_000_000_000, notice.createdAtMillis)
        assertTrue(notice.isEdited)
        assertTrue(!notice.isPinned)
    }

    @Test
    fun `a timestamp sent as a string still reads`() {
        // Older clients wrote it quoted.
        assertEquals(1_700_000_000_000, read("""{"_id":"n1","created":"1700000000000"}""")!!.createdAtMillis)
    }

    @Test
    fun `an unparseable timestamp degrades to zero rather than dropping the post`() {
        // A post with a bad date is still a post someone needs to read.
        val notice = read("""{"_id":"n1","created":"yesterday"}""")!!

        assertEquals(0, notice.createdAtMillis)
    }

    @Test
    fun `pinned is a timestamp, not a flag`() {
        assertTrue(read("""{"_id":"n1","pinned":1700000000000}""")!!.isPinned)
        assertTrue(!read("""{"_id":"n1","pinned":0}""")!!.isPinned)
        assertTrue(!read("""{"_id":"n1"}""")!!.isPinned)
    }

    @Test
    fun `deleted posts are dropped`() {
        // The server still returns them so clients can purge a local cache.
        assertNull(read("""{"_id":"n1","deleted":true}"""))
    }

    @Test
    fun `a row with no id is dropped`() {
        assertNull(read("""{"message":"CIPHER"}"""))
        assertNull(read("""{"_id":"  "}"""))
    }

    @Test
    fun `a row that is not an object is dropped, not fatal`() {
        assertNull(read("""["not","an","object"]"""))
        assertNull(read("""null"""))
    }

    @Test
    fun `the reply count counts readable replies, not array junk`() {
        // Comments are parsed rows now, not a raw array length — three
        // primitives are zero replies, and a null array is zero, not a crash.
        assertEquals(0, read("""{"_id":"n1","comments":[1,2,3]}""")!!.commentCount)
        assertEquals(0, read("""{"_id":"n1","comments":null}""")!!.commentCount)
        assertEquals(1, read("""{"_id":"n1","comments":[{"_id":"c1"}]}""")!!.commentCount)
    }

    // -- attachments -------------------------------------------------------
    //
    // The wire's `attachment` is a singular object. The first reader counted a
    // plural `attachments` array — a field this feed never carried — which is
    // why every post with media rendered as bare text.

    @Test
    fun `an image post carries its attachment`() {
        val notice = read(
            """{"_id":"n1","message_type":"image","attachment":{
                "media":"chat/abc.jpg","name":"set.jpg","thumbnail":"chat/abc_thumb.jpg",
                "content_type":"image/jpeg","content_subtype":"jpg",
                "bucket":"zillit-us","region":"us-east-1","file_size":204800,
                "width":1200,"height":800}}""",
        )!!

        assertEquals(NoticeKind.Image, notice.kind)
        val attachment = notice.attachment!!
        assertEquals("chat/abc.jpg", attachment.media)
        assertEquals("chat/abc_thumb.jpg", attachment.previewKey)
        assertEquals("set.jpg", attachment.fileName)
        assertEquals(204800L, attachment.sizeBytes)
        assertTrue(attachment.isFetchable)
    }

    @Test
    fun `no thumbnail means the preview is the media itself`() {
        val notice = read(
            """{"_id":"n1","message_type":"image","attachment":{
                "media":"chat/abc.jpg","bucket":"b","region":"r"}}""",
        )!!

        assertEquals("chat/abc.jpg", notice.attachment!!.previewKey)
    }

    @Test
    fun `an attachment without storage is kept but marked unfetchable`() {
        // The post still shows — its caption and file name are information —
        // but nothing tries to ask S3 for a location it does not have.
        val notice = read(
            """{"_id":"n1","message_type":"document","attachment":{"media":"k","name":"call.pdf"}}""",
        )!!

        assertTrue(notice.attachment!!.isFetchable.not())
    }

    @Test
    fun `an unknown message type falls back to text`() {
        // A newer client's post degrades to its caption; it must not vanish.
        assertEquals(NoticeKind.Text, read("""{"_id":"n1","message_type":"hologram"}""")!!.kind)
        assertEquals(NoticeKind.Text, read("""{"_id":"n1"}""")!!.kind)
    }

    @Test
    fun `an attachment with no media key is no attachment`() {
        assertEquals(null, read("""{"_id":"n1","attachment":{"name":"x.pdf"}}""")!!.attachment)
    }

    @Test
    fun `a missing author stays blank for the screen to hide, never "Unknown"`() {
        // System rows — project invites — name nobody. The screen drops a
        // blank author line; a made-up "Unknown" would render on every one.
        assertEquals("", read("""{"_id":"n1"}""")!!.authorName)
        assertEquals("", read("""{"_id":"n1","name":"  "}""")!!.authorName)
    }

    @Test
    fun `an empty body decrypts to empty rather than being sent to the engine`() {
        assertEquals("decrypted:", read("""{"_id":"n1"}""")!!.body)
    }

    // -- locations ---------------------------------------------------------

    @Test
    fun `a location post carries its point, however the numbers arrived`() {
        // Numbers from one client, quoted strings from another.
        val fromNumbers = read(
            """{"_id":"n1","message_type":"location","location":{"lat":34.05,"long":-118.24}}""",
        )!!
        assertEquals(34.05, fromNumbers.location?.lat)
        assertEquals(-118.24, fromNumbers.location?.long)

        val fromStrings = read(
            """{"_id":"n1","message_type":"location","location":{"lat":"34.05","long":"-118.24"}}""",
        )!!
        assertEquals(34.05, fromStrings.location?.lat)
    }

    @Test
    fun `the maps link is the web's link`() {
        val notice = read(
            """{"_id":"n1","location":{"lat":34.05,"long":-118.24}}""",
        )!!

        assertEquals("https://www.google.com/maps?q=34.05,-118.24", notice.location?.mapsUrl)
    }

    @Test
    fun `half a coordinate is no coordinate`() {
        assertEquals(null, read("""{"_id":"n1","location":{"lat":34.05}}""")!!.location)
        assertEquals(null, read("""{"_id":"n1","location":{"long":"x"}}""")!!.location)
        assertEquals(null, read("""{"_id":"n1","location":"nowhere"}""")!!.location)
    }

    // -- what a desktop user can paste ------------------------------------

    @Test
    fun `plain coordinates parse, comma or space`() {
        assertEquals(GeoPoint(34.05, -118.24), parseGeoInput("34.05, -118.24"))
        assertEquals(GeoPoint(34.05, -118.24), parseGeoInput("34.05 -118.24"))
        assertEquals(GeoPoint(-33.9, 151.2), parseGeoInput(" -33.9,151.2 "))
    }

    @Test
    fun `a copied Maps link parses`() {
        assertEquals(
            GeoPoint(34.05, -118.24),
            parseGeoInput("https://www.google.com/maps?q=34.05,-118.24"),
        )
        assertEquals(
            GeoPoint(34.05, -118.24),
            parseGeoInput("https://www.google.com/maps/place/x/@34.05,-118.24,15z"),
        )
        assertEquals(
            GeoPoint(34.05, -118.24),
            parseGeoInput("https://maps.google.com/?q=34.05%2C-118.24"),
        )
    }

    @Test
    fun `garbage and off-globe points refuse to parse`() {
        // A guessed point sends the crew to the wrong place with confidence.
        assertEquals(null, parseGeoInput(""))
        assertEquals(null, parseGeoInput("the car park"))
        assertEquals(null, parseGeoInput("91.0, 10.0"))
        assertEquals(null, parseGeoInput("10.0, 181.0"))
        assertEquals(null, parseGeoInput("https://example.com/nothing/here"))
    }

    @Test
    fun `the wire's content_type is the category word, never the MIME type`() {
        // The server validates content_type against {image, video, audio,
        // document} and rejects a MIME type with
        // unit_chat_attachment_content_type_required — found live on dev.
        // Web sends `fileType`, iOS sends `assetType.rawValue`; the MIME type
        // belongs on the S3 PUT header, not in the chat payload.
        val dto = UploadedNoticeMedia(
            kind = NoticeKind.Image,
            media = "key/photo",
            bucket = "b",
            region = "r",
            fileName = "Set Photo.PNG",
            contentType = "image/png",
            sizeBytes = 1_024,
        ).toDto()

        assertEquals("image", dto.contentType)
        assertEquals("png", dto.contentSubtype)
        // file_size travels as a string — web stringifies, iOS declares
        // String; a JSON number fails as unit_chat_file_size_required.
        assertEquals("1024", dto.fileSize)
    }

    @Test
    fun `the thumbnail key is never absent from the payload`() {
        // The server rejects a missing key with
        // unit_chat_attachment_thumbmnail_required (sic) — also found live.
        // The web always sends one: the uploaded thumb when there is one, the
        // media key itself for image and audio, "" for documents.
        fun uploaded(kind: NoticeKind, thumbnail: String? = null) = UploadedNoticeMedia(
            kind = kind,
            media = "key/original",
            bucket = "b",
            region = "r",
            fileName = "f.bin",
            contentType = "x/y",
            sizeBytes = 0,
            thumbnail = thumbnail,
        )

        assertEquals("key/thumb", uploaded(NoticeKind.Video, "key/thumb").toDto().thumbnail)
        assertEquals("key/original", uploaded(NoticeKind.Image).toDto().thumbnail)
        assertEquals("key/original", uploaded(NoticeKind.Audio).toDto().thumbnail)
        assertEquals("", uploaded(NoticeKind.Document).toDto().thumbnail)

        // An unknown size still travels — the web's own literal fallback.
        assertEquals("1000000", uploaded(NoticeKind.Image).toDto().fileSize)
    }

    @Test
    fun `a forwarded attachment keeps its keys but re-derives its description`() {
        // Rows written by earlier builds hold a MIME type in content_type;
        // copying that forward would replay the very rejection this client
        // fixed. The keys, though, are the point of a forward — never touched.
        val stored = NoticeAttachment(
            media = "chat/set.jpg",
            fileName = "set.jpg",
            contentType = "image/jpeg",
            bucket = "b",
            region = "r",
        )

        val dto = stored.toForwardDto(NoticeKind.Image)
        assertEquals("chat/set.jpg", dto.media)
        assertEquals("image", dto.contentType)
        assertEquals("jpg", dto.contentSubtype)
        assertEquals("chat/set.jpg", dto.thumbnail)
        assertEquals("1000000", dto.fileSize)

        // A location post's map image is typed "image" — iOS's rule.
        assertEquals("image", stored.toForwardDto(NoticeKind.Location).contentType)
    }

    @Test
    fun `read receipts read tolerantly, ids required and everything else optional`() {
        val readBy = readReadBy(
            Json.parseToJsonElement(
                """{
                   "message_read_by":[
                     {"userId":"u1","user_name":"Aisha","designation_name":"gaffer_label",
                      "read_time":1700000000000},
                     {"userId":"u2","read_time":"1700000001"},
                     {"no_id_at_all":true}
                   ],
                   "message_unread_by":[{"userId":"u3"}]
                }""",
            ),
        )

        assertEquals(listOf("u1", "u2"), readBy.read.map { it.userId })
        assertEquals("Aisha", readBy.read[0].userName)
        assertEquals(1_700_000_000_000, readBy.read[0].readTimeMillis)
        // A seconds timestamp (and a string one) still lands in millis.
        assertEquals(1_700_000_001_000, readBy.read[1].readTimeMillis)
        assertEquals(listOf("u3"), readBy.unread.map { it.userId })
        assertEquals(0, readBy.unread[0].readTimeMillis)
    }
}
