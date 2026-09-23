package com.zillit.desktop.feature.maps

import com.zillit.desktop.feature.maps.data.MapCanvasClient
import com.zillit.desktop.feature.maps.data.MapCanvasWire
import com.zillit.desktop.feature.maps.domain.GeocodeOutcome
import com.zillit.desktop.feature.maps.domain.LatLng
import com.zillit.desktop.feature.maps.domain.MapCanvasEvent
import com.zillit.desktop.feature.maps.domain.MapCanvasHost
import com.zillit.desktop.feature.maps.domain.MapScene
import com.zillit.desktop.feature.maps.domain.MarkerActionKind
import com.zillit.desktop.feature.maps.domain.PlaceKind
import com.zillit.desktop.feature.maps.domain.SceneMarker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The page's wire and the client that speaks it. */
@OptIn(ExperimentalCoroutinesApi::class)
class MapCanvasTest {

    @Test
    fun `page events decode, and junk decodes to nothing`() {
        assertEquals(MapCanvasEvent.Ready, MapCanvasWire.parseEvent("""{"type":"map-ready"}"""))
        assertEquals(
            MapCanvasEvent.MarkerAction(MarkerActionKind.Directions, "l1"),
            MapCanvasWire.parseEvent("""{"type":"marker-action","action":"directions","id":"l1"}"""),
        )
        assertEquals(
            MapCanvasEvent.PreviewAdd(LatLng(1.5, -2.25), "Andheri", "Andheri West"),
            MapCanvasWire.parseEvent(
                """{"type":"preview-action","action":"add","lat":1.5,"lng":-2.25,""" +
                    """"name":"Andheri","address":"Andheri West"}""",
            ),
        )
        assertEquals(
            MapCanvasEvent.MarkerDragged("l1", LatLng(3.0, 4.0)),
            MapCanvasWire.parseEvent("""{"type":"marker-dragged","id":"l1","lat":3,"lng":4}"""),
        )
        assertIs<MapCanvasEvent.Failed>(MapCanvasWire.parseEvent("""{"type":"auth-failed"}"""))
        assertEquals(
            MapCanvasEvent.Guide(collapsed = true, dismissed = false),
            MapCanvasWire.parseEvent("""{"type":"guide","collapsed":true}"""),
        )
        assertNull(MapCanvasWire.parseEvent("""{"type":"ready"}"""))
        assertNull(MapCanvasWire.parseEvent("not json"))
        assertNull(MapCanvasWire.parseEvent("""{"type":"marker-dragged","id":"l1"}"""))
        assertTrue(MapCanvasWire.isPageReady("""{"type":"ready"}"""))
    }

    @Test
    fun `every payload crosses as one quoted string`() {
        val scene = MapScene(
            markers = listOf(SceneMarker("x", "It's \"quoted\" </script>", "Hotel", "H", "#8E44AD", LatLng(1.0, 2.0))),
        )
        val script = MapCanvasWire.renderScript(scene)
        assertTrue(script.startsWith("zillitMap.render(\""))
        assertTrue(script.endsWith("\")"))
        val inner = Json.parseToJsonElement(
            script.removePrefix("zillitMap.render(").removeSuffix(")"),
        ).jsonPrimitive.content
        val decoded = Json.parseToJsonElement(inner).jsonObject
        val markers = decoded["markers"]!! as kotlinx.serialization.json.JsonArray
        val firstName = markers[0].jsonObject["name"]!!.jsonPrimitive.content
        assertEquals("It's \"quoted\" </script>", firstName)
        assertEquals("null", decoded["zone"].toString())
        assertEquals("zillitMap.boot(\"k\\\"ey\")", MapCanvasWire.bootScript("k\"ey"))
    }

    private class FakeHost : MapCanvasHost {
        override val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val scripts = mutableListOf<String>()
        override fun execute(script: String) {
            scripts += script
        }
    }

    private fun requestId(script: String): Int {
        val payload = Json.parseToJsonElement(
            script.removePrefix("zillitMap.request(").removeSuffix(")"),
        ).jsonPrimitive.content
        return Json.parseToJsonElement(payload).jsonObject["id"]!!.jsonPrimitive.content.toInt()
    }

    @Test
    fun `a request is answered by the reply with its id`() = runTest {
        val host = FakeHost()
        val client = MapCanvasClient(host, backgroundScope)
        client.start()
        runCurrent()
        val answer = async { client.geocode("MG Road & Ring Road, Pune", null) }
        runCurrent()
        val id = requestId(host.scripts.last { it.startsWith("zillitMap.request") })
        host.messages.emit("""{"type":"reply","id":${id + 1},"ok":true,"lat":9,"lng":9}""")
        host.messages.emit("""{"type":"reply","id":$id,"ok":true,"lat":18.5,"lng":73.8,"address":"MG Rd, Pune"}""")
        runCurrent()
        assertEquals(GeocodeOutcome.Found(LatLng(18.5, 73.8), "MG Rd, Pune"), answer.await())
    }

    @Test
    fun `a page that never answers times out to nothing`() = runTest {
        val host = FakeHost()
        val client = MapCanvasClient(host, backgroundScope)
        client.start()
        val answer = async { client.predictions("Andheri", PlaceKind.Any) }
        runCurrent()
        advanceTimeBy(13_000)
        runCurrent()
        assertNull(answer.await())
        assertEquals(emptyList(), client.predictions("", PlaceKind.Any))
    }

    @Test
    fun `a page that loads late gets the scene, theme and camera again`() = runTest {
        val host = FakeHost()
        val client = MapCanvasClient(host, backgroundScope)
        client.start()
        runCurrent()
        client.render(MapScene(pinMode = true))
        client.panTo(LatLng(19.0, 72.8), 13)
        host.scripts.clear()
        host.messages.emit("""{"type":"map-ready"}""")
        runCurrent()
        assertTrue(host.scripts.any { it.startsWith("zillitMap.render(") })
        assertTrue(host.scripts.any { it.startsWith("zillitMap.camera(") })
        // An unchanged scene is not restated.
        host.scripts.clear()
        client.render(MapScene(pinMode = true))
        assertTrue(host.scripts.isEmpty())
    }
}
