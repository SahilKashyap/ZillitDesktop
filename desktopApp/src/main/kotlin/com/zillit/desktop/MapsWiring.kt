package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.ImageBitmap
import com.zillit.desktop.core.badges.BadgeDrilldownQuery
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.datastore.PreferenceKey
import com.zillit.desktop.core.datastore.PreferenceScope
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.media.AwtAttachmentPicker
import com.zillit.desktop.core.media.PickRefusal
import com.zillit.desktop.core.media.PreviewKind
import com.zillit.desktop.core.media.contentTypeFor
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.socket.NotificationReadDto
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.feature.email.data.AwsCredentials
import com.zillit.desktop.feature.email.data.S3AttachmentUploader
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.maps.data.MapRepositoryImpl
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.MapAttachment
import com.zillit.desktop.feature.maps.domain.MapBadges
import com.zillit.desktop.feature.maps.domain.MapHost
import com.zillit.desktop.feature.maps.domain.MapPhotos
import com.zillit.desktop.feature.maps.domain.MapPrefs
import com.zillit.desktop.feature.maps.domain.MapShareHost
import com.zillit.desktop.feature.maps.domain.MapStaticMaps
import com.zillit.desktop.feature.maps.domain.PickedPhoto
import com.zillit.desktop.feature.maps.domain.SharePerson
import com.zillit.desktop.feature.maps.ui.MapToolProvider
import com.zillit.desktop.feature.maps.ui.MapViewModel
import com.zillit.desktop.feature.maps.ui.screen.MapImages
import com.zillit.desktop.feature.maps.ui.screen.PHOTO_EXTENSIONS
import com.zillit.desktop.feature.maps.ui.screen.decodeMapImage
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Map tool, built: the repository on the map host, the view model with
 * everything it asks of the application, and the tool provider with the
 * Chromium map and the pictures.
 */
internal fun AppGraph.Ready.buildMaps(permissions: () -> ProjectPermissions): MapViewModel = MapViewModel(
    repository = MapRepositoryImpl(
        apiClient,
        config,
        bus = socketEvents,
        currentProjectId = { projectContext?.context?.value?.project?.projectId },
        currentDeviceId = deviceId,
    ),
    resolveViewer = { mapViewer(permissions()) },
    canvas = mapCanvas,
    host = MapHost(
        photos = mapPhotos(),
        share = mapShare(),
        badges = mapBadges(),
        prefs = mapPrefs(),
        staticMaps = mapStaticMaps(),
    ),
    rights = rightsRequests,
)

internal fun AppGraph.Ready.mapToolProvider(viewModel: MapViewModel): MapToolProvider = MapToolProvider(
    viewModel = viewModel,
    onOpenUrl = ::openInBrowser,
    canvas = mapCanvasSurface(this),
    images = mapImages(),
)

/**
 * The Chromium map page as the Map tool's canvas slot, when the engine is the
 * real one.
 *
 * Mirrors [callVideoSurface]: a SwingPanel because JCEF renders into a
 * heavyweight AWT component, arriving asynchronously because Chromium takes
 * seconds to come up — a spinner holds the pane until it exists.
 */
internal fun mapCanvasSurface(ready: AppGraph.Ready): (@Composable (Boolean) -> Unit)? {
    val engine = ready.mapCanvas as? KcefMapEngine ?: return null
    return { visible -> MapCanvasPane(engine, visible) }
}

@Composable
private fun MapCanvasPane(engine: KcefMapEngine, visible: Boolean) {
    // Chromium starts on first use, not at app launch — the Map tool is not
    // worth seconds of every startup, and the runtime may already be up.
    LaunchedEffect(engine) { engine.open() }
    val component by engine.surface.collectAsState()
    val awtComponent = component
    Box(Modifier.fillMaxSize().background(ZillitTheme.colors.surfaceSunken), contentAlignment = Alignment.Center) {
        if (awtComponent == null) {
            ZillitSpinner()
        } else {
            // Handed back when the pane leaves the screen: the engine parks the
            // component in a hidden window of its own, because a browser
            // component left with no parent is a browser that will not work the
            // next time the tool opens.
            DisposableEffect(awtComponent) {
                onDispose { engine.releaseSurface() }
            }
            // Hidden rather than removed while a dialog or the list covers
            // it: the browser view paints above every Compose pixel, and
            // hiding it keeps the map exactly where it was for when it returns.
            SwingPanel(
                factory = { awtComponent },
                modifier = Modifier.fillMaxSize(),
                update = { it.isVisible = visible },
            )
        }
    }
}

