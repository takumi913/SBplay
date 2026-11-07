package com.shilapi.xcertplay

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最近一次连接尝试，存在磁盘上，给首页和诊断报告看（README 里的“失败可见性”缺口）。
 *
 * 为什么要有它：连接是在后台的 CarPlay 主机界面里进行的，失败原因只画在那个界面上；
 * 会话一结束，首页就退回固定的“未发现 iPhone / 未检测到 iPhone”，车主看不到卡在哪一步。
 * 主机界面关闭时还会结束整个进程，所以这条记录不能只放内存，必须写进 SharedPreferences。
 *
 * 只用事件，不用计时器（AGENTS.md §2）：
 * - 主机界面开始一次新尝试 → [started]（同一界面里的自动重试不清掉已记下的失败）；
 * - 每个连接步骤 → [progressed]，记下“停在哪一步”；
 * - 收到明确的失败状态 → [failed]；
 * - 会话真正建立 → [sessionActive]，整条记录删除；
 * - 会话正常收尾（断开、退出）→ [ended]。
 * 记录里带着写入时的进程号：仍是“进行中”但进程号已经变了，说明上一个进程在连接途中被结束，
 * 这就是“被中断”，它同样来自事件（进程换了），不是超时判断。
 *
 * 只用于显示，不参与任何连接决策：记录错了最多是一行文字不准，不会影响连接本身。
 */
internal data class ConnectionAttempt(
    val wireless: Boolean,
    val startedAtMillis: Long,
    val pid: Int,
    val ended: Boolean,
    val stage: String?,
    val reason: String?,
    val failedAtMillis: Long,
)

internal sealed class ConnectionAttemptOutcome {
    abstract val wireless: Boolean
    abstract val stage: String?
    abstract val atMillis: Long

    /** 收到过明确的失败状态。 */
    data class Failed(
        override val wireless: Boolean,
        override val stage: String?,
        val reason: String,
        override val atMillis: Long,
    ) : ConnectionAttemptOutcome()

    /** 没有失败状态，但上一个进程在连接途中就没了（被系统结束、崩溃或被强行停止）。 */
    data class Interrupted(
        override val wireless: Boolean,
        override val stage: String?,
        override val atMillis: Long,
    ) : ConnectionAttemptOutcome()
}

internal object ConnectionAttemptRecord {
    private const val PREFS = "diplay_connection_attempt"
    private const val KEY_WIRELESS = "wireless"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_PID = "pid"
    private const val KEY_ENDED = "ended"
    private const val KEY_STAGE = "stage"
    private const val KEY_REASON = "reason"
    private const val KEY_FAILED_AT = "failed_at"

    /** 首页卡片里一行放得下的长度；完整原因在诊断报告和日志里。 */
    const val HOME_REASON_MAX_CHARS = 80
    const val REDACTED_REASON = "（原因含敏感字段，已省略）"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 开始连接。[keepFailure] 为 true 时（同一主机界面里的重试）保留上次记下的失败步骤和原因，
     * 只更新传输方式和进程号；为 false 时是一次新的尝试，旧记录整个作废。
     */
    @Synchronized
    fun started(
        context: Context,
        wireless: Boolean,
        keepFailure: Boolean,
        pid: Int = android.os.Process.myPid(),
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val prefs = prefs(context)
        val editor = prefs.edit()
        if (!keepFailure || !prefs.contains(KEY_STARTED_AT)) editor.clear().putLong(KEY_STARTED_AT, nowMillis)
        // commit 而不是 apply：进程可能在下一刻就被结束，“进行中”必须已经落盘，才能在下次打开时认出中断。
        editor.putBoolean(KEY_WIRELESS, wireless)
            .putInt(KEY_PID, pid)
            .putBoolean(KEY_ENDED, false)
            .commit()
    }

