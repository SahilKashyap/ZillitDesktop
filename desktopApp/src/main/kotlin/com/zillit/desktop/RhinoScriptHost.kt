package com.zillit.desktop

import com.zillit.desktop.feature.payroll.domain.PayrollScriptHost
import io.github.aakira.napier.Napier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * Runs the payroll service's pay engine on the JVM.
 *
 * ## Why a JavaScript engine at all
 *
 * The overtime rules are the payroll service's, published per agreement as a
 * browser bundle and fetched at runtime (`lib/otEngine.js`). Every client runs
 * that bundle; a second implementation of the same rules in Kotlin would agree
 * with it only until the first agreement changed, and the figure that drifts
 * is somebody's pay. So the bundle itself is run here.
 *
 * ## Interpreted, always
 *
 * Rhino's compiling mode emits a JVM method per function and these bundles are
 * large — a single function over the 64 KB method limit fails to load at all,
 * and it would fail on exactly the biggest agreements. Interpreted mode has no
 * such ceiling, and an estimate is seven day calculations, not a hot loop.
 *
 * ## One engine at a time
 *
 * A Rhino [Context] belongs to the thread that entered it, and the bundles
 * bind their namespace on a shared scope. Every load and call therefore takes
 * the same lock, which also gives the "one active engine" guarantee the boards
 * rely on when they block selection during a fill.
 */
internal class RhinoScriptHost : PayrollScriptHost {

    private val lock = Mutex()
    private val scopes = mutableMapOf<String, Scriptable>()

    override suspend fun load(key: String, source: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            if (scopes.containsKey(key)) return@withLock true
            runCatching {
                inContext { context ->
                    val scope = context.initStandardObjects()
                    // The bundle is browser code: it looks for a global object
                    // under whichever name its wrapper was built with, and some
                    // log on load. Giving it all four costs nothing and is the
                    // difference between a bundle that runs and one that throws
                    // on its first line.
                    listOf("globalThis", "self", "window", "global").forEach { alias ->
                        ScriptableObject.putProperty(scope, alias, scope)
                    }
                    context.evaluateString(scope, CONSOLE_SHIM, "console", 1, null)
                    context.evaluateString(scope, source, key, 1, null)
                    val engine = ScriptableObject.getProperty(scope, ENGINE)
                    check(engine is Scriptable) { "bundle did not define $ENGINE" }
                    scopes[key] = scope
                }
                true
            }.getOrElse { failure ->
                // A bundle that will not run is not fatal: the boards that
                // price locally say they cannot, exactly as the web does when
                // its own load fails.
                Napier.e("Payroll pay engine $key would not load", failure)
                false
            }
        }
    }

    override suspend fun call(key: String, function: String, arguments: List<String>): String? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val scope = scopes[key] ?: return@withLock null
                // Fails closed rather than throwing: the name is spliced into
                // the call below, so anything that is not a bare identifier is
                // refused here rather than run.
                if (!IDENTIFIER.matches(function)) return@withLock null
                runCatching {
                    inContext { context ->
                        // The arguments cross as STRINGS and are parsed inside
                        // the engine's own realm. Splicing them into the source
                        // would make every deal document a script.
                        // Built as an Object[] on purpose: Rhino's newArray
                        // rejects any other component type outright, and a
                        // String[] — which `toTypedArray()` gives — is one.
                        val array = context.newArray(scope, Array<Any>(arguments.size) { arguments[it] })
                        ScriptableObject.putProperty(scope, ARGUMENTS, array)
                        val result = context.evaluateString(scope, invoke(function), function, 1, null)
                        (result as? String)?.takeIf { it.isNotBlank() && it != "null" }
                    }
                }.getOrElse { failure ->
                    Napier.e("Payroll pay engine $key.$function failed", failure)
                    null
                }
            }
        }

    private fun <T> inContext(block: (Context) -> T): T {
        val context = Context.enter()
        return try {
            context.languageVersion = Context.VERSION_ES6
            @Suppress("DEPRECATION")
            context.optimizationLevel = INTERPRETED
            block(context)
        } finally {
            Context.exit()
        }
    }

    private fun invoke(function: String): String = """
        (function (raw) {
          var fn = $ENGINE['$function'];
          if (typeof fn !== 'function') return null;
          var parsed = [];
          for (var i = 0; i < raw.length; i++) parsed.push(JSON.parse(raw[i]));
          var out = fn.apply($ENGINE, parsed);
          return (out === null || out === undefined) ? null : JSON.stringify(out);
        })($ARGUMENTS)
    """.trimIndent()

    private companion object {
        const val ENGINE = "OTEngine"
        const val ARGUMENTS = "__zillitEngineArgs"
        const val INTERPRETED = -1
        val IDENTIFIER = Regex("^[A-Za-z_$][A-Za-z0-9_$]*$")

        /** Bundles log on load; without this the first `console.log` throws. */
        val CONSOLE_SHIM = """
            var console = { log: function () {}, warn: function () {}, error: function () {}, info: function () {} };
        """.trimIndent()
    }
}
