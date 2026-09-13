package com.zillit.desktop.feature.maps

import com.zillit.desktop.feature.maps.domain.CenterPointType
import com.zillit.desktop.feature.maps.domain.IntersectionStreets
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.LocationType
import com.zillit.desktop.feature.maps.domain.MapAttachment
import com.zillit.desktop.feature.maps.domain.MapCity
import com.zillit.desktop.feature.maps.domain.MapLocation
import com.zillit.desktop.feature.maps.domain.MapViewer
import com.zillit.desktop.feature.maps.ui.MapUiState

/** A production's map with enough on it to fill every panel. */
internal object MapFixtures {

    val mumbai = MapCity(
        id = "mumbai",
        name = "Mumbai",
        description = "Maharashtra, India",
        coordinates = LatLng(19.076, 72.8777),
        radiusMiles = 30.0,
        locationCount = 4,
        hasLocations = true,
    )
    val pune = MapCity(id = "pune", name = "Pune", description = "Maharashtra, India", coordinates = LatLng(18.5204, 73.8567), locationCount = 2)
    val goa = MapCity(id = "goa", name = "Goa", description = "India", coordinates = LatLng(15.2993, 74.124))

    val types = listOf(
        LocationType("t1", "Hotel", icon = "🏨", subTypes = listOf("5 star", "Budget")),
        LocationType("t2", "Base Camp", icon = "⛺", subTypes = listOf("Tents", "Catering")),
        LocationType("t3", "Shooting", icon = "🎬", subTypes = listOf("Interior", "Exterior")),
        LocationType("t4", "Parking", subTypes = emptyList()),
    )

    val photo = MapAttachment(media = "map/1/lobby.jpg", bucket = "b", region = "r", name = "lobby.jpg", contentType = "image")

    val locations = listOf(
        MapLocation(
            id = "l1",
            cityId = "mumbai",
            name = "Taj Lands End",
            type = "Hotel",
            subTypes = listOf("5 star"),
            description = "Crew accommodation — 120 rooms held from 2 Oct.",
            address = "Bandstand, Bandra West, Mumbai, Maharashtra 400050",
            point = LatLng(19.0445, 72.8193),
            attachments = listOf(photo, photo.copy(media = "map/1/pool.jpg", name = "pool.jpg")),
        ),
        MapLocation(
            id = "l2",
            cityId = "mumbai",
            name = "Film City Unit Base",
            type = "Base Camp",
            subTypes = listOf("Tents", "Catering"),
            description = "Enter via Gate 2. Generators behind stage 7.",
            address = "Film City Rd, Goregaon East, Mumbai 400065",
            point = LatLng(19.1611, 72.8807),
        ),
        MapLocation(
            id = "l3",
            cityId = "mumbai",
            name = "Marine Drive Promenade",
            type = "Shooting",
            subTypes = listOf("Exterior"),
            description = "Night shoot, sc. 42–44",
            address = "Marine Dr, Churchgate, Mumbai 400020",
            sceneNumber = "42",
            point = LatLng(18.9431, 72.823),
        ),
        MapLocation(
            id = "l4",
            cityId = "mumbai",
            name = "Juhu Beach Parking",
            type = "Parking",
            address = "Juhu Tara Rd, Juhu, Mumbai 400049",
            point = LatLng(19.0988, 72.8267),
        ),
    )

    val zones = listOf(
        MapLocation(
            id = "z1",
            cityId = "mumbai",
            name = "Andheri Zone",
            address = "Andheri East, Mumbai",
            point = LatLng(19.1136, 72.8697),
            isStudioZone = true,
            miles = 30.0,
        ),
        MapLocation(
            id = "z2",
            cityId = "mumbai",
            name = "MG Road & Link Road Zone",
            address = "MG Rd & Link Rd, Goregaon West",
            point = LatLng(19.1646, 72.8493),
            isStudioZone = true,
            miles = 12.5,
            centerPointType = CenterPointType.Intersection,
            intersection = IntersectionStreets("MG Road", "Link Road"),
        ),
    )

    val viewer = MapViewer(userId = "u1", displayName = "Sahil", canView = true, canPost = true, ready = true)

    val base = MapUiState(
        viewer = viewer,
        cities = listOf(mumbai, pune, goa),
        citiesLoaded = true,
        types = types,
        selectedCityId = "mumbai",
        locations = locations,
        zones = zones,
        activeZoneId = "z1",
        cityUnread = mapOf("pune" to 3),
    )
}
