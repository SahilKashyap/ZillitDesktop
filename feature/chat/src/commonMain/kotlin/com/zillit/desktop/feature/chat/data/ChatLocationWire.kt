package com.zillit.desktop.feature.chat.data

import com.zillit.desktop.feature.chat.domain.ChatLocation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The `location` half of the CNC wire, kept beside `ChatWire` rather than in
 * it: one short file that holds every fact about how a shared place travels.
 *
 * The place rides the envelope TOP LEVEL, a sibling of `attachment` — Android
 * `ChatAndGroupModel.location: LocationInfo?`
 * (`chatAndGroupChat/model/ChatAndGroupRequestModelHandler.kt:42`, set from
 * `postDataInChat`'s `location` parameter, `baseUtils/CommonApis.kt:1878,1889`),
 * web `pages/cnc_latest/cncUtil.js:312-315`. It is NEVER inside `attachment`:
 * on a phone-sent location that key holds the map screenshot the sender
 * uploaded (`mapView/MapsActivity.kt:205-224`), which is a different thing.
 *
 * The write half lives in `sendEnvelope` (ChatWire.kt), where every other
 * outgoing field is spelled.
 */

/**
 * The `message_type` word for a shared place — Android's `Constants.LOCATION`
 * (`utils/Constants.kt:713`), reached through
 * `"".messageTypeOrContentTypeProvider(isLocation = true)`
 * (`mediaHandler/imageeditor/utils/Extension.kt:602-603`); the web's
 * `CNC_MESSAGE_TYPES.LOCATION` (`pages/cnc_latest/constants/CncConstants.js:7`).
 */
const val LOCATION_KIND = "location"

/**
 * The place a message shares, in every shape the wire produces:
 *  - the phones', `{lat, long, address, imageLink, height, width}`
 *    (`bottomNav/home/models/HomeChatRequest.kt:228-239`);
 *  - the web's, `{lat, long}` alone with no address (`cncUtil.js:312-315`);
 *  - this desktop's own echo, which is the phones' three place fields.
 *
 * `lng` is tolerated beside `long` only as insurance — the wire says `long`,
 * and every client that has ever written this key wrote `long`.
 *
 * Null unless there is a real pin, which is Android's own precondition for
 * drawing one (`chatAndGroupChat/ChatAndGroupPage.kt:2598`: lat and long both
 * present and neither 0.0). It earns its keep here because the web stamps an
 * EMPTY `location: {}` onto every reply payload (`cncUtil.js:380`) — without
 * the guard, every quoted text reply would arrive as a map card.
 */
internal fun readLocation(message: JsonObject): ChatLocation? {
    val node = message["location"] as? JsonObject ?: return null
    val lat = node.coordinate("lat") ?: return null
    val lng = node.coordinate("long") ?: node.coordinate("lng") ?: return null
    return ChatLocation(
        address = (node["address"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }.orEmpty(),
        lat = lat,
        lng = lng,
        // The null-island guard, applied where the value is built so it cannot
        // be forgotten by a later edit: 0/0 is what an empty object decodes to
        // in the clients that fill defaults, and it is not a place.
    ).takeIf { lat != 0.0 || lng != 0.0 }
}

/**
 * A coordinate, however the sender wrote it. Rows carry the pair as JSON
 * numbers and — where a client round-tripped its own `LocationInfo` through a
 * store that stringifies — as quoted strings. A pin that reads as null is a
 * bubble that draws as nothing.
 */
private fun JsonObject.coordinate(key: String): Double? =
    (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