// Photos -------------------------------------------------------------------------

/**
 * The location form's photos: the system picker, the production's store, and
 * the media reader every other tool shows stored pictures with. HEIC and HEIF
 * become JPEG before they are stored, as the web converts them, so every
 * client can show them.
 */
private fun AppGraph.Ready.mapPhotos(): MapPhotos {
    val picker = AwtAttachmentPicker()
    val uploader = S3AttachmentUploader(
        httpClient = httpClient,
        credentials = { awsKeyPair(remoteConfigRepository)?.let { (access, secret) -> AwsCredentials(access, secret) } },
        storage = storageTarget,
        newKey = { fileName -> "map/${UUID.randomUUID()}/${fileName.safeKeyPart()}" },
    )
    return object : MapPhotos {
        override suspend fun pick(onRefused: (String) -> Unit): List<PickedPhoto> =
            picker.pick(
                kind = PreviewKind.Image,
                multiple = true,
                maxBytes = MAX_PHOTO_BYTES,
                onRefused = { refusal ->
                    when (refusal) {
                        is PickRefusal.TooLarge -> onRefused(refusal.name)
                        is PickRefusal.WrongKind -> onRefused(refusal.name)
                    }
                },
            )
                .filter { it.name.substringAfterLast('.', "").lowercase() in PHOTO_EXTENSIONS }
                .map { PickedPhoto(it.name, contentTypeFor(it.name, it.contentType), it.bytes) }

        override suspend fun upload(photo: PickedPhoto): ZillitResult<MapAttachment> {
            val ready = jpegIfHeic(photo)
            val extension = ready.name.substringAfterLast('.', "").lowercase()
            return when (val stored = uploader.upload(ready.name, contentTypeFor(ready.name, ready.contentType), ready.bytes)) {
                is ZillitResult.Failure -> stored
                is ZillitResult.Success -> {
                    val picture = decodeMapImage(ready.bytes, Int.MAX_VALUE)
                    ZillitResult.Success(
                        MapAttachment(
                            media = stored.data.media,
                            bucket = stored.data.bucket,
                            region = stored.data.region,
                            // An image is its own thumbnail on the web's uploads.
                            thumbnail = stored.data.media,
                            name = ready.name,
                            contentType = "image",
                            contentSubtype = extension,
                            width = picture?.width ?: 0,
                            height = picture?.height ?: 0,
                        ),
                    )
                }
            }
        }

        override suspend fun read(attachment: MapAttachment, preview: Boolean): ZillitResult<ByteArray> =
            noticeMedia.fetch(
                NoticeAttachment(
                    media = attachment.media,
                    thumbnail = attachment.thumbnail.ifBlank { null },
                    bucket = attachment.bucket.ifBlank { null },
                    region = attachment.region.ifBlank { null },
                ),
                preview = preview,
            )
    }
}

/** HEIC/HEIF to JPEG with macOS's own converter; the original when that is not possible. */
private suspend fun jpegIfHeic(photo: PickedPhoto): PickedPhoto = withContext(Dispatchers.IO) {
    val extension = photo.name.substringAfterLast('.', "").lowercase()
    if (extension != "heic" && extension != "heif") return@withContext photo
    runCatching {
        val dir = File(System.getProperty("java.io.tmpdir"), "zillit-map-${UUID.randomUUID()}").apply { mkdirs() }
        val input = File(dir, "in.$extension").apply { writeBytes(photo.bytes) }
        val output = File(dir, "out.jpg")
        val process = ProcessBuilder("sips", "-s", "format", "jpeg", input.absolutePath, "--out", output.absolutePath)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(CONVERT_SECONDS, TimeUnit.SECONDS)
        val converted = if (finished && process.exitValue() == 0 && output.isFile) output.readBytes() else null
        dir.deleteRecursively()
        converted?.let { PickedPhoto(photo.name.substringBeforeLast('.') + ".jpg", "image/jpeg", it) }
    }.onFailure { ZillitLog.w(TAG) { "HEIC conversion unavailable: ${it::class.simpleName}" } }
        .getOrNull() ?: photo
}

// Share ------------------------------------------------------------------------------