    /** 记下当前步骤（已经是界面上显示的本地化文字）。步骤很多，用 apply 异步写。 */
    @Synchronized
    fun progressed(context: Context, stage: String) {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_PID)) return
        val safe = sanitize(stage) ?: return
        prefs.edit().putString(KEY_STAGE, safe).apply()
    }

    /** 收到失败状态。[stage] 是失败前最后一个正常步骤。 */
    @Synchronized
    fun failed(
        context: Context,
        stage: String?,
        reason: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_PID)) return
        val editor = prefs.edit()
            .putString(KEY_REASON, sanitize(reason) ?: REDACTED_REASON)
            .putLong(KEY_FAILED_AT, nowMillis)
        stage?.let(::sanitize)?.let { editor.putString(KEY_STAGE, it) }
        // 失败之后进程可能马上退出，这条要先落盘。
        editor.commit()
    }

    /** 会话真正建立：没有失败可报，整条记录删除。 */
    @Synchronized
    fun sessionActive(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /** 会话正常收尾（断开、退出）。保留失败原因，只标记“不是中断”。 */
    @Synchronized
    fun ended(context: Context) {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_PID)) return
        prefs.edit().putBoolean(KEY_ENDED, true).commit()
    }

    @Synchronized
    fun load(context: Context): ConnectionAttempt? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_PID)) return null
        return ConnectionAttempt(
            wireless = prefs.getBoolean(KEY_WIRELESS, false),
            startedAtMillis = prefs.getLong(KEY_STARTED_AT, 0L),
            pid = prefs.getInt(KEY_PID, 0),
            ended = prefs.getBoolean(KEY_ENDED, false),
            stage = prefs.getString(KEY_STAGE, null),
            reason = prefs.getString(KEY_REASON, null),
            failedAtMillis = prefs.getLong(KEY_FAILED_AT, 0L),
        )
    }

    fun outcome(context: Context, currentPid: Int = android.os.Process.myPid()): ConnectionAttemptOutcome? =
        resolve(load(context), currentPid)

    /**
     * 有失败原因就报失败；没有失败原因、仍是“进行中”、而写记录的进程已经不是当前进程，报中断；
     * 其它情况（正在本进程里连接、用户自己断开、从没连过）都不报。
     */
    fun resolve(attempt: ConnectionAttempt?, currentPid: Int): ConnectionAttemptOutcome? {
        if (attempt == null) return null
        attempt.reason?.let { reason ->
            return ConnectionAttemptOutcome.Failed(attempt.wireless, attempt.stage, reason, attempt.failedAtMillis)
        }
        if (!attempt.ended && attempt.pid != currentPid) {
            return ConnectionAttemptOutcome.Interrupted(attempt.wireless, attempt.stage, attempt.startedAtMillis)
        }
        return null
    }

    /** 首页圆环下那一行，以及操作卡片的标题。 */
    fun title(outcome: ConnectionAttemptOutcome): String = when (outcome) {
        is ConnectionAttemptOutcome.Failed -> "上次连接失败"
        is ConnectionAttemptOutcome.Interrupted -> "上次连接被中断"
    }

    /** 操作卡片的说明：方式 · 时间 · 停在哪一步 · 原因。 */
    fun detail(outcome: ConnectionAttemptOutcome, clock: (Long) -> String = ::clockText): String {
        val head = "${if (outcome.wireless) "无线" else "USB"} · ${clock(outcome.atMillis)}"
        val stage = outcome.stage?.let { " · 停在：$it" }.orEmpty()
        return when (outcome) {
            is ConnectionAttemptOutcome.Failed -> "$head$stage\n${shorten(outcome.reason)}"
            is ConnectionAttemptOutcome.Interrupted -> "$head$stage\n连接途中应用被结束，没有留下失败原因。"
        }
    }

    /** 诊断报告里的一段，完整原因不截断。 */
    fun report(context: Context, currentPid: Int = android.os.Process.myPid()): String {
        val attempt = load(context) ?: return "No unfinished or failed connection attempt recorded."
        val outcome = when (resolve(attempt, currentPid)) {
            is ConnectionAttemptOutcome.Failed -> "failed"
            is ConnectionAttemptOutcome.Interrupted -> "interrupted (previous process ended mid-connect)"
            null -> if (attempt.ended) "ended without a failure" else "in progress in this process"
        }
        return buildString {
            append("outcome=").append(outcome)
            append(" transport=").append(if (attempt.wireless) "wireless" else "USB")
            append(" started=").append(stampText(attempt.startedAtMillis))
            if (attempt.failedAtMillis > 0) append(" failedAt=").append(stampText(attempt.failedAtMillis))
            append(" sameProcess=").append(attempt.pid == currentPid)
            attempt.stage?.let { append("\nstage=").append(it) }
            attempt.reason?.let { append("\nreason=").append(it) }
        }
    }

    internal fun shorten(text: String): String =
        if (text.length <= HOME_REASON_MAX_CHARS) text else text.take(HOME_REASON_MAX_CHARS - 1) + "…"

    /** 换行压成空格后走统一的脱敏；含密码、SSID、设备名等字段的整行丢弃（返回 null）。 */
    internal fun sanitize(text: String): String? =
        DiagnosticRedactor.redact(text.replace('\r', ' ').replace('\n', ' ').trim())?.takeIf { it.isNotBlank() }

    private fun clockText(millis: Long): String =
        if (millis <= 0) "时间未知" else SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(millis))

    private fun stampText(millis: Long): String =
        if (millis <= 0) "unknown" else SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(millis))
}
