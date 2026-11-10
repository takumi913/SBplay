package com.shilapi.xcertplay

/**
 * “失败详情”页里的【通话麦克风诊断】：从最近一次连接的日志里，把麦克风这条链路
 * “iPhone 要没要麦克风 → 车机打没打开 → 录到的是不是静音 → 发没发给 iPhone”
 * 逐段对出来，给一句结论和下一步。
 *
 * 只读日志行、只输出文字；纯函数，便于单测。日志行本身已脱敏（只有固定词汇和计数）。
 * 依赖的日志口径：
 * - `Audio: setup ...`            每一路音频 SETUP，`mic=planned` 或 `mic=skipped reason=...`
 * - `Audio: downlink started ...` 下行首包（麦克风在这一刻才启动）
 * - `Microphone: switches/probe/effects/start/stats/summary/failure ...`
 * - `Audio: mode entered communication ...`、含 `hfp=` 的蓝牙行、`Call: ...`
 */
internal object MicrophoneDiagnosis {
    /** 整段采集的最大音量低于这个值，就当作“录到的是静音”。车上实测静音 5，正常说话几千。 */
    const val QUIET_PEAK = 300
    private const val MAX_RAW_LINES = 80

    private val CALL_TYPES = listOf("telephony", "default", "compatibility", "other")

    private class Capture(val type: String, val actualSource: String?) {
        var summary: String? = null
        val stats = mutableListOf<String>()
    }

    private class Totals(
        val seconds: Long?,
        val captureBytes: Long,
        val frames: Long?,
        val silentFrames: Long?,
        val peakMax: Long?,
        val udpSent: Long,
        val sendErrors: Long,
        val emptyEncodedFrames: Long,
    )

    fun section(lines: List<String>): List<String> {
        val setups = lines.filter { it.contains("Audio: setup ") }
        val downlinks = lines.filter { it.contains("Audio: downlink started ") }
        val failures = lines.filter { it.contains("Microphone: failure ") }
        val effects = lines.filter { it.contains("Microphone: effects ") }
        val switches = lines.filter { it.contains("Microphone: switches ") }
        val modeEntered = lines.any { it.contains("Audio: mode entered communication") }
        val hfpOn = lines.any { it.contains("hfp=true") }
        val captures = captures(lines)

        val out = mutableListOf("【通话麦克风诊断】")
        if (setups.isNotEmpty()) {
            out += "iPhone 建立的音频流（最近 ${minOf(setups.size, 12)} 路）："
            setups.takeLast(12).forEach { out += "· " + describeSetup(it) }
        }
        captures.forEachIndexed { index, capture ->
            out += "· 第 ${index + 1} 次开麦：" + describeCapture(capture, totals(capture))
        }

        val (conclusion, next) = verdict(setups, downlinks, failures, effects, switches, modeEntered, hfpOn, captures)
        out += "结论：$conclusion"
        next?.let { out += "下一步：$it" }

        val raw = lines.filter { line ->
            line.contains("Audio: setup ") || line.contains("Audio: downlink ") ||
                line.contains("Microphone: ") || line.contains("Audio: mode ") ||
                line.contains("Call: ") || line.contains("hfp=")
        }
        if (raw.isNotEmpty()) {
            val shown = raw.takeLast(MAX_RAW_LINES)
            out += "—— 麦克风相关日志（${raw.size} 行" +
                (if (raw.size > shown.size) "，只列最近 ${shown.size} 行" else "") + "）——"
            out += shown
        }
        return out
    }

