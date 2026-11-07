package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * “失败详情”页的内容：一屏一页、能直接拍照的失败摘要和关键日志。
 *
 * 以前要看失败原因，得先“保存诊断报告”再“查看报告”，报告是几份日志从旧到新拼成的一长段，
 * 这次失败在最末尾，中间夹着大量主题、画面表面之类的行。这里只取最近一次连接（最后一个
 * `Host started` 起）的关键行，摘要放最前面。
 *
 * 只读、只显示：日志行来自已经脱敏的会话日志（SessionLogFile 写入前就过了 DiagnosticRedactor），
 * 显示前再脱敏一次；摘要来自 ConnectionAttemptRecord，它在写入时已脱敏。不上传、不改任何连接状态。
 * 判定逻辑都是纯函数，方便单测；[collect] 负责读文件。
 */
internal object FailureDetail {
    /** 最多显示的关键行数：再多就拍不完了，完整内容仍在诊断报告里。 */
    const val MAX_KEY_LINES = 120

    /** 关键行：连接步骤、错误、失败、重试、会话起止。 */
    private val KEEP = listOf(
        "STEP ", "ERROR", "CONNECTION_DIAGNOSTIC", "Exception", "Error", "error=",
        "failed", "Failed", "failure", "失败", "timed out", "timeout", "denied", "refused", "rejected",
        "unplugged", "retrying", "Reconnecting", "recovery stopped", "Host started",
        "Starting CarPlay controller", "AirPlay session", "DiPlay log started",
    )

    /** 明确无关的高频行，即使含有上面的词也不要。 */
    private val NOISE = listOf(
        "THEME_DIAGNOSTIC", "Texture surface", "attach surface", "Display detected", "codec capabilities",
    )

    /** 一次连接从哪里开始：主机界面实例启动时写的这一行。 */
    private const val ATTEMPT_START = "Host started"
    private const val LOG_START = "DiPlay log started"

    fun isKeyLine(line: String): Boolean =
        line.isNotBlank() && NOISE.none { line.contains(it) } && KEEP.any { line.contains(it) }

    /**
     * 最近一次连接的所有行：从最后一个 `Host started` 往前找到它所在日志的开头一行，取到末尾。
     * 没有 `Host started`（例如从没连过）就取全部。
     */
    fun lastAttempt(lines: List<String>): List<String> {
        val start = lines.indexOfLast { it.contains(ATTEMPT_START) }
        if (start < 0) return lines
        val header = (start downTo 0).firstOrNull { lines[it].contains(LOG_START) } ?: start
        return lines.subList(header, lines.size)
    }

    /** 最近一次连接里的关键行，超过上限时保留最后的部分（失败总在后面）。 */
    fun keyLines(lines: List<String>, max: Int = MAX_KEY_LINES): List<String> {
        val kept = lastAttempt(lines).filter(::isKeyLine)
        return if (kept.size <= max) kept else kept.takeLast(max)
    }

    /** 页首的摘要。[outcome] 为 null 表示没有记录到失败。 */
    fun summary(
        outcome: ConnectionAttemptOutcome?,
        appVersion: String,
        device: String,
        clock: (Long) -> String = ::clockText,
    ): List<String> {
        val lines = mutableListOf("SBplay $appVersion · $device")
        if (outcome == null) {
            lines += "【没有记录到失败】下面是最近一次连接的关键日志。"
            return lines
        }
        lines += "【${ConnectionAttemptRecord.title(outcome)}】"
        lines += "方式：${if (outcome.wireless) "无线" else "USB"} · 时间：${clock(outcome.atMillis)}"
        outcome.stage?.let { lines += "停在：$it" }
        lines += when (outcome) {
            is ConnectionAttemptOutcome.Failed -> "原因：${outcome.reason}"
            is ConnectionAttemptOutcome.Interrupted -> "原因：连接途中应用被结束，没有留下失败原因。"
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

    /** 摘要 + 分隔行 + 关键行，交给界面分页。读文件，放在后台线程里调用。 */
    fun collect(context: Context, appVersion: String): List<String> {
        val logLines = SessionLogFile.REPORT_NAMES.flatMap { name ->
            val file = File(context.filesDir, "logs/$name")
            if (file.isFile) runCatching { file.readLines() }.getOrDefault(emptyList()) else emptyList()
        }
        val key = keyLines(logLines).mapNotNull(DiagnosticRedactor::redact)
        val outcome = runCatching { ConnectionAttemptRecord.outcome(context) }.getOrNull()
        val device = "Android ${Build.VERSION.RELEASE ?: "?"} · ${Build.MODEL ?: "?"}"
        return summary(outcome, appVersion, device) +
            "—— 最近一次连接的关键日志（${key.size} 行，完整内容见诊断报告）——" +
            key.ifEmpty { listOf("（没有找到日志：可能还没有连接过，或日志已被清除）") }
    }

    private fun clockText(millis: Long): String =
        if (millis <= 0) "时间未知" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(millis))
}