/** People in this production, and a direct message to each — the in-app share. */
private fun AppGraph.Ready.mapShare(): MapShareHost = object : MapShareHost {
    override fun people(): List<SharePerson> {
        val context = projectContext?.context?.value ?: return emptyList()
        val me = context.profile?.userId
        return context.users
            .filter { it.userId != me && it.userId.isNotBlank() && it.status.isActiveMember() }
            .map { SharePerson(it.userId, it.fullName, it.designationText().orEmpty()) }
    }

    override suspend fun send(userIds: List<String>, text: String): ZillitResult<Int> {
        var reached = 0
        var lastError: ZillitError? = null
        userIds.forEach { userId ->
            when (
                val sent = chatRepository.send(
                    receiverId = userId,
                    body = text,
                    uniqueId = UUID.randomUUID().toString(),
                    nowMillis = System.currentTimeMillis(),
                )
            ) {
                is ZillitResult.Success -> reached++
                is ZillitResult.Failure -> lastError = sent.error
            }
        }
        val error = lastError
        return if (reached == 0 && error != null) ZillitResult.Failure(error) else ZillitResult.Success(reached)
    }
}

/** Crew who are in the production now — not pending, rejected, left or removed. */
private fun String?.isActiveMember(): Boolean = this.isNullOrBlank() || this == "approved" || this == "accepted"

// Badges -------------------------------------------------------------------------------

/**
 * The map's badges by city: the ledger's `map_label` rows split by unit, as
 * the web's `getMapToolBadgesFromDB` groups them. A city read goes to the
 * server the way the web's `clearCityBadges` sends it — `notification:read`
 * with the tool as `module` and the city as `segment` — and clears the local
 * ledger at once, since no echo comes back for one's own read.
 */
private fun AppGraph.Ready.mapBadges(): MapBadges = object : MapBadges {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val cityUnread: Flow<Map<String, Int>> = badgeStore.counts
        .map { badgeStore.split(BadgeDrilldownQuery(groupBy = "unit", tool = MAP_BADGE_TOOL)) }
        .distinctUntilChanged()

    override fun markCityRead(cityId: String) {
        val projectId = projectContext?.context?.value?.project?.projectId ?: return
        scope.launch {
            runCatching {
                socketEvents.emit(
                    ZillitSocketEvents.Badges.NotificationRead,
                    NotificationReadDto(
                        projectId = projectId,
                        module = MAP_BADGE_TOOL,
                        segment = cityId,
                        timestamp = System.currentTimeMillis(),
                    ),
                    NotificationReadDto.serializer(),
                )
            }.onFailure { ZillitLog.w(TAG) { "city badge read not sent: ${it::class.simpleName}" } }
            badgeStore.markRead(LedgerRead.Levels(tool = MAP_BADGE_TOOL, unit = cityId))
        }
    }
}

// Preferences ----------------------------------------------------------------------------

/** The last city and each city's zone, per production — the web's two `localStorage` keys. */
private fun AppGraph.Ready.mapPrefs(): MapPrefs = object : MapPrefs {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun lastVisitedCityId(): String? =
        runCatching { preferences.get(LAST_CITY) }.getOrNull()?.takeIf { it.isNotBlank() }

    override suspend fun setLastVisitedCityId(cityId: String) {
        runCatching { preferences.set(LAST_CITY, cityId) }
    }

    override suspend fun activeZoneId(cityId: String): String? = zones()[cityId]

    override suspend fun setActiveZoneId(cityId: String, zoneId: String) {
        val next = zones() + (cityId to zoneId)
        val encoded = buildJsonObject { next.forEach { (city, zone) -> put(city, JsonPrimitive(zone)) } }.toString()
        runCatching { preferences.set(ACTIVE_ZONES, encoded) }
    }

    private suspend fun zones(): Map<String, String> = runCatching {
        (json.parseToJsonElement(preferences.get(ACTIVE_ZONES)) as? JsonObject)
            ?.mapNotNull { (city, zone) -> (zone as? JsonPrimitive)?.contentOrNull?.let { city to it } }
            ?.toMap()
    }.getOrNull().orEmpty()
}

private val LAST_CITY = PreferenceKey.StringKey("map.lastVisitedCityId", "", PreferenceScope.Project)
private val ACTIVE_ZONES = PreferenceKey.StringKey("map.activeZoneByCity", "{}", PreferenceScope.Project)

// Pictures ------------------------------------------------------------------------------

