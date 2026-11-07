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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Private Java/Kotlin exception recorder for Android 4.4.3 / API 19.
 *
 * The original system crash handler still runs. Each exception gets a distinct,
 * bounded file and old crashes remain available across subsequent attempts.
 * A native abort, force-stop, or system kill has no Java stack: use the persisted
 * connection checkpoint instead. No uploads or connection-behavior changes.
 */
object CrashDiagnostics {
    private const val PREFS = "sbplay_crash_checkpoints"
    private const val KEY_PID = "pid"
    private const val KEY_TIME = "time"
    private const val KEY_STEP = "step"
    private const val MAX_CRASH_CHARS = 192 * 1024
    private const val MAX_RECENT_CRASHES = 5
    private const val LEGACY_CRASH_FILE = "last-java-crash.txt"
    private val FILE_PATTERN = Regex("^crash-([0-9]+)-pid([0-9]+)-([0-9]+)[.]txt$")
    private val HEADER_PID = Regex("(?:^| )pid=([0-9]+)(?: |$)")
    private val sequence = AtomicInteger(0)

    private val installed = AtomicBoolean(false)
    private val recordingCrash = AtomicBoolean(false)

    internal data class CrashEntry(
        val file: File,
        val pid: Int?,
        val recordedAtMillis: Long,
    )

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
                    // An exception in diagnostics must not replace the original failure.
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

    /** Starting another connection must never delete the previous crash stack. */
    internal fun beginAttempt(context: Context, label: String) {
        checkpoint(context, label)
    }