    private fun verdict(
        setups: List<String>,
        downlinks: List<String>,
        failures: List<String>,
        effects: List<String>,
        switches: List<String>,
        modeEntered: Boolean,
        hfpOn: Boolean,
        captures: List<Capture>,
    ): Pair<String, String?> {
        val chosen = captures
            .filter { it.type in CALL_TYPES }
            .ifEmpty { captures }
            .maxByOrNull { totals(it).frames ?: totals(it).captureBytes }
        if (chosen != null) return captureVerdict(chosen, totals(chosen), effects, switches, modeEntered)

        failures.lastOrNull()?.let { line ->
            val stage = field(line, "stage") ?: "?"
            return failureVerdict(stage, line) to "把这一页拍下来；下面的 probe 行说明每个录音音源的结果。"
        }

        val planned = setups.filter { field(it, "mic") == "planned" }
        if (planned.isNotEmpty()) {
            val plannedTypes = planned.mapNotNull { field(it, "type") }.toSet()
            val started = downlinks.mapNotNull { field(it, "type") }.toSet()
            return if (plannedTypes.none { it in started }) {
                "iPhone 要了车机麦克风（${plannedTypes.joinToString("/")}），但这一路的下行声音一直没开始；" +
                    "麦克风按设计要等收到第一包声音才启动，所以没有启动。" to
                    "通话时注意车机喇叭有没有对方的声音；把这一页拍下来。"
            } else {
                "iPhone 要了车机麦克风，下行声音也开始了，但没有任何开麦记录（日志可能被截断，或进程中途退出）。" to
                    "再打一次，挂断后等几秒再打开本页。"
            }
        }
        if (setups.any { field(it, "reason") == "mic-disabled" }) {
            return "应用没有启用麦克风（没有录音权限），所以没有向 iPhone 声明车机麦克风。" to
                "在系统设置里给本应用录音权限，然后重新连接。"
        }
        if (setups.isNotEmpty()) {
            return "这次连接里 iPhone 从来没有向车机要过麦克风。如果期间打过微信/电话，说明这通电话没有走 CarPlay 的声音通道；" +
                "最常见的是 iPhone 还连着车机原生蓝牙电话（HFP），通话被车机自己的蓝牙电话接走。" +
                (if (hfpOn) "日志里出现过 hfp=true，印证了这一点。" else "") to
                "打电话时看车机屏幕是 CarPlay 通话界面还是车机自己的蓝牙电话界面；把这一页拍下来。"
        }
        return "这次连接的日志里没有任何音频流记录（连接没走到音频阶段，或 App 版本早于这项诊断）。" to
            "确认已装新版本，连上 CarPlay 后打一通电话，挂断后再打开本页。"
    }

    private fun captureVerdict(
        capture: Capture,
        totals: Totals,
        effects: List<String>,
        switches: List<String>,
        modeEntered: Boolean,
    ): Pair<String, String?> {
        val photo = "把这一页拍下来发给开发者。"
        if (totals.captureBytes == 0L && (totals.frames ?: 0L) == 0L) {
            return "车机麦克风打开了，但一个字节都没读到。" to photo
        }
        if (totals.udpSent == 0L) {
            return "车机录到了数据，但一个包都没发给 iPhone（编码为空 ${totals.emptyEncodedFrames} 帧）。" to photo
        }
        if (totals.sendErrors * 10 > totals.udpSent + totals.sendErrors) {
            return "车机录到了数据，但发给 iPhone 时大量失败（成功 ${totals.udpSent}，失败 ${totals.sendErrors}）。" to photo
        }
        val peak = totals.peakMax
            ?: return "车机录到了数据并发给了 iPhone（${totals.udpSent} 包），但这版日志没有音量信息，判断不了是不是静音。" to
                "装上带音量统计的版本再测一次。"
        if (peak >= QUIET_PEAK) {
            return "车机这一端正常：录到了声音（最大音量 $peak）并持续发给了 iPhone（${totals.udpSent} 包，无失败）。" +
                "对方仍听不到的话，问题在 iPhone 收到之后（解码或格式协商）。" to photo
        }
        val head = "车机麦克风录到的几乎全是静音（最大音量 $peak，正常说话应在几千以上），但照常发给了 iPhone，所以对方什么都听不到。"
        if (capture.type != "telephony") {
            return head + "这次走的是 ${capture.type} 类型，两个通话开关对它不生效；" +
                "静音来自录音输入本身（实际音源 ${capture.actualSource ?: "未知"}），可能是车机语音助手或别的程序占着真实的麦克风输入。" to photo
        }
        val effectLine = effects.lastOrNull { field(it, "type") == "telephony" }
        val effectState = when {
            effectLine == null -> "未知"
            field(effectLine, "applied") == "yes" ->
                "已启用（aec=${field(effectLine, "aec") ?: "?"} ns=${field(effectLine, "ns") ?: "?"}）"
            else -> "未启用（${field(effectLine, "reason") ?: "?"}）"
        }
        val modeSwitch = switches.lastOrNull { field(it, "type") == "telephony" }?.let { field(it, "mic_mode") }
        val modeState = when {
            modeEntered -> "已进入"
            modeSwitch == "off" -> "开关已关"
            else -> "未进入"
        }
        val nextStep = when {
            effectLine != null && field(effectLine, "applied") == "yes" ->
                "先只关回声消除再打一次：adb shell setprop debug.diplay.mic_effects 0；" +
                    "还是静音就再关通话模式：adb shell setprop debug.diplay.mic_mode 0。每次打完都看本页。"
            modeEntered ->
                "回声消除已经关了，再关通话模式打一次：adb shell setprop debug.diplay.mic_mode 0。"
            else ->
                "两个开关都已关闭仍是静音，问题在录音输入本身；$photo"
        }
        return head + "这次是 telephony 通话：回声消除/降噪$effectState，通话模式$modeState。" to nextStep
    }

