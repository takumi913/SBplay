package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * “失败详情”页的内容：一屏一页、便于拍照的失败摘要和最近一次连接的完整会话日志。
 *
 * 不按关键字过滤，也不限制显示行数；只受 SessionLogFile 已保留的日志文件范围约束。
 * 只读、只显示：日志写入前已脱敏，这里显示前再脱敏一次；摘要也已脱敏。
 * 不上传、不修改连接状态。
 */
internal object FailureDetail {
    /** 一次连接从哪里开始：主机界面实例启动时写的这一行。 */
    private const val ATTEMPT_START = "Host started"
    private val LOG_START_MARKERS = listOf("SBplay log started", "DiPlay log started")

    /**
     * 最近一次连接的所有行：从最后一个 `Host started` 往前找到它所在日志的开头一行，取到末尾。
     * 没有 `Host started`（例如从没连过）就取全部。
     */
    fun lastAttempt(lines: List<String>): List<String> {
        val start = lines.indexOfLast { it.contains(ATTEMPT_START) }
        if (start < 0) return lines
        val header = (start downTo 0).firstOrNull { LOG_START_MARKERS.any { marker -> lines[it].contains(marker) } } ?: start
        return lines.subList(header, lines.size)
    }

    /** 页首的摘要。[outcome] 为 null 表示没有记录到失败。 */
    fun summary(
        outcome: ConnectionAttemptOutcome?,
        appVersion: String,
        device: String,
        hasCrash: Boolean = false,
        clock: (Long) -> String = ::clockText,
    ): List<String> {
        val lines = mutableListOf("SBplay $appVersion · $device")
        if (outcome == null) {
            lines += if (hasCrash) "【检测到未捕获的 Java/Kotlin 异常】堆栈见下方。" else "【没有记录到失败】下面是最近一次连接的完整日志。"
            return lines
        }
        lines += "【${ConnectionAttemptRecord.title(outcome)}】"
        lines += "方式：${if (outcome.wireless) "无线" else "USB"} · 时间：${clock(outcome.atMillis)}"
        outcome.stage?.let { lines += "停在：$it" }
        lines += when (outcome) {
            is ConnectionAttemptOutcome.Failed -> "原因：${outcome.reason}"
            is ConnectionAttemptOutcome.Interrupted -> "原因：连接途中进程结束；下方显示最后检查点和可获取的异常堆栈。" 
            is ConnectionAttemptOutcome.NoVideo -> "原因：会话已建立，但到会话结束都没有收到 iPhone 的画面。"
        }
        return lines
    }

    /**
     * 按屏幕高度分页，每页正好拍一张。[rowsOf] 给出一行折行后占几行（界面按真实字宽算）。
     * 单行比一整页还高时独占一页（界面上那一页可以滚动），保证不丢内容。
     */
    fun paginate(lines: List<String>, rowsPerPage: Int, rowsOf: (String) -> Int): List<List<String>> {
        val capacity = rowsPerPage.coerceAtLeast(1)
        val pages = mutableListOf<List<String>>()
        var page = mutableListOf<String>()
        var used = 0
        for (line in lines) {
            val rows = rowsOf(line).coerceAtLeast(1)
            if (page.isNotEmpty() && used + rows > capacity) {
                pages += page
                page = mutableListOf()
                used = 0
            }
            page += line
            used += rows
        }
        if (page.isNotEmpty() || pages.isEmpty()) pages += page
        return pages
    }

    /**
     * 从新到旧查找最近一次 Host started，避免为一次连接读取全部历史归档。
     * 如果日志被轮转到旧文件，则将该文件从最近连接起的内容和之后的文件顺序拼接。
     * 没有 Host started 时回退到全部当前保留的日志；不在这里按内容或行数丢弃。
     */
    private fun retainedAttemptLines(context: Context): List<String> {
        val chunks = mutableListOf<List<String>>()
        for (name in SessionLogFile.REPORT_NAMES.asReversed()) {
            val file = File(context.filesDir, "logs/$name")
            if (!file.isFile) continue
            val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
            val found = lines.any { it.contains(ATTEMPT_START) }
            chunks.add(if (found) lastAttempt(lines) else lines)
            if (found) break
        }
        return chunks.asReversed().flatMap { it }
    }

    /** 摘要 + 分隔行 + 最近一次连接的完整日志；读取发生在后台线程。 */
    fun collect(context: Context, appVersion: String): List<String> {
        val details = retainedAttemptLines(context).mapNotNull(DiagnosticRedactor::redact)
        val attempt = runCatching { ConnectionAttemptRecord.load(context) }.getOrNull()
        val outcome = runCatching { ConnectionAttemptRecord.outcome(context) }.getOrNull()
        val device = "Android ${Build.VERSION.RELEASE ?: "?"} · ${Build.MODEL ?: "?"}"
        val hasCrash = CrashDiagnostics.hasCrash(context)
        val crashDetails = CrashDiagnostics.displayLines(context, attempt, outcome)
        return summary(outcome, appVersion, device, hasCrash = hasCrash) +
            crashDetails +
            "—— 最近一次连接的完整日志（${details.size} 行，仅限已保留的日志文件）——" +
            details.ifEmpty { listOf("（没有找到日志：可能还没有连接过，或日志已被清除）") }
    }

    private fun clockText(millis: Long): String =
        if (millis <= 0) "时间未知" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(millis))
}
