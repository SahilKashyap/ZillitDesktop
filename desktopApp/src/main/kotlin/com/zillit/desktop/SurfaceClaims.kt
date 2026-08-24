package com.zillit.desktop

import java.util.concurrent.atomic.AtomicLong

/**
 * Who currently owns the one browser component.
 *
 * There is a single heavyweight Chromium AWT component and three places that
 * can host it: the main window, the call window, and the call window's
 * thumbnail. Moving between them is a hand-off, and for one frame both hosts
 * exist — the arriving `SwingPanel` adds the component to its own interop group
 * before the leaving one disposes.
 *
 * That matters because parking is destructive. `Container.addImpl` removes a
 * component from its current parent first, so a leaving host that parks
 * unconditionally rips the component out of the group that now legitimately
 * owns it, leaving a *live* `SwingInteropViewGroup` with zero children. Compose
 * then remeasures it and `getPreferredSize` runs `getComponents()[0]` on an
 * empty array — `Index 0 out of bounds for length 0`, fatal, mid-call.
 *
 * So each mount takes a ticket and each unmount offers it back: only the newest
 * ticket may park. A superseded host's release becomes a no-op.
 */
class SurfaceClaims {
    private val latest = AtomicLong(0)

    /** Taken as a host mounts the surface. */
    fun claim(): Long = latest.incrementAndGet()

    /**
     * True when [claim] is still the current owner and may park the component.
     *
     * Must be asked *after* the frame has settled — the whole point is to see
     * whether anyone else took the surface in the meantime.
     */
    fun mayPark(claim: Long): Boolean = latest.get() == claim
}

/**
 * One host's turn holding the browser component.
 *
 * Handed out by `KcefCallEngine.hostSurface()` and released when the host's
 * Compose node is disposed. Releasing a lease that has been superseded does
 * nothing, which is what makes a hand-off safe.
 */
class SurfaceLease(private val onRelease: () -> Unit) {
    fun release() = onRelease()
}
