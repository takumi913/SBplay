package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class MicrophoneCaptureStatsTest {
    private val config = MicrophoneConfig("telephony", 48_000, 1, 100, 20,
        InetAddress.getByName("198.51.100.20"), 54321, ByteArray(32) { 0x7f }, AudioCodecKind.OPUS)

    @Test fun aggregatesFiveSecondWindowsAndKeepsFinalPartialWindow() {
        var now = 0L
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { now }
        stats.started(15)
        stats.reading()
        now = 25_000_000
        stats.read(1920)
        stats.encoded(1, 0)
        stats.sent()
        stats.reading()
        now += 30_000_000
        stats.read(0)
        stats.encoded(0, 1)
        stats.sent()
        stats.sendFailed()
        stats.flush()
        assertEquals(1, reports.size)
        now = 5_000_000_000
        stats.flush(routeType = { 18 })
        val first = reports.last()
        assertTrue(first.contains("captureBytes=1920 reads=2 zeroReads=1 readErrors=0 readMaxMs=30"))
        assertTrue(first.contains("encodedFrames=1 emptyEncodedFrames=1 udpSent=2 sendErrors=1 sendGapMaxMs=30"))
        assertTrue(first.contains("routedDeviceType=18"))
        assertTrue(first.endsWith("ended=false"))
        stats.reading()
        now += 8_000_000
        stats.read(-3)
        stats.failure(MicrophoneFailureStage.READ, code = -3)
        stats.flush(ended = true)
        val final = reports.last()
        assertTrue(final.contains("captureBytes=0 reads=1 zeroReads=0 readErrors=1 readMaxMs=8"))
        assertTrue(final.contains("encodedFrames=0 emptyEncodedFrames=0 udpSent=0 sendErrors=0"))
        assertTrue(final.endsWith("ended=true"))
    }

    @Test fun levelIsReportedPerWindowAndInTheFinalSummary() {
        var now = 0L
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { now }
        stats.probe(6, -3)
        stats.probe(7, 2048)
        stats.started(15, actualSource = 7)
        stats.level(5)
        stats.level(12_000)
        stats.read(1920)
        stats.sent()
        now = 5_000_000_000
        stats.flush()
        stats.level(3)
        stats.sent()
        stats.sendFailed()
        now = 7_000_000_000
        stats.flush(ended = true)
        stats.finish()

        assertTrue(reports[0].endsWith("candidate=VOICE_RECOGNITION result=-3"))
        assertTrue(reports[1].endsWith("candidate=VOICE_COMMUNICATION result=2048"))
        assertTrue(reports[2].endsWith("actualSource=VOICE_COMMUNICATION"))
        assertTrue(reports[3], reports[3].contains("frames=2 silentFrames=1 peakMax=12000 zeroFrames=0 meanAbs=0 ended=false"))
        // The window resets; the summary keeps the whole capture.
        assertTrue(reports[4], reports[4].contains("frames=1 silentFrames=1 peakMax=3 zeroFrames=0 meanAbs=0 ended=true"))
        val summary = reports[5]
        assertTrue(summary, summary.startsWith("Microphone: summary type=telephony source=VOICE_COMMUNICATION"))
        assertTrue(summary, summary.contains("seconds=7 captureBytes=1920 frames=3 silentFrames=2 peakMax=12000"))
        assertTrue(summary, summary.endsWith("udpSent=2 sendErrors=1"))
        assertTrue(reports.all { it.length < 512 && '\n' !in it && !it.contains("port=") && !it.contains("key=") })
    }

    @Test fun defaultAndCompatibilityStreamsAreNamedInTheReport() {
        val reports = mutableListOf<String>()
        MicrophoneCaptureStats(config.copy(audioType = "default"), reports::add) { 0 }.started(null)
        MicrophoneCaptureStats(config.copy(audioType = "compatibility"), reports::add) { 0 }.started(null)
        assertTrue(reports[0].startsWith("Microphone: start type=default source=MIC"))
        assertTrue(reports[1].startsWith("Microphone: start type=compatibility source=MIC"))
    }

    @Test fun reportingIsBoundedAndRouteQueriesRunOnlyWhenDue() {
        var now = 0L
        var reports = 0
        var routeQueries = 0
        var longest = 0
        val stats = MicrophoneCaptureStats(config, { reports++; longest = maxOf(longest, it.length) }) { now }
        repeat(100_000) {
            stats.reading()
            now += 1000
            stats.read(0)
            stats.encoded(0, 1)
            stats.flush(routeType = { routeQueries++; 15 })
        }
        assertEquals(0, reports)
        assertEquals(0, routeQueries)
        now = 5_000_000_000
        stats.flush(routeType = { routeQueries++; 15 })
        assertEquals(1, reports)
        assertEquals(1, routeQueries)
        assertTrue(longest < 512)
    }

    @Test fun callbackAndRouteFailuresDoNotEscapeOrPreventCounterReset() {
        var now = 0L
        var fail = true
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, {
            if (fail) throw IllegalStateException("diagnostic callback failed")
            reports.add(it)
        }) { now }
        stats.started(15)
        stats.failure(MicrophoneFailureStage.CAPTURE, IllegalStateException("not reported"))
        stats.reading()
        stats.read(1920)
        stats.sent()
        now = 5_000_000_000
        stats.flush(routeType = { throw IllegalStateException("route metadata unavailable") })
        fail = false
        stats.flush(ended = true)
        assertEquals(1, reports.size)
        assertTrue(reports.single().contains("captureBytes=0 reads=0"))
        assertTrue(reports.single().contains("udpSent=0"))
        assertTrue(reports.single().contains("routedDeviceType=unknown"))
    }

    @Test fun outputContainsOnlyAllowlistedMetadataAndNeverExceptionMessagesOrEndpoints() {
        val reports = mutableListOf<String>()
        val privateConfig = config.copy(audioType = "PRIVATE_PHONE_NAME\nsecret=value")
        val stats = MicrophoneCaptureStats(privateConfig, reports::add) { 0 }
        stats.started(15)
        stats.failure(MicrophoneFailureStage.CAPTURE, IllegalStateException("SECRET_PAYLOAD 198.51.100.20 54321"))
        stats.flush(ended = true)
        MicrophoneCaptureStats.reportStartFailure(privateConfig, SecurityException("PRIVATE_DEVICE_ADDRESS"), reports::add)
        val text = reports.joinToString("\n")
        assertTrue(text.contains("type=other source=MIC codec=OPUS"))
        assertTrue(text.contains("stage=CAPTURE error=IllegalStateException"))
        for (privateValue in listOf("PRIVATE", "secret", "SECRET", "198.51.100.20", "54321", "head=", "payload=", "key=")) {
            assertFalse(privateValue, text.contains(privateValue))
        }
        assertEquals(4, reports.size)
        assertTrue(reports.all { it.length < 512 && '\n' !in it })
    }

    @Test fun summaryTellsDigitalSilenceFromNoiseAndWhenSoundFirstArrived() {
        var now = 0L
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { now }
        stats.started(null, actualSource = 7)
        stats.level(0, 0, 960)
        stats.level(0, 0, 960)
        now = 1_500_000_000
        stats.level(4000, 960_000, 960)
        stats.encoded(1, 0, 3)
        stats.encoded(1, 0, 61)
        stats.encoded(0, 1, 0)
        now = 5_000_000_000
        stats.flush(ended = true)
        stats.finish("closed")
        val window = reports[reports.size - 2]
        assertTrue(window, window.contains("peakMax=4000 zeroFrames=2 meanAbs=333 ended=true"))
        val summary = reports.last()
        assertTrue(summary, summary.contains("zeroFrames=2 loudFrames=1 meanAbs=333 firstSoundMs=1500"))
        assertTrue(summary, summary.contains("encodedBytes=64 avgPacketBytes=32 endedBy=closed"))
        assertTrue(summary, summary.length < 512)
    }

    @Test fun summaryOfACaptureThatNeverHeardAnythingSaysNever() {
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { 0 }
        stats.level(5)
        stats.finish()
        assertTrue(reports.last(), reports.last().contains("firstSoundMs=never"))
        assertTrue(reports.last(), reports.last().contains("endedBy=unknown"))
    }

    @Test fun probeAndNoteLinesCarryOnlyNumbers() {
        val reports = mutableListOf<String>()
        val stats = MicrophoneCaptureStats(config, reports::add) { 0 }
        stats.probe(7, 2048, 17)
        stats.note("recorder", "recordingState=3 actualRate=48000")
        assertTrue(reports[0], reports[0].endsWith("candidate=VOICE_COMMUNICATION result=2048 probePeak=17"))
        assertTrue(reports[1], reports[1].startsWith("Microphone: recorder type=telephony source=VOICE_COMMUNICATION"))
        assertTrue(reports[1], reports[1].endsWith("recordingState=3 actualRate=48000"))
    }
}
