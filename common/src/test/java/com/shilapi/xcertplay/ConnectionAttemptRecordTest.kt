package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ConnectionAttemptRecordTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val pid = 1000
    private val nextPid = 2000

    @Before fun reset() {
        context.getSharedPreferences("diplay_connection_attempt", 0).edit().clear().commit()
    }

    @Test fun nothingIsShownBeforeAnyAttempt() {
        assertNull(ConnectionAttemptRecord.outcome(context, pid))
        assertTrue(ConnectionAttemptRecord.report(context, pid).startsWith("No unfinished"))
    }

    @Test fun failureSurvivesTheCleanEndAndNamesTheLastStep() {
        ConnectionAttemptRecord.started(context, wireless = true, keepFailure = false, pid = pid, nowMillis = 1L)
        ConnectionAttemptRecord.progressed(context, "正在连接 iPhone 蓝牙")
        ConnectionAttemptRecord.failed(context, "正在连接 iPhone 蓝牙", "No unambiguous bonded iPhone found; pair one iPhone and retry", 2L)
        ConnectionAttemptRecord.ended(context)

        val outcome = ConnectionAttemptRecord.outcome(context, pid) as ConnectionAttemptOutcome.Failed
        assertTrue(outcome.wireless)
        assertEquals("正在连接 iPhone 蓝牙", outcome.stage)
        assertTrue(outcome.reason.startsWith("No unambiguous bonded iPhone"))
        assertEquals("上次连接失败", ConnectionAttemptRecord.title(outcome))
        // The record outlives the process: the next process still sees the failure.
        assertTrue(ConnectionAttemptRecord.outcome(context, nextPid) is ConnectionAttemptOutcome.Failed)
    }

    @Test fun retriesInTheSameHostKeepTheRecordedFailure() {
        ConnectionAttemptRecord.started(context, wireless = false, keepFailure = false, pid = pid, nowMillis = 1L)
        ConnectionAttemptRecord.failed(context, "正在与 iPhone 配对", "USBMUX read failed", 2L)
        ConnectionAttemptRecord.started(context, wireless = false, keepFailure = true, pid = pid, nowMillis = 3L)

        val outcome = ConnectionAttemptRecord.outcome(context, pid) as ConnectionAttemptOutcome.Failed
        assertEquals("USBMUX read failed", outcome.reason)
    }

    @Test fun aNewAttemptDiscardsTheOldFailure() {
        ConnectionAttemptRecord.started(context, wireless = true, keepFailure = false, pid = pid, nowMillis = 1L)
        ConnectionAttemptRecord.failed(context, null, "old failure", 2L)
        ConnectionAttemptRecord.started(context, wireless = false, keepFailure = false, pid = pid, nowMillis = 3L)

        // Still connecting in this process: nothing to report yet.
        assertNull(ConnectionAttemptRecord.outcome(context, pid))
    }

    @Test fun theFirstRenderedFrameClearsEverything() {
        ConnectionAttemptRecord.started(context, wireless = true, keepFailure = false, pid = pid, nowMillis = 1L)
        ConnectionAttemptRecord.failed(context, null, "transient", 2L)
        ConnectionAttemptRecord.sessionActive(context, 3L)
        ConnectionAttemptRecord.firstFrame(context)

        assertNull(ConnectionAttemptRecord.load(context))
        assertNull(ConnectionAttemptRecord.outcome(context, pid))
        assertNull(ConnectionAttemptRecord.outcome(context, nextPid))
    }

    @Test fun anActiveSessionDropsEarlierFailuresButKeepsWaitingForAPicture() {
        ConnectionAttemptRecord.started(context, wireless = false, keepFailure = false, pid = pid, nowMillis = 1L)
        ConnectionAttemptRecord.failed(context, null, "transient", 2L)
        ConnectionAttemptRecord.sessionActive(context, 3L)
        ConnectionAttemptRecord.progressed(context, "CarPlay 控制通道运行中")

        val attempt = ConnectionAttemptRecord.load(context)!!
        assertNull(attempt.reason)
        // Later status lines must not overwrite the step that matters now.
        assertEquals(ConnectionAttemptRecord.WAITING_FOR_FIRST_FRAME, attempt.stage)
        // Still waiting in this process: nothing to report yet.
        assertNull(ConnectionAttemptRecord.outcome(context, pid))
        assertTrue(ConnectionAttemptRecord.report(context, pid).contains("waiting for the first frame"))
    }

    @Test fun aSessionThatEndsWithoutAFrameIsReportedAsNoVideo() {
        ConnectionAttemptRecord.started(context, wireless = false, keepFailure = false, pid = pid, nowMillis = 1L)
        ConnectionAttemptRecord.sessionActive(context, 5L)
        ConnectionAttemptRecord.ended(context)

        val outcome = ConnectionAttemptRecord.outcome(context, pid) as ConnectionAttemptOutcome.NoVideo
        assertFalse(outcome.wireless)
        assertEquals(5L, outcome.atMillis)
        assertEquals("上次连接没有画面", ConnectionAttemptRecord.title(outcome))
        assertTrue(ConnectionAttemptRecord.report(context, pid).contains("outcome=no video"))
    }

    @Test fun aProcessThatDiedWhileWaitingForAPictureIsNoVideoToo() {
        ConnectionAttemptRecord.started(context, wireless = true, keepFailure = false, pid = pid, nowMillis = 1L)
        ConnectionAttemptRecord.sessionActive(context, 5L)

        assertTrue(ConnectionAttemptRecord.outcome(context, nextPid) is ConnectionAttemptOutcome.NoVideo)
    }

    @Test fun aFailureAfterTheSessionWentActiveStillWins() {
        ConnectionAttemptRecord.started(context, wireless = false, keepFailure = false, pid = pid, nowMillis = 1L)
        ConnectionAttemptRecord.sessionActive(context, 5L)
        ConnectionAttemptRecord.failed(context, null, "USBMUX read failed", 6L)
        ConnectionAttemptRecord.ended(context)

        val outcome = ConnectionAttemptRecord.outcome(context, pid) as ConnectionAttemptOutcome.Failed
        assertEquals("USBMUX read failed", outcome.reason)
        assertEquals(ConnectionAttemptRecord.WAITING_FOR_FIRST_FRAME, outcome.stage)
    }

    @Test fun aRetryAfterASessionWithoutAPictureKeepsThatAsTheFailure() {
        ConnectionAttemptRecord.started(context, wireless = false, keepFailure = false, pid = pid, nowMillis = 1L)
        ConnectionAttemptRecord.sessionActive(context, 5L)
        ConnectionAttemptRecord.started(context, wireless = false, keepFailure = true, pid = pid, nowMillis = 9L)
        ConnectionAttemptRecord.progressed(context, "正在与 iPhone 配对")

        val attempt = ConnectionAttemptRecord.load(context)!!
        assertEquals(ConnectionAttemptRecord.NO_VIDEO_REASON, attempt.reason)
        assertEquals(5L, attempt.failedAtMillis)
        assertEquals(0L, attempt.sessionActiveAtMillis)
        // The retry records its own steps again.
        assertEquals("正在与 iPhone 配对", attempt.stage)
    }

    @Test fun noVideoDetailNamesTransportAndSaysThePictureNeverCame() {
        val detail = ConnectionAttemptRecord.detail(
            ConnectionAttemptOutcome.NoVideo(wireless = false, stage = null, atMillis = 5L),
        ) { "11-07 11:18" }
        assertEquals("USB · 11-07 11:18\n会话已建立，但到会话结束都没有收到 iPhone 的画面。", detail)
    }

    @Test fun aProcessThatDiedMidConnectIsReportedAsInterrupted() {
        ConnectionAttemptRecord.started(context, wireless = true, keepFailure = false, pid = pid, nowMillis = 1L)
        ConnectionAttemptRecord.progressed(context, "正在启动无线 CarPlay 热点")

        // Same process, still connecting: not an interruption.
        assertNull(ConnectionAttemptRecord.outcome(context, pid))
        val outcome = ConnectionAttemptRecord.outcome(context, nextPid) as ConnectionAttemptOutcome.Interrupted
        assertEquals("正在启动无线 CarPlay 热点", outcome.stage)
        assertEquals("上次连接被中断", ConnectionAttemptRecord.title(outcome))
        assertTrue(ConnectionAttemptRecord.report(context, nextPid).contains("outcome=interrupted"))
    }

    @Test fun aCleanEndWithoutFailureShowsNothing() {
        ConnectionAttemptRecord.started(context, wireless = true, keepFailure = false, pid = pid, nowMillis = 1L)
        ConnectionAttemptRecord.ended(context)

        assertNull(ConnectionAttemptRecord.outcome(context, nextPid))
        assertTrue(ConnectionAttemptRecord.report(context, nextPid).contains("ended without a failure"))
    }

    @Test fun eventsWithoutAStartedAttemptAreIgnored() {
        ConnectionAttemptRecord.progressed(context, "stage")
        ConnectionAttemptRecord.failed(context, "stage", "reason", 2L)
        ConnectionAttemptRecord.ended(context)

        assertNull(ConnectionAttemptRecord.load(context))
    }

    @Test fun reasonsAreRedactedBeforeTheyReachDisk() {
        ConnectionAttemptRecord.started(context, wireless = true, keepFailure = false, pid = pid, nowMillis = 1L)
        ConnectionAttemptRecord.failed(context, null, "Could not connect RFCOMM to AA:BB:CC:DD:EE:FF", 2L)
        val stored = ConnectionAttemptRecord.load(context)!!.reason!!
        assertFalse(stored.contains("AA:BB"))
        assertTrue(stored.contains("[address]"))

        ConnectionAttemptRecord.failed(context, null, "hotspot ssid=Secret failed", 3L)
        assertEquals(ConnectionAttemptRecord.REDACTED_REASON, ConnectionAttemptRecord.load(context)!!.reason)
    }

    @Test fun multiLineStagesAreFlattened() {
        ConnectionAttemptRecord.started(context, wireless = true, keepFailure = false, pid = pid, nowMillis = 1L)
        ConnectionAttemptRecord.progressed(context, "第一行\n第二行")
        assertEquals("第一行 第二行", ConnectionAttemptRecord.load(context)!!.stage)
    }

    @Test fun homeDetailIsShortAndNamesTransportAndStep() {
        val long = "x".repeat(200)
        val detail = ConnectionAttemptRecord.detail(
            ConnectionAttemptOutcome.Failed(wireless = false, stage = "正在与 iPhone 配对", reason = long, atMillis = 5L),
        ) { "11-07 11:18" }
        val lines = detail.split('\n')
        assertEquals("USB · 11-07 11:18 · 停在：正在与 iPhone 配对", lines[0])
        assertEquals(ConnectionAttemptRecord.HOME_REASON_MAX_CHARS, lines[1].length)
        assertTrue(lines[1].endsWith("…"))
    }

    @Test fun interruptedDetailSaysNoReasonWasLeft() {
        val detail = ConnectionAttemptRecord.detail(
            ConnectionAttemptOutcome.Interrupted(wireless = true, stage = null, atMillis = 0L),
        ) { "?" }
        assertEquals("无线 · ?\n连接途中应用被结束，没有留下失败原因。", detail)
    }
}
