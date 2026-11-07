package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashDiagnosticsTest {
    @Test fun javaCrashIncludesTypeMessageAndNestedCauseWithFrames() {
        val cause = IllegalArgumentException("invalid display")
        cause.stackTrace = arrayOf(StackTraceElement("com.shilapi.xcertplay.DisplayConfig", "resolve", "DisplayConfig.kt", 42))
        val error = IllegalStateException("could not start receiver", cause)
        error.stackTrace = arrayOf(StackTraceElement("com.shilapi.xcertplay.CarPlayHostActivity", "startCarPlay", "CarPlayHostActivity.kt", 2399))
        val shown = CrashDiagnostics.sanitizedStack(error)
        assertTrue(shown.any { it.contains("java.lang.IllegalStateException: could not start receiver") })
        assertTrue(shown.any { it.contains("CarPlayHostActivity.startCarPlay") })
        assertTrue(shown.any { it.contains("Caused by: java.lang.IllegalArgumentException: invalid display") })
        assertTrue(shown.any { it.contains("DisplayConfig.resolve") })
    }

    @Test fun sensitiveStackLinesNeverLeak() {
        val throwable = IllegalStateException("password=example123")
        throwable.stackTrace = arrayOf(StackTraceElement("com.example.Foo", "run", "Foo.kt", 9))
        val lines = CrashDiagnostics.sanitizedStack(throwable)
        assertTrue(lines.any { it == "[redacted sensitive stack line]" })
        assertTrue(lines.none { it.contains("example123") })
        assertTrue(lines.any { it.contains("Foo.run") })
    }

    @Test fun sizeBoundedDiagnosticsCanBePaginatedWithoutDiscardingFrames() {
        val e = IllegalStateException("test")
        e.stackTrace = (1..150).map { StackTraceElement("com.example.Foo", "run", "Foo.kt", it) }.toTypedArray()
        val lines = CrashDiagnostics.sanitizedStack(e)
        assertTrue(lines.size > 150)
        val pages = FailureDetail.paginate(lines, 8) { 1 }
        assertEquals(lines, pages.flatten())
    }
}
