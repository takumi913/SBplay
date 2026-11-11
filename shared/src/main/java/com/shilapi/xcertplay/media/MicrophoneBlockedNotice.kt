package com.shilapi.xcertplay.media

/**
 * Tells the screen, while a call is still on, that the head unit's microphone is not really ours.
 *
 * On the CS11 the car's own voice wake word ("小度小度" / "嗨领克", switched from the smiley icon at the
 * top left of the head unit) keeps the microphone open. A CarPlay capture then either has every source
 * refused (`-3`), or opens and records exact zeros. Both used to be visible only on the diagnostic page
 * after hanging up; the far end simply heard nothing. The host activity listens and shows a short hint.
 *
 * Only a hint: nothing here touches the voice assistant or its setting.
 */
object MicrophoneBlockedNotice {
    enum class Reason(val token: String) {
        /** Every recorder source was refused. */
        ALL_SOURCES_REFUSED("all-sources-refused"),

        /** The capture opened but its first seconds were exact zeros (no real microphone has that). */
        DIGITAL_SILENCE("digital-silence"),
    }

    /** Called on the capture thread; the listener posts to its own thread. */
    @Volatile var listener: ((Reason) -> Unit)? = null

    /** Clears [expected] only if it is still the listener, so a newer activity's listener survives. */
    fun detach(expected: (Reason) -> Unit) {
        if (listener === expected) listener = null
    }

    internal fun publish(reason: Reason) {
        // A failing screen must never cost the capture.
        runCatching { listener?.invoke(reason) }
    }
}

/**
 * Decides, once per capture, whether its opening stretch is digital silence.
 *
 * Exact zeros only: a working microphone always has some noise floor, so even a driver who says nothing
 * produces non-zero samples. "Quiet" would misfire on every call where the driver just listens. The first
 * non-zero frame settles the question the other way for the rest of the capture.
 */
internal class DigitalSilenceDetector(
    sampleRate: Int,
    seconds: Int = DEFAULT_SECONDS,
) {
    private val samplesToDecide = maxOf(1L, sampleRate.toLong() * seconds)
    private var zeroSamples = 0L
    private var decided = false

    /** True exactly once: when the first [seconds] of frames have all been exact zero. */
    fun onFrame(peak: Int, samples: Int): Boolean {
        if (decided) return false
        if (peak != 0) {
            decided = true
            return false
        }
        zeroSamples += samples
        if (zeroSamples < samplesToDecide) return false
        decided = true
        return true
    }

    private companion object {
        const val DEFAULT_SECONDS = 3
    }
}