    /** Durable last step: usable even if a process dies without a Java exception. */
    internal fun checkpoint(context: Context, step: String) {
        try {
            val safe = DiagnosticRedactor.redact(step) ?: "sensitive stage hidden"
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_PID, Process.myPid())
                .putLong(KEY_TIME, System.currentTimeMillis())
                .putString(KEY_STEP, safe)
                .commit()
        } catch (_: Throwable) {
            // Never break connection startup just because a checkpoint could not be saved.
        }
    }

    internal fun recordCaught(context: Context, throwable: Throwable, phase: String) {
        runCatching { saveCrash(context, throwable, "caught: $phase", Thread.currentThread().name) }
    }

    private fun crashDirectory(context: Context) = File(context.filesDir, "diagnostics")

    internal fun hasCrash(context: Context): Boolean {
        val directory = crashDirectory(context)
        return File(directory, LEGACY_CRASH_FILE).isFile ||
            directory.listFiles()?.any { it.isFile && FILE_PATTERN.matches(it.name) } == true
    }

    internal fun sanitizedStack(throwable: Throwable): List<String> {
        val raw = StringWriter()
        throwable.printStackTrace(PrintWriter(raw))
        return raw.toString().lineSequence()
            .filter { it.isNotEmpty() }
            .map { DiagnosticRedactor.redact(it) ?: "[redacted sensitive stack line]" }
            .toList()
    }

    private fun saveCrash(context: Context, throwable: Throwable, kind: String, thread: String) {
        val directory = crashDirectory(context)
        if (!directory.isDirectory && !directory.mkdirs()) return
        val whenMillis = System.currentTimeMillis()
        val pid = Process.myPid()
        val name = "crash-${whenMillis}-pid${pid}-${sequence.incrementAndGet()}.txt"
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        val header = "time=${clock(whenMillis)} pid=$pid thread=${DiagnosticRedactor.redact(thread) ?: "redacted"} kind=$kind"
        val lines = sanitizedStack(throwable)
        FileOutputStream(temporary).use { stream ->
            val output = BufferedWriter(OutputStreamWriter(stream, "UTF-8"))
            output.write(header)
            output.newLine()
            var used = header.length
            for (line in lines) {
                if (used + line.length > MAX_CRASH_CHARS) {
                    output.write("[stack truncated after 192 KiB; see device diagnostics if available]")
                    output.newLine()
                    break
                }
                output.write(line)
                output.newLine()
                used += line.length + 1
            }
            output.flush()
            runCatching { stream.fd.sync() }
        }
        // Never overwrite or delete another exception (including the legacy crash).
        if (!temporary.renameTo(target)) {
            temporary.delete()
            return
        }
        pruneNewCrashes(directory)
    }

    internal fun newCrashEntries(directory: File): List<CrashEntry> =
        directory.listFiles().orEmpty().mapNotNull { file ->
            if (!file.isFile) return@mapNotNull null
            val match = FILE_PATTERN.matchEntire(file.name) ?: return@mapNotNull null
            val millis = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val pid = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            CrashEntry(file, pid, millis)
        }.sortedWith(compareByDescending<CrashEntry> { it.recordedAtMillis }
            .thenByDescending { it.file.name })

    /** Keep only five new crash stacks; the one-file legacy format remains readable. */
    internal fun pruneNewCrashes(directory: File) {
        newCrashEntries(directory).drop(MAX_RECENT_CRASHES).forEach { runCatching { it.file.delete() } }
    }

    private fun crashEntries(context: Context): List<CrashEntry> {
        val directory = crashDirectory(context)
        val newer = newCrashEntries(directory).take(MAX_RECENT_CRASHES)
        val legacy = File(directory, LEGACY_CRASH_FILE)
        if (!legacy.isFile) return newer
        val firstLine = runCatching {
            legacy.bufferedReader().use { it.readLine().orEmpty() }
        }.getOrDefault("")
        val pid = HEADER_PID.find(firstLine)?.groupValues?.get(1)?.toIntOrNull()
        return newer + CrashEntry(legacy, pid, legacy.lastModified())
    }

    /**
     * A crash from the connection PID takes precedence over later, unrelated
     * crashes (e.g. Android 4.4's text-selection popup in the diagnostics UI).
     */
    internal fun prioritizeCrashes(entries: List<CrashEntry>, connectionPid: Int?): List<CrashEntry> =
        entries.sortedWith(compareByDescending<CrashEntry> {
            connectionPid != null && it.pid == connectionPid
        }.thenByDescending { it.recordedAtMillis }.thenByDescending { it.file.name })

    /** Current attempt's stack first; clearly distinguish stacks from other PIDs. */
    internal fun displayLines(
        context: Context,
        attempt: ConnectionAttempt?,
        outcome: ConnectionAttemptOutcome?,
    ): List<String> {
        val connectionPid = attempt?.pid
        val crashes = prioritizeCrashes(crashEntries(context), connectionPid)
        val checkpoint = runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val storedPid = prefs.getInt(KEY_PID, -1)
            if (attempt == null || storedPid != attempt.pid) null
            else "最后检查点：${prefs.getString(KEY_STEP, "unknown")} · ${clock(prefs.getLong(KEY_TIME, 0L))} · pid=$storedPid"
        }.getOrNull()
        if (crashes.isEmpty() && checkpoint == null && outcome !is ConnectionAttemptOutcome.Interrupted) {
            return emptyList()
        }
        val result = mutableListOf("—— 应用崩溃与启动检查点（本机保存）——")
        if (checkpoint != null) result += checkpoint
        val hasMatchingCrash = connectionPid != null && crashes.any { it.pid == connectionPid }
        if (outcome is ConnectionAttemptOutcome.Interrupted && !hasMatchingCrash) {
            result += "连接进程 pid=$connectionPid 未找到对应 Java/Kotlin 异常堆栈。可能是 Native 崩溃、系统杀进程或其他退出；下列不同 PID 的异常不能作为此次连接的原因。"
        }
        for (entry in crashes) {
            val matched = connectionPid != null && entry.pid == connectionPid
            val source = if (matched) "与本次连接 PID 匹配"
                else if (connectionPid != null) "其他进程异常，非本次连接的直接证据"
                else "历史 Java/Kotlin 异常"
            result += "【$source · pid=${entry.pid ?: "未知"} · ${clock(entry.recordedAtMillis)}】"
            result += runCatching {
                entry.file.useLines { lines ->
                    lines.map { DiagnosticRedactor.redact(it) ?: "[redacted sensitive stack line]" }.toList()
                }
            }.getOrElse { listOf("读取异常堆栈失败：${it.javaClass.simpleName}") }
        }
        if (crashes.isEmpty() && outcome !is ConnectionAttemptOutcome.Interrupted) {
            result += "无已保存的 Java/Kotlin 异常堆栈。"
        }
        result += "注意：最多保留最近 5 次新异常及旧版历史记录，分享前请检查私人信息。"
        return result
    }

    private fun clock(millis: Long): String =
        if (millis <= 0) "unknown" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))
}
