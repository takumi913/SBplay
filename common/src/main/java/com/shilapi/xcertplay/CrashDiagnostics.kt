package com.shilapi.xcertplay

import android.content.Context
import android.os.Process
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App-private API 19 crash recorder, independent of logcat and SessionLogFile.
 *
 * A Java/Kotlin uncaught exception is persisted synchronously before the system's original
 * crash handler runs. A force-stop, native abort, kernel kill, or power loss cannot supply a
 * Java stack; synchronous checkpoints can still identify the last completed startup phase.
 * This is diagnostic-only: nothing is uploaded and it does not change the connection state.
 */
object CrashDiagnostics {
    private const val PREFS = "sbplay_crash_checkpoints"
    private const val KEY_PID = "pid"
    private const val KEY_TIME = "time"
    private const val KEY_STEP = "step"
    private const val MAX_CRASH_CHARS = 192 * 1024
    private const val CRASH_FILE = "last-java-crash.txt"

    private val installed = AtomicBoolean(false)
    private val recordingCrash = AtomicBoolean(false)

    @JvmStatic
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(object : Thread.UncaughtExceptionHandler {
            override fun uncaughtException(thread: Thread, throwable: Throwable) {
                try {
                    if (recordingCrash.compareAndSet(false, true)) {
                        saveCrash(app, throwable, "uncaught", thread.name)
                    }
                } catch (_: Throwable) {
                    // Never turn a crash recorder failure into a second crash.
                } finally {
                    if (previous != null) {
                        previous.uncaughtException(thread, throwable)
                    } else {
                        Process.killProcess(Process.myPid())
                        System.exit(10)
                    }
                }
            }
        })
    }

    /** New attempt starts fresh; never display a previous attempt's stack as the current failure. */
    internal fun beginAttempt(context: Context, label: String) {
        runCatching { crashFile(context).delete() }
        checkpoint(context, label)
    }

    /** Durable checkpoint: survives a kill even when SessionLogFile cannot flush the next line. */
    internal fun checkpoint(context: Context, step: String) {
        try {
            val safe = DiagnosticRedactor.redact(step) ?: "sensitive stage hidden"
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_PID, Process.myPid())
                .putLong(KEY_TIME, System.currentTimeMillis())
                .putString(KEY_STEP, safe)
                .commit()
        } catch (_: Throwable) {
            // Logging cannot be allowed to break the connection itself.
        }
    }

    /** Handled startup errors must also retain their stack, not just their simple class name. */
    internal fun recordCaught(context: Context, throwable: Throwable, phase: String) {
        runCatching { saveCrash(context, throwable, "caught: $phase", Thread.currentThread().name) }
    }

    internal fun hasCrash(context: Context): Boolean = crashFile(context).isFile

    private fun crashFile(context: Context) =
        File(File(context.filesDir, "diagnostics"), CRASH_FILE)

    /**
     * Keep all normal Java stack lines and nested Caused by / Suppressed sections.
     * Sensitive lines are replaced rather than exposing credentials; a single stack
     * frame is never silently filtered because the type/method name matched a redaction rule.
     */
    internal fun sanitizedStack(throwable: Throwable): List<String> {
        val raw = StringWriter()
        throwable.printStackTrace(PrintWriter(raw))
        return raw.toString().lineSequence()
            .filter { it.isNotEmpty() }
            .map { DiagnosticRedactor.redact(it) ?: "[redacted sensitive stack line]" }
            .toList()
    }

    private fun saveCrash(context: Context, throwable: Throwable, kind: String, thread: String) {
        val target = crashFile(context)
        val folder = target.parentFile ?: return
        if (!folder.isDirectory && !folder.mkdirs()) return
        val temp = File(folder, "$CRASH_FILE.tmp")
        val header = "time=${clock(System.currentTimeMillis())} pid=${Process.myPid()} thread=${DiagnosticRedactor.redact(thread) ?: "redacted"} kind=$kind"
        val lines = sanitizedStack(throwable)
        BufferedWriter(OutputStreamWriter(FileOutputStream(temp), "UTF-8")).use { out ->
            out.write(header)
            out.newLine()
            var used = header.length
            for (line in lines) {
                if (used + line.length > MAX_CRASH_CHARS) {
                    out.write("[stack truncated after 192 KiB; see device diagnostics if available]")
                    out.newLine()
                    break
                }
                out.write(line)
                out.newLine()
                used += line.length + 1
            }
            out.flush()
        }
        // Atomic replacement on Android's app-private filesystem; preserve the prior report if
        // the temporary write failed.
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    /** Presented before session logs so a screen photo captures the actionable failure first. */
    internal fun displayLines(
        context: Context,
        attempt: ConnectionAttempt?,
        outcome: ConnectionAttemptOutcome?,
    ): List<String> {
        val crash = runCatching { crashFile(context).takeIf { it.isFile }?.readLines() }.getOrNull()
            ?.mapNotNull(DiagnosticRedactor::redact).orEmpty()
        val checkpoint = runCatching {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pid = p.getInt(KEY_PID, -1)
            if (attempt == null || pid != attempt.pid) null else {
                "最后检查点：${p.getString(KEY_STEP, "unknown")} · ${clock(p.getLong(KEY_TIME, 0L))} · pid=$pid"
            }
        }.getOrNull()
        if (crash.isEmpty() && checkpoint == null && outcome !is ConnectionAttemptOutcome.Interrupted) {
            return emptyList()
        }
        val result = mutableListOf("—— 应用崩溃与启动检查点（本机保存）——")
        if (checkpoint != null) result += checkpoint
        if (crash.isNotEmpty()) {
            result += "【Java/Kotlin 异常类型、错误信息与堆栈】"
            result += crash
        } else if (outcome is ConnectionAttemptOutcome.Interrupted) {
            result += "未捕获到 Java/Kotlin 异常堆栈。可能是 Native 崩溃、系统杀进程或强制退出；不能据此判断具体原因。"
        } else {
            result += "无 Java/Kotlin 异常堆栈。"
        }
        result += "注意：包含堆栈的日志仅保存在车机本地；分享前请检查私人信息。"
        return result
    }

    private fun clock(millis: Long): String =
        if (millis <= 0) "unknown" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))
}
