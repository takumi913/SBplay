package com.shilapi.xcertplay

import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneDiagnosisTest {
    private val meta = "source=VOICE_COMMUNICATION codec=OPUS rate=48000 channels=1 frameMs=20"

    private fun conclusion(lines: List<String>): String =
        MicrophoneDiagnosis.section(lines).first { it.startsWith("结论：") }

    private fun next(lines: List<String>): String =
        MicrophoneDiagnosis.section(lines).firstOrNull { it.startsWith("下一步：") } ?: ""

    private val telephonySetup =
        "10:00:00.000  Audio: setup type=telephony stream=100 codec=OPUS rate=48000 channels=1 " +
            "formatBits=0x40000000 input=true dataPort=yes mic=planned"
    private val mediaSetup =
        "10:00:00.000  Audio: setup type=media stream=100 codec=LPCM rate=48000 channels=2 " +
            "formatBits=0x8000 input=absent dataPort=no mic=skipped reason=no-input-requested"

    private fun call(peak: Int, effects: String = "applied=yes switch=on aec=enabled ns=enabled", mode: Boolean = true) = listOf(
        telephonySetup,
        "10:00:01.000  Audio: downlink started type=telephony stream=100 codec=OPUS rate=48000 channels=1",
        "10:00:01.001  Microphone: switches type=telephony stream=100 codec=OPUS rate=48000 " +
            "mic_effects=on mic_mode=${if (mode) "on" else "off"} appliesToThisStream=true",
        "10:00:01.002  Microphone: probe type=telephony $meta candidate=VOICE_COMMUNICATION result=2048",
        "10:00:01.003  Microphone: effects type=telephony $meta $effects",
        "10:00:01.004  Microphone: start type=telephony $meta routedDeviceType=unknown actualSource=VOICE_COMMUNICATION",
        "10:00:31.000  Microphone: summary type=telephony $meta seconds=30 captureBytes=2880000 " +
            "frames=1500 silentFrames=1500 peakMax=$peak readErrors=0 emptyEncodedFrames=0 udpSent=1500 sendErrors=0",
    ) + (if (mode) listOf("10:00:01.001  Audio: mode entered communication from=0 now=3 stream=AudioStreamId") else emptyList())

    @Test fun silentTelephonyCaptureBlamesTheEffectsFirst() {
        val text = conclusion(call(peak = 5))
        assertTrue(text, text.contains("几乎全是静音"))
        assertTrue(text, text.contains("最大音量 5"))
        assertTrue(text, text.contains("回声消除/降噪已启用"))
        assertTrue(next(call(peak = 5)).contains("debug.diplay.mic_effects 0"))
    }

    @Test fun withEffectsOffTheNextStepIsTheMode() {
        val lines = call(peak = 5, effects = "applied=no reason=switch-off switch=off")
        assertTrue(next(lines), next(lines).contains("debug.diplay.mic_mode 0"))
    }

    @Test fun loudCaptureSaysTheCarSideWorks() {
        val text = conclusion(call(peak = 12000))
        assertTrue(text, text.contains("车机这一端正常"))
    }

    @Test fun silentNonTelephonyCaptureDoesNotBlameTheSwitches() {
        val lines = listOf(
            "10:00:00.000  Audio: setup type=default stream=100 codec=OPUS rate=48000 channels=1 " +
                "formatBits=0x40000000 input=true dataPort=yes mic=planned",
            "10:00:01.000  Microphone: start type=default source=MIC codec=OPUS rate=48000 channels=1 frameMs=20 " +
                "routedDeviceType=unknown actualSource=VOICE_COMMUNICATION",
            "10:00:11.000  Microphone: summary type=default source=MIC codec=OPUS rate=48000 channels=1 frameMs=20 " +
                "seconds=10 captureBytes=960000 frames=500 silentFrames=500 peakMax=3 readErrors=0 " +
                "emptyEncodedFrames=0 udpSent=500 sendErrors=0",
        )
        val text = conclusion(lines)
        assertTrue(text, text.contains("两个通话开关对它不生效"))
    }

    @Test fun noMicrophoneEverRequestedPointsAtBluetoothHandsFree() {
        val lines = listOf(mediaSetup, "10:00:05.000  bluetooth profiles a2dp=false hfp=true")
        val text = conclusion(lines)
        assertTrue(text, text.contains("从来没有向车机要过麦克风"))
        assertTrue(text, text.contains("hfp=true"))
    }

    @Test fun plannedButNoDownlinkExplainsWhyTheMicNeverStarted() {
        val text = conclusion(listOf(telephonySetup))
        assertTrue(text, text.contains("下行声音一直没开始"))
    }

    @Test fun everySourceRefusedNamesTheVoiceAssistant() {
        val lines = listOf(
            telephonySetup,
            "10:00:01.000  Audio: downlink started type=telephony stream=100 codec=OPUS rate=48000 channels=1",
            "10:00:01.002  Microphone: probe type=telephony $meta candidate=VOICE_COMMUNICATION result=-3",
            "10:00:01.003  Microphone: failure type=telephony $meta stage=RECORDER_INITIALIZATION error=none reason=none code=none",
        )
        assertTrue(conclusion(lines).contains("语音助手"))
    }

    @Test fun nothingSentIsReported() {
        val lines = call(peak = 9000).map {
            if (it.contains("Microphone: summary")) it.replace("udpSent=1500", "udpSent=0") else it
        }
        assertTrue(conclusion(lines).contains("一个包都没发给 iPhone"))
    }

    @Test fun statsWithoutSummaryAreStillTotalled() {
        val lines = listOf(
            telephonySetup,
            "10:00:01.004  Microphone: start type=telephony $meta routedDeviceType=unknown actualSource=VOICE_COMMUNICATION",
            "10:00:06.000  Microphone: stats type=telephony $meta routedDeviceType=unknown captureBytes=480000 reads=250 " +
                "zeroReads=0 readErrors=0 readMaxMs=21 encodedFrames=250 emptyEncodedFrames=0 udpSent=250 sendErrors=0 " +
                "sendGapMaxMs=25 frames=250 silentFrames=0 peakMax=8000 ended=false",
        )
        val section = MicrophoneDiagnosis.section(lines)
        assertTrue(section.joinToString("\n"), section.any { it.contains("最大音量 8000") && it.contains("没有结束记录") })
        assertTrue(conclusion(lines).contains("车机这一端正常"))
    }

    @Test fun anEmptyLogStillGivesAConclusion() {
        assertTrue(conclusion(emptyList()).contains("没有任何音频流记录"))
    }
}
