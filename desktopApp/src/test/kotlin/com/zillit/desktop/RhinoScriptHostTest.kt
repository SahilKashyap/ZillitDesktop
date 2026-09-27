package com.zillit.desktop

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge to the payroll service's pay engine.
 *
 * The real bundle is fetched from the server at runtime and cannot be pinned
 * here, so these run engine-SHAPED bundles instead: a classic script that
 * binds `OTEngine` on the global, called with JSON arguments and answering
 * JSON. What is under test is the bridge — that a browser bundle loads at all,
 * that arguments cross as data rather than as source, and that a bundle which
 * will not run is a refusal rather than a crash.
 */
class RhinoScriptHostTest {

    private val engine = """
        var OTEngine = (function () {
          function calcDay(ui, idx, opts) {
            if (!ui || !opts || !opts.rates) return null;
            var hours = opts.rates.hours || 10;
            return {
              basicPay: opts.rates.daily,
              otPay: idx * 10,
              dayGross: opts.rates.daily + idx * 10,
              workedMin: hours * 60,
              lines: [{ identifier: 'basic', label: 'Basic', rate_amount: opts.rates.daily }],
              prev: opts.prevDay ? opts.prevDay.type : null,
              type: ui.type
            };
          }
          function deriveRatesFromDeal(deal) {
            return { daily: deal.rates.daily_rate, hp_rate: 0.1077 };
          }
          return { calcDay: calcDay, deriveRatesFromDeal: deriveRatesFromDeal };
        })();
    """.trimIndent()

    @Test
    fun `a browser bundle loads and its namespace is callable`() = runTest {
        val host = RhinoScriptHost()
        assertTrue(host.load("default@1", engine))
        val rates = host.call("default@1", "deriveRatesFromDeal", listOf("""{"rates":{"daily_rate":350}}"""))
        assertEquals("""{"daily":350,"hp_rate":0.1077}""", rates)
    }

    /** Three arguments, in order, and the answer comes back as JSON. */
    @Test
    fun `a day is priced from JSON arguments`() = runTest {
        val host = RhinoScriptHost()
        host.load("default@1", engine)
        val day = host.call(
            "default@1",
            "calcDay",
            listOf("""{"type":"SWD","call":"07:00"}""", "2", """{"rates":{"daily":350},"prevDay":{"type":"CWD"}}"""),
        )
        requireNotNull(day)
        assertTrue(""""basicPay":350""" in day, day)
        assertTrue(""""otPay":20""" in day, day)
        assertTrue(""""workedMin":600""" in day, day)
        // The third argument's prevDay reached the engine, so the turnaround
        // chain this port relies on is genuinely being passed through.
        assertTrue(""""prev":"CWD"""" in day, day)
        assertTrue(""""type":"SWD"""" in day, day)
    }

    /** An engine that answers nothing for a day it cannot price answers null here. */
    @Test
    fun `a day the engine declines comes back as nothing`() = runTest {
        val host = RhinoScriptHost()
        host.load("default@1", engine)
        assertNull(host.call("default@1", "calcDay", listOf("null", "0", "null")))
    }

    /**
     * Arguments are DATA. A deal document is another team's JSON and must not
     * be able to run as source — a bundle spliced together from it would make
     * every field an injection point.
     */
    @Test
    fun `an argument cannot execute as source`() = runTest {
        val host = RhinoScriptHost()
        host.load("default@1", """var OTEngine = { echo: function (x) { return x; } };""")
        val hostile = """{"name":"\"); OTEngine.hacked = true; (\""}"""
        assertEquals(hostile.replace(" ", ""), host.call("default@1", "echo", listOf(hostile))?.replace(" ", ""))
        assertNull(host.call("default@1", "hacked", listOf()))
    }

    @Test
    fun `a bundle that binds nothing is refused rather than thrown`() = runTest {
        val host = RhinoScriptHost()
        assertFalse(host.load("broken@1", "var Something = 1;"))
        assertNull(host.call("broken@1", "calcDay", listOf("{}")))
    }

    @Test
    fun `a bundle that will not parse is refused`() = runTest {
        assertFalse(RhinoScriptHost().load("bad@1", "var OTEngine = {{{"))
    }

    /** Bundles log on load; a console that throws would take the whole engine down. */
    @Test
    fun `a bundle may log on load`() = runTest {
        val host = RhinoScriptHost()
        val chatty = "console.log('loading'); var OTEngine = { ok: function () { return 1; } };"
        assertTrue(host.load("chatty@1", chatty))
        assertEquals("1", host.call("chatty@1", "ok", listOf()))
    }

    /** Two agreements' bundles stay loaded side by side, each callable by key. */
    @Test
    fun `two engines are held at once and do not overwrite each other`() = runTest {
        val host = RhinoScriptHost()
        host.load("pact@1", """var OTEngine = { name: function () { return 'pact'; } };""")
        host.load("bectu@2", """var OTEngine = { name: function () { return 'bectu'; } };""")
        assertEquals("\"pact\"", host.call("pact@1", "name", listOf()))
        assertEquals("\"bectu\"", host.call("bectu@2", "name", listOf()))
    }

    @Test
    fun `a function name that is not an identifier is refused`() = runTest {
        val host = RhinoScriptHost()
        host.load("default@1", engine)
        assertNull(host.call("default@1", "calcDay'] || eval('1", listOf()))
    }

    @Test
    fun `an unknown key answers nothing`() = runTest {
        assertNull(RhinoScriptHost().call("never@0", "calcDay", listOf("{}")))
    }
}
