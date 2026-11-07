// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

/**
 * The connection stage, published by the CarPlay host for the home page.
 *
 * The host owns the session and renders the bring-up itself; while it sits behind the home page its own
 * stage line is invisible, so it republishes the friendly text here and the home page's small line under
 * the gauge reads it. Process-local and deliberately tiny: one volatile string, written on the UI thread
 * whenever the stage changes and cleared when the session goes live or the host shuts down.
 *
 * Nothing reads this for control flow - it is display text only, so a stale value costs at most one
 * misleading line, never a wrong connection decision.
 */
internal object CarPlayProgress {
    @Volatile
    var stage: String? = null
        private set

    fun publish(text: String) {
        stage = text
    }

    fun clear() {
        stage = null
    }

    /**
     * Transport of the session this process is bringing up or running (true = wireless), null when there is
     * none. Published by the host at every controller start - so the in-session switch to USB updates it - and
     * cleared only on shutdown, not when the session goes live. The home page uses it to keep its tab on the
     * running transport (ConnectionModePolicy). Display only, like [stage].
     */
    @Volatile
    var sessionWireless: Boolean? = null
        private set

    fun publishTransport(wireless: Boolean) {
        sessionWireless = wireless
    }

    fun clearTransport() {
        sessionWireless = null
    }

    /**
     * True between the session going active and its first rendered frame (first-frame watchdog, README gap 4).
     * Set and cleared only on events by the host; the home page uses it to say "waiting for the picture"
     * instead of "connected". Display only.
     */
    @Volatile
    var awaitingFirstFrame: Boolean = false
        private set

    fun setAwaitingFirstFrame(waiting: Boolean) {
        awaitingFirstFrame = waiting
    }
}

/**
 * Start the session and hand the screen straight back to the home page.
 *
 * The connection used to own the screen from the first tap: the host activity came up with its "preparing
 * CarPlay" checklist and only swapped to video once the first frame arrived. The home page now stays put
 * instead: it starts the host with this extra, the host reorders the home page to the front right after
 * `next.start()`, and reorders itself back when the session goes live. The controller and the link already
 * outlive the UI (`onStop`), so nothing is torn down in between.
 */
internal const val EXTRA_START_BEHIND = "com.shilapi.xcertplay.extra.START_BEHIND"

/**
 * A still frame of the home page, taken just before the CarPlay host is started from it.
 *
 * The host opens in its own task, and between the task switch and the hand-back there are a few hundred
 * milliseconds in which neither screen is drawn - which the driver sees as a black flash. Painting this
 * still frame as the host's first content makes that window show exactly what was on screen a moment ago;
 * it is dropped as soon as the session owns the display. RGB_565 keeps the copy at ~1.6 MB, and it is
 * released immediately after use.
 */
internal object CarPlayLaunchSnapshot {
    @Volatile
    var bitmap: android.graphics.Bitmap? = null
}



