package com.zillit.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.zillit.desktop.core.datastore.PreferenceStore
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

/**
 * Builds the application graph, and every view model keyed on it, beside the
 * first window instead of in front of it.
 *
 * ## The measurement this exists for
 *
 * On a packaged dev image, process start to a visible main window split three
 * nearly equal ways (see [StartupTrace], which is still in the build so the
 * split can be re-read at any time):
 *
 * | | |
 * |---|---|
 * | [AppGraph.build] | ~1.2s |
 * | [buildAppViewModels] — every screen's view model | ~1.2s |
 * | Compose's own first frame — Skia, the window, the first composition | ~1.4s |
 *
 * The first two both ran *before* the first `Window` composed, because the
 * composition opened with `remember { AppGraph.build() }` and every view model
 * was keyed on the result. The third cannot be avoided; the other two can be
 * moved alongside it.
 *
 * ## Why both stages are on the one thread
 *
 * Moving only the graph made the window appear 1.8s sooner and then froze it
 * for the next 1.2s: the swap to [AppGraph.Ready] re-ran the view models, by
 * then on the UI thread with the window up. Nothing in the second stage wants
 * the UI thread — a per-view-model trace put no single one above ~120ms, so the
 * cost is 60-odd constructors and the class loading behind them, not one slow
 * screen that could be made lazy on its own.
 *
 * The two land together, as one [Wired], rather than the graph first: a graph
 * published on its own would recompose the whole application against `Ready`
 * with the starting screen's empty view models, and then again a second later.
 * One swap, with everything in it, is both cheaper and simpler to reason about.
 *
 * ## Why a thread started from `main`, not a `LaunchedEffect`
 *
 * A `LaunchedEffect` does not run until the first composition has already
 * happened, which would serialise the build *after* Compose's 1.4s rather than
 * across it. Started from `main` the two overlap almost exactly, and the build
 * is close to free: the window waits on whichever of the two is slower, not on
 * their sum.
 *
 * It is a plain thread rather than a coroutine because it starts before there
 * is any scope to launch in, and because [AppGraph.build] is one long blocking
 * call — two SQLCipher opens through the credential store among them — with
 * nothing for a dispatcher to interleave.
 *
 * ## Why a failure is held rather than thrown
 *
 * Today a graph that cannot be built throws out of the composition, where
 * `installCrashLogging` logs it and Compose puts its dialog up. A throw on this
 * thread would instead leave the app on the [AppGraph.Building] screen for
 * ever, which is the worst of the options — so the throwable is carried over
 * and raised in the composition, where the existing handling still applies.
 * Hence `Result` rather than widening [AppGraph] with a failure state: nothing
 * is meant to *render* for a failure.
 */
internal object AppGraphLoader {

    /**
     * A graph and the view models built for it.
     *
     * They are one value because they are one swap: see the doc above.
     */
    internal class Wired(val graph: AppGraph, val viewModels: AppViewModels)

    private val state = MutableStateFlow<Result<Wired>?>(null)

    /**
     * The wiring, or null while the thread is still at it — in which case
     * [current] is showing the starting screen's own [Wired].
     */
    val wired: StateFlow<Result<Wired>?> = state.asStateFlow()

    /**
     * How the thread gets what only a composition has.
     *
     * [buildAppViewModels] needs the preference store and the application's
     * coroutine scope, and the scope is `rememberCoroutineScope()` — it does
     * not exist until the first composition. So the composition hands over a
     * closure instead, and the thread waits on it.
     *
     * The wait is not a risk in either direction: the first composition
     * happens about a second before [AppGraph.build] finishes, so in practice
     * the value is already there; and the thread is a daemon, so even a
     * composition that never ran could not hold the process open.
     */
    private val builder = CompletableFuture<(AppGraph) -> AppViewModels>()

    /**
     * Starts the build. Call once, from `main`, after the single-instance
     * claim — the point of that guard is that a second copy opens neither the
     * database nor the preference file, and this opens both.
     */
    fun start() {
        thread(name = THREAD_NAME, isDaemon = true) {
            state.value = runCatching {
                val graph = StartupTrace.time("AppGraph.build()") { AppGraph.build() }
                val build = builder.get()
                Wired(graph, StartupTrace.time("view models off the UI thread") { build(graph) })
            }
        }
    }

    /**
     * The wiring for the composition, recomposing when the thread finishes.
     *
     * Until then it is [AppGraph.Building] and the view models that go with it
     * — every screen null, because there is no graph to build one from, which
     * is the state every call site already handles for `Unconfigured`.
     *
     * Rethrows a build failure on the caller's thread, so a graph that cannot
     * be built fails where it used to.
     */
    @Composable
    fun current(preferences: PreferenceStore, scope: CoroutineScope): Wired {
        val starting = remember {
            builder.complete { graph -> buildAppViewModels(graph, preferences, scope) }
            Wired(
                AppGraph.Building,
                StartupTrace.time("view models for the starting screen") {
                    buildAppViewModels(AppGraph.Building, preferences, scope)
                },
            )
        }
        val loaded by wired.collectAsState()
        return loaded?.getOrThrow() ?: starting
    }

    /** Named so it is recognisable in a thread dump sent in from the field. */
    private const val THREAD_NAME = "zillit-app-graph"
}
