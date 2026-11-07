package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureDetailTest {
    private val earlierAttempt = listOf(
        "DiPlay log started 2026-11-07 11:16:30.686 pid=2459 path=/data/data/x/files/logs/diplay.log",
        "11:16:30.801  Host started; MFI target=本地离线; transport=wireless",
        "11:16:40.000  ERROR old failure from an earlier attempt",
    )
    private val lastAttempt = listOf(
        "DiPlay log started 2026-11-07 11:18:22.039 pid=2728 path=/data/data/x/files/logs/diplay.log",
        "11:18:22.047  THEME_DIAGNOSTIC sample source=create uiMode=0x0 failed=no",
        "11:18:22.066  Host started; MFI target=本地离线; transport=wired",
        "11:18:22.168  Texture surface created",
        "11:18:22.655  Display detected: 1280x650",
        "11:18:23.000  STEP usb/discover: searching for an iPhone USB device",
        "11:18:24.000  some unrelated line",
        "11:18:25.000  ERROR Could not connect RFCOMM",
    )

    @Test fun onlyTheLastAttemptIsShown() {
        val shown = FailureDetail.lastAttempt(earlierAttempt + lastAttempt)
        assertEquals(lastAttempt, shown)
    }

    @Test fun diagnosticsSurfacesAndOrdinaryMessagesArePreserved() {
        val shown = FailureDetail.lastAttempt(lastAttempt)
        assertEquals(lastAttempt, shown)
        assertTrue(shown.any { it.contains("THEME_DIAGNOSTIC") })
        assertTrue(shown.any { it.contains("Texture surface") })
        assertTrue(shown.any { it.contains("Display detected") })
        assertTrue(shown.any { it.contains("some unrelated line") })
    }

    @Test fun withoutAnyAttemptStartEverythingIsConsidered() {
        val lines = listOf("11:00:00.000  STEP mfi/start: preparing", "11:00:01.000  nothing")
        assertEquals(lines, FailureDetail.lastAttempt(lines))
    }

    @Test fun hundredsOfLogLinesAreNotTruncated() {
        val lines = listOf("11:00:00.000  Host started") +
            (1..300).map { "11:00:01.000  diagnostic n=$it" }
        val shown = FailureDetail.lastAttempt(lines)
        assertEquals(301, shown.size)
        assertEquals(lines, shown)
        val pages = FailureDetail.paginate(shown, rowsPerPage = 8) { 1 }
        assertEquals(lines, pages.flatten())
    }

    @Test fun summaryNamesOutcomeTransportStepAndFullReason() {
        val reason = "No unambiguous bonded iPhone found; pair one iPhone and retry"
        val summary = FailureDetail.summary(
            ConnectionAttemptOutcome.Failed(wireless = true, stage = "正在连接 iPhone 蓝牙", reason = reason, atMillis = 1L),
            appVersion = "0.2.21-cs11.1", device = "Android 4.4.3 · CS11",
        ) { "11-07 11:16:33" }
        assertEquals("SBplay 0.2.21-cs11.1 · Android 4.4.3 · CS11", summary[0])
        assertEquals("【上次连接失败】", summary[1])
        assertEquals("方式：无线 · 时间：11-07 11:16:33", summary[2])
        assertEquals("停在：正在连接 iPhone 蓝牙", summary[3])
        // The full reason, not the 80-character home-card version.
        assertEquals("原因：$reason", summary[4])
    }

    @Test fun summaryWithoutAFailureSaysSo() {
        val summary = FailureDetail.summary(null, "v", "d")
        assertEquals(2, summary.size)
        assertTrue(summary[1].contains("没有记录到失败"))
    }

    @Test fun pagesNeverExceedTheirRows() {
        val lines = (1..10).map { "line $it" }
        // Every line takes 2 rows; 5 rows per page -> 2 lines per page -> 5 pages.
        val pages = FailureDetail.paginate(lines, rowsPerPage = 5) { 2 }
        assertEquals(5, pages.size)
        assertTrue(pages.all { it.size == 2 })
        assertEquals(lines, pages.flatten())
    }

    @Test fun aLineTallerThanAPageGetsAPageOfItsOwn() {
        val pages = FailureDetail.paginate(listOf("a", "huge", "b"), rowsPerPage = 3) { if (it == "huge") 10 else 1 }
        assertEquals(listOf(listOf("a"), listOf("huge"), listOf("b")), pages)
    }

    @Test fun anEmptyListStillHasOnePage() {
        assertEquals(listOf(emptyList<String>()), FailureDetail.paginate(emptyList(), rowsPerPage = 4) { 1 })
    }
}
