package com.zillit.desktop.feature.sos

import com.zillit.desktop.core.common.MessageElement
import com.zillit.desktop.feature.sos.data.AlertDto
import com.zillit.desktop.feature.sos.data.AlertReferenceDto
import com.zillit.desktop.feature.sos.data.ContactDto
import com.zillit.desktop.feature.sos.data.ContactUserDto
import com.zillit.desktop.feature.sos.data.IsdCodeDto
import com.zillit.desktop.feature.sos.data.RelationDto
import com.zillit.desktop.feature.sos.data.SosEndpoints
import com.zillit.desktop.feature.sos.data.externalBody
import com.zillit.desktop.feature.sos.data.internalBody
import com.zillit.desktop.feature.sos.data.toAlert
import com.zillit.desktop.feature.sos.data.toContact
import com.zillit.desktop.feature.sos.data.toIsdCode
import com.zillit.desktop.feature.sos.data.toRelation
import com.zillit.desktop.feature.sos.domain.ExternalContactDraft
import com.zillit.desktop.feature.sos.domain.SosContactKind
import com.zillit.desktop.feature.sos.domain.SosTextDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every URL and every body, pinned against the web and Android sources they were read from. */
class SosWireTest {

    private val project = "https://api.example.com/api/v2/"
    private val notification = "https://notify.example.com/api/v2/"
    private val decoder = SosTextDecoder(translate = { key -> key })

    @Test
    fun `the feed and its two directions sit on the notification host`() {
        assertEquals(
            "${notification}project/notifications/1700/previous",
            SosEndpoints.alerts(notification, 1700, older = true),
        )
        assertEquals(
            "${notification}project/notifications/1700/next",
            SosEndpoints.alerts(notification, 1700, older = false),
        )
        assertEquals("${notification}levelmarkread/sos_label/1700", SosEndpoints.markRead(notification, 1700))
        assertEquals("${notification}markdelete/abc/1700", SosEndpoints.deleteAlert(notification, "abc", 1700))
        assertEquals("${notification}markdelete/sos_label/1700", SosEndpoints.deleteAllAlerts(notification, 1700))
    }

    @Test
    fun `the alarm and the receiver routes sit on the project host`() {
        assertEquals("${project}sos/send/alert", SosEndpoints.sendAlert(project))
        assertEquals("${project}sos/contacts", SosEndpoints.contacts(project))
        assertEquals("${project}sos/contact/create", SosEndpoints.createContact(project))
        assertEquals("${project}sos/contact/update/c1", SosEndpoints.updateContact(project, "c1"))
        assertEquals("${project}sos/contact/c1", SosEndpoints.contact(project, "c1"))
        assertEquals("${project}sos/departments", SosEndpoints.relations(project))
        assertEquals("${project}preset/isd-codes", SosEndpoints.isdCodes(project))
        assertEquals("${project}sos/notification/abc", SosEndpoints.alertFallback(project, "abc"))
        assertEquals("${project}sos/notification", SosEndpoints.allAlertsFallback(project))
    }

    @Test
    fun `the contact bodies carry exactly the keys the other clients post`() {
        assertEquals("""{"user_id":"u-sam"}""", Json.encodeToString(internalBody("u-sam")))
        val draft = ExternalContactDraft(
            contactName = "Jo Blake",
            relation = "Family",
            countryCode = "+44",
            phoneNumber = "7700900000",
        )
        assertEquals(
            """{"contact_name":"Jo Blake","relation":"Family","country_code":"+44","phone_number":"7700900000"}""",
            Json.encodeToString(externalBody(draft)),
        )
    }

    @Test
    fun `an alert row reads its sender, stamp, tombstone and named map link`() {
        val row = AlertDto(
            id = "a1",
            uuid = "u1",
            sender = "u-sam",
            message = "{{name}} raised an SOS",
            action = "sos_alert_entertainment",
            created = 400,
            updated = 500,
            deleted = 0,
            referenceData = AlertReferenceDto(
                messageElements = listOf(
                    MessageElement(search = "{{name}}", replacer = "Sam Carter"),
                    MessageElement(search = "{{location}}", replacer = "https://maps.google.com/?q=1,2"),
                ),
                contactInfo = "+44 7700900000",
            ),
        )
        val alert = row.toAlert(decoder)
        checkNotNull(alert)
        assertEquals("Sam Carter raised an SOS", alert.text)
        assertEquals("Sam Carter", alert.senderNameHint)
        assertEquals("https://maps.google.com/?q=1,2", alert.mapsUrl)
        assertEquals("+44 7700900000", alert.contactInfo)
        assertEquals(500L, alert.updatedMillis)
        assertTrue(alert.isEntertainment)
        assertTrue(!alert.deleted)
    }

    @Test
    fun `a positional map link is accepted, a non-http one is not, and a stamped delete is a tombstone`() {
        val positional = AlertDto(
            id = "a2",
            deleted = 1_700_000_000_000,
            referenceData = AlertReferenceDto(
                messageElements = listOf(
                    MessageElement(search = "{{name}}", replacer = "Sam"),
                    MessageElement(search = "{{link}}", replacer = "https://maps.example/x"),
                ),
            ),
        )
        val alert = checkNotNull(positional.toAlert(decoder))
        assertEquals("https://maps.example/x", alert.mapsUrl)
        assertTrue(alert.deleted)

        val junk = AlertDto(
            id = "a3",
            referenceData = AlertReferenceDto(
                messageElements = listOf(
                    MessageElement(search = "{{name}}", replacer = "Sam"),
                    MessageElement(search = "{{count}}", replacer = "3"),
                ),
            ),
        )
        assertEquals("", checkNotNull(junk.toAlert(decoder)).mapsUrl)
        assertNull(AlertDto(id = "").toAlert(decoder))
    }

    @Test
    fun `a contact row reads both kinds, and a relation id arrives as a number`() {
        val internal = ContactDto(
            id = "c1",
            type = "internal",
            entryType = "user",
            userId = "u-sam",
            user = ContactUserDto(fullName = "Sam Carter", designationName = "gaffer_label"),
        ).toContact()
        checkNotNull(internal)
        assertEquals(SosContactKind.Internal, internal.kind)
        assertEquals("Sam Carter", internal.displayName)
        assertEquals("", internal.phoneLabel)

        val external = ContactDto(
            id = "c2",
            type = "external",
            contactName = "Jo Blake",
            relation = "Family",
            countryCode = "+44",
            phoneNumber = "7700900000",
        ).toContact()
        checkNotNull(external)
        assertEquals("Jo Blake", external.displayName)
        assertEquals("+44 7700900000", external.phoneLabel)

        val relation = RelationDto(id = JsonPrimitive(7), departmentName = "Family", identifier = "family").toRelation()
        assertEquals("7", checkNotNull(relation).id)
        assertNull(RelationDto(id = JsonPrimitive(8)).toRelation())
        assertEquals("+44", checkNotNull(IsdCodeDto(name = "UK", dialCode = "+44", code = "GB").toIsdCode()).dialCode)
    }
}