    private fun failureVerdict(stage: String, line: String): String = when (stage) {
        "RECORDER_INITIALIZATION", "RECORDER_CREATION" ->
            "车机拒绝了所有录音音源，麦克风没打开；通常是车机自带的语音助手占着麦克风。"
        "SOCKET_CREATION" ->
            "录音能打开，但建立回传 iPhone 的通道失败（reason=${field(line, "reason") ?: "?"}）。"
        "ENCODER" -> "车机上没有可用的 Opus 编码器，无法给 iPhone 发声音。"
        "MIN_BUFFER" -> "车机不支持 iPhone 要求的录音格式（rate=${field(line, "rate") ?: "?"}）。"
        else -> "录音过程中出错（stage=$stage error=${field(line, "error") ?: "?"} code=${field(line, "code") ?: "?"}）。"
    }

    private fun captures(lines: List<String>): List<Capture> {
        val result = mutableListOf<Capture>()
        var current: Capture? = null
        for (line in lines) {
            when {
                line.contains("Microphone: start ") -> {
                    current = Capture(field(line, "type") ?: "?", field(line, "actualSource"))
                    result += current
                }
                line.contains("Microphone: stats ") -> current?.stats?.add(line)
                line.contains("Microphone: summary ") -> {
                    current?.summary = line
                    current = null
                }
            }
        }
        return result
    }

    private fun totals(capture: Capture): Totals {
        capture.summary?.let { line ->
            return Totals(
                seconds = long(line, "seconds"),
                captureBytes = long(line, "captureBytes") ?: 0,
                frames = long(line, "frames"),
                silentFrames = long(line, "silentFrames"),
                peakMax = long(line, "peakMax"),
                udpSent = long(line, "udpSent") ?: 0,
                sendErrors = long(line, "sendErrors") ?: 0,
                emptyEncodedFrames = long(line, "emptyEncodedFrames") ?: 0,
            )
        }
        val stats = capture.stats
        val hasLevel = stats.any { field(it, "peakMax") != null }
        return Totals(
            seconds = if (stats.isEmpty()) null else stats.size * 5L,
            captureBytes = stats.sumOf { long(it, "captureBytes") ?: 0 },
            frames = if (hasLevel) stats.sumOf { long(it, "frames") ?: 0 } else null,
            silentFrames = if (hasLevel) stats.sumOf { long(it, "silentFrames") ?: 0 } else null,
            peakMax = if (hasLevel) stats.maxOf { long(it, "peakMax") ?: 0 } else null,
            udpSent = stats.sumOf { long(it, "udpSent") ?: 0 },
            sendErrors = stats.sumOf { long(it, "sendErrors") ?: 0 },
            emptyEncodedFrames = stats.sumOf { long(it, "emptyEncodedFrames") ?: 0 },
        )
    }

    private fun describeSetup(line: String): String {
        val type = field(line, "type") ?: "?"
        val codec = field(line, "codec") ?: "?"
        val rate = field(line, "rate") ?: "?"
        val mic = if (field(line, "mic") == "planned") {
            "要车机麦克风"
        } else {
            "不带麦克风（${reasonText(field(line, "reason"))}）"
        }
        return "$type $codec ${rate}Hz：$mic"
    }

    private fun describeCapture(capture: Capture, totals: Totals): String {
        val level = if (totals.frames != null) {
            "采集 ${totals.frames} 帧，其中静音 ${totals.silentFrames} 帧，最大音量 ${totals.peakMax}"
        } else {
            "采集 ${totals.captureBytes} 字节（无音量信息）"
        }
        return "${capture.type} 实际音源 ${capture.actualSource ?: "未知"} " +
            "时长约 ${totals.seconds ?: "?"} 秒：$level；发给 iPhone ${totals.udpSent} 包，失败 ${totals.sendErrors} 包" +
            if (capture.summary == null) "（没有结束记录）" else ""
    }

    private fun reasonText(reason: String?): String = when (reason) {
        "mic-disabled" -> "应用麦克风未启用"
        "not-main-audio" -> "不是主音频通道"
        "no-input-requested" -> "iPhone 没要求麦克风"
        "no-data-port", "bad-data-port" -> "iPhone 没给回传端口"
        "no-peer-address" -> "拿不到 iPhone 地址"
        "no-stream-secret" -> "缺少加密参数"
        null -> "原因未知"
        else -> reason
    }

    private fun field(line: String, name: String): String? =
        Regex("(?:^|\\s)" + Regex.escape(name) + "=(\\S+)").find(line)?.groupValues?.get(1)

    private fun long(line: String, name: String): Long? = field(line, name)?.toLongOrNull()
}
