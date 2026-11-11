package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitalSilenceDetectorTest {
    /** 20 ms mono frames at 48 kHz, the shape a CarPlay call capture uses. */
    private val samples = 960

    @Test fun threeSecondsOfExactZeroFiresOnce() {
        val detector = DigitalSilenceDetector(sampleRate = 48_000)
        val fired = (1..400).count { detector.onFrame(peak = 0, samples = samples) }
        assertEquals(1, fired)
    }

    @Test fun itFiresAtThreeSecondsNotBefore() {
        val detector = DigitalSilenceDetector(sampleRate = 48_000)
        repeat(149) { assertFalse(detector.onFrame(0, samples)) }
        assertTrue(detector.onFrame(0, samples))
    }

    @Test fun aNoiseFloorNeverCountsAsBlocked() {
        // A driver who only listens still produces a few units of noise; that is a working microphone.
        val detector = DigitalSilenceDetector(sampleRate = 48_000)
        assertFalse((1..400).any { detector.onFrame(peak = 3, samples = samples) })
    }

    @Test fun oneRealFrameSettlesItForTheRestOfTheCapture() {
        val detector = DigitalSilenceDetector(sampleRate = 48_000)
        repeat(10) { detector.onFrame(0, samples) }
        detector.onFrame(1200, samples)
        assertFalse((1..400).any { detector.onFrame(0, samples) })
    }

    @Test fun aDetachedListenerIsNotCalled() {
        val calls = mutableListOf<MicrophoneBlockedNotice.Reason>()
        val listener: (MicrophoneBlockedNotice.Reason) -> Unit = { calls += it }
        val other: (MicrophoneBlockedNotice.Reason) -> Unit = {}
        MicrophoneBlockedNotice.listener = listener
        MicrophoneBlockedNotice.detach(other)
        MicrophoneBlockedNotice.publish(MicrophoneBlockedNotice.Reason.DIGITAL_SILENCE)
        MicrophoneBlockedNotice.detach(listener)
        MicrophoneBlockedNotice.publish(MicrophoneBlockedNotice.Reason.ALL_SOURCES_REFUSED)
        assertEquals(listOf(MicrophoneBlockedNotice.Reason.DIGITAL_SILENCE), calls)
    }
}