/**
 * A zone as a picture: Google Static Maps with the circle drawn as a filled
 * path, the map fitting itself around it. The production's own key, as the
 * shared-location pictures use.
 */
private fun AppGraph.Ready.mapStaticMaps(): MapStaticMaps = object : MapStaticMaps {
    override suspend fun zone(centre: LatLng, radiusMiles: Double): ByteArray? {
        val key = remoteConfigRepository.credentials.value?.googleMapsKey?.takeIf { it.isNotBlank() } ?: return null
        val ring = circlePoints(centre, radiusMiles).joinToString("|") { "${it.lat.format5()},${it.lng.format5()}" }
        val url = "https://maps.googleapis.com/maps/api/staticmap?size=640x320&scale=2" +
            "&path=color:0x3B82F6D9%7Cweight:3%7Cfillcolor:0x3B82F626%7C$ring" +
            "&markers=size:tiny%7Ccolor:0x3B82F6%7C${centre.lat},${centre.lng}&key=$key"
        return runCatching {
            val response = httpClient.get(url)
            if (response.status.isSuccess()) response.readRawBytes() else null
        }.onFailure { ZillitLog.w(TAG) { "zone preview failed: ${it::class.simpleName}" } }.getOrNull()
    }
}

/** Points on a circle of [radiusMiles] around [centre] — a destination-point walk. */
private fun circlePoints(centre: LatLng, radiusMiles: Double): List<LatLng> {
    val angular = radiusMiles * METERS_PER_MILE / EARTH_RADIUS_M
    val lat1 = Math.toRadians(centre.lat)
    val lng1 = Math.toRadians(centre.lng)
    return (0..CIRCLE_STEPS).map { step ->
        val bearing = 2 * PI * step / CIRCLE_STEPS
        val lat2 = asin(sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing))
        val lng2 = lng1 + atan2(sin(bearing) * sin(angular) * cos(lat1), cos(angular) - sin(lat1) * sin(lat2))
        LatLng(Math.toDegrees(lat2), Math.toDegrees(lng2))
    }
}

private fun Double.format5(): String = String.format(java.util.Locale.US, "%.5f", this)

/** Stored photos and zone previews, decoded once and kept for the session. */
private fun AppGraph.Ready.mapImages(): MapImages {
    val photos = mapPhotos()
    val statics = mapStaticMaps()
    val cache = object : LinkedHashMap<String, ImageBitmap>(IMAGE_CACHE, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean = size > IMAGE_CACHE
    }
    return object : MapImages {
        override suspend fun photo(attachment: MapAttachment, preview: Boolean): ImageBitmap? {
            val key = "${attachment.media}|$preview"
            synchronized(cache) { cache[key] }?.let { return it }
            val bytes = (photos.read(attachment, preview) as? ZillitResult.Success)?.data ?: return null
            val bitmap = withContext(Dispatchers.Default) {
                decodeMapImage(bytes, if (preview) PREVIEW_EDGE else FULL_EDGE)
            } ?: return null
            synchronized(cache) { cache[key] = bitmap }
            return bitmap
        }

        override suspend fun zonePreview(centre: LatLng, radiusMiles: Double): ImageBitmap? {
            val key = "zone|${centre.lat}|${centre.lng}|$radiusMiles"
            synchronized(cache) { cache[key] }?.let { return it }
            val bytes = statics.zone(centre, radiusMiles) ?: return null
            val bitmap = withContext(Dispatchers.Default) { decodeMapImage(bytes, FULL_EDGE) } ?: return null
            synchronized(cache) { cache[key] = bitmap }
            return bitmap
        }

        override fun decode(photo: PickedPhoto): ImageBitmap? = decodeMapImage(photo.bytes, PREVIEW_EDGE)
    }
}

/** The web's map badge tool label (`BADGE_CONSTANTS.map_label`). */
private const val MAP_BADGE_TOOL = "map_label"
private const val TAG = "MapsWiring"
private const val MAX_PHOTO_BYTES = 30L * 1024 * 1024
private const val CONVERT_SECONDS = 30L
private const val METERS_PER_MILE = 1_609.34
private const val EARTH_RADIUS_M = 6_371_000.0
private const val CIRCLE_STEPS = 48
private const val IMAGE_CACHE = 80
private const val LOAD_FACTOR = 0.75f
private const val PREVIEW_EDGE = 480
private const val FULL_EDGE = 2400
