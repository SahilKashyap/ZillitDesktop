package com.zillit.desktop.feature.maps.domain

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * What the map tool needs from the application around it.
 *
 * Seams rather than repository calls, for the reason every tool module gives:
 * the file picker belongs to the desktop, the object store and the crew list
 * to the production, the badge ledger to the frame — none is something the
 * map service offers. The app module implements them; tests hand in fakes;
 * a host that has none gets the defaults, which do nothing and say so.
 */
class MapHost(
    val photos: MapPhotos? = null,
    val share: MapShareHost? = null,
    val badges: MapBadges = MapBadges.None,
    val prefs: MapPrefs = MapPrefs.InMemory(),
    val staticMaps: MapStaticMaps? = null,
    val locator: MapLocator? = null,
)

/** A photo chosen on this machine, before it is stored. */
class PickedPhoto(val name: String, val contentType: String, val bytes: ByteArray) {
    /** Never prints the bytes. */
    override fun toString(): String = "PickedPhoto(name=$name, type=$contentType, size=${bytes.size})"
}

/** The location form's photos: pick, store, and show. */
interface MapPhotos {

    /**
     * The system picker, images only, several at once. Empty when cancelled.
     * [onRefused] names each file turned away before it was read.
     */
    suspend fun pick(onRefused: (String) -> Unit): List<PickedPhoto>

    /** Puts one photo in the production's store, answering its attachment record. */
    suspend fun upload(photo: PickedPhoto): ZillitResult<MapAttachment>

    /** A stored photo's bytes — the thumbnail when [preview] and one exists. */
    suspend fun read(attachment: MapAttachment, preview: Boolean): ZillitResult<ByteArray>
}

/** Someone a share can go to. */
data class SharePerson(val userId: String, val name: String, val designation: String = "")

/**
 * Where a share goes — the desktop's half of the web's "Forward In App".
 *
 * The web forwards through its full destination picker; the desktop sends the
 * same text as a direct message, the one in-app destination this client can
 * reach from outside the chat module.
 */
interface MapShareHost {
    fun people(): List<SharePerson>

    /** Sends [text] to each person; answers how many it reached. */
    suspend fun send(userIds: List<String>, text: String): ZillitResult<Int>
}

/** The map's unread badges, by city — the ledger's `map_label` rows split by unit. */
interface MapBadges {
    val cityUnread: Flow<Map<String, Int>>

    /** `notification:read` with `module: map_label, segment: cityId`. */
    fun markCityRead(cityId: String)

    object None : MapBadges {
        override val cityUnread: Flow<Map<String, Int>> = emptyFlow()
        override fun markCityRead(cityId: String) = Unit
    }
}

/**
 * What the map remembers between visits.
 *
 * The last city (reqs G + H) and each city's chosen zone (req F) survive
 * restarts — the web keeps both in `localStorage`. The guide's dismissal is
 * per session and lives in the view model, as the web keeps it in
 * `sessionStorage`.
 */
interface MapPrefs {
    suspend fun lastVisitedCityId(): String?
    suspend fun setLastVisitedCityId(cityId: String)
    suspend fun activeZoneId(cityId: String): String?
    suspend fun setActiveZoneId(cityId: String, zoneId: String)

    /** For tests and hosts without storage: remembered until the process ends. */
    class InMemory : MapPrefs {
        private var lastCity: String? = null
        private val zones = mutableMapOf<String, String>()

        override suspend fun lastVisitedCityId(): String? = lastCity
        override suspend fun setLastVisitedCityId(cityId: String) {
            lastCity = cityId
        }
        override suspend fun activeZoneId(cityId: String): String? = zones[cityId]
        override suspend fun setActiveZoneId(cityId: String, zoneId: String) {
            zones[cityId] = zoneId
        }
    }
}

/**
 * Where this machine is, when the browser cannot say.
 *
 * The web asks `navigator.geolocation`; embedded Chromium here has no way to
 * grant that prompt, so the page's answer is always a refusal. This is the
 * second try — an approximate fix (Google's Geolocation API from the network
 * address is the desktop's), good enough to name the city someone is in.
 */
interface MapLocator {
    suspend fun locate(): LatLng?
}

/**
 * A still picture of a place — the zone previews the web draws with a small
 * live map. A live map inside a side panel would be a second browser; a
 * picture says the same thing.
 */
interface MapStaticMaps {
    suspend fun zone(centre: LatLng, radiusMiles: Double): ByteArray?
}
