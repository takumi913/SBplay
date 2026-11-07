package com.shilapi.xcertplay

/**
 * “有线还是无线”只有一个答案（README 里的“模式收敛”缺口）。
 *
 * 以前有三份各自为政的状态：首页标签（点了不保存）、保存的模式（自动连接、蓝牙唤起、热点自动开启、
 * 诊断报告都读它）、CarPlay 主机界面自己的变量（每次回到前台从保存的模式重读）。
 * 结果是：标签停在 USB，开机自动连接却走无线；报告写 `Connection: wireless`，日志却是 `transport=wired`。
 *
 * 现在的规则：
 * - 有会话时，以会话实际的传输方式为准，首页标签跟着它走；此时不允许切换标签，也绝不改保存的模式——
 *   主机界面每次回到前台都会重读保存的模式，会话中途改它会让下一次自动重试悄悄换成另一种连接方式。
 * - 没有会话时，以保存的模式为准，点标签就立即保存，所有读保存模式的地方都和看到的标签一致。
 *
 * 纯函数，只决定“显示哪个、能不能切、要不要保存”，不碰任何连接流程。
 */
internal object ConnectionModePolicy {
    enum class TabClick {
        /** 点的就是当前标签。 */
        UNCHANGED,

        /** 没有会话：切换标签并保存为新的连接方式。 */
        SWITCH_AND_SAVE,

        /** 有会话：不切换、不保存，提示先断开。 */
        BLOCKED_BY_SESSION,
    }

    /** 首页应显示的传输方式：有会话时跟会话，否则跟保存的模式。 */
    fun displayedWireless(savedWireless: Boolean, sessionWireless: Boolean?): Boolean =
        sessionWireless ?: savedWireless

    fun onTabClick(currentWireless: Boolean, clickedWireless: Boolean, hasSession: Boolean): TabClick = when {
        clickedWireless == currentWireless -> TabClick.UNCHANGED
        hasSession -> TabClick.BLOCKED_BY_SESSION
        else -> TabClick.SWITCH_AND_SAVE
    }

    /** connect() 一开始就能保存模式的条件：没有会话，或者要连的就是会话正在用的方式。 */
    fun maySaveOnConnect(requestedWireless: Boolean, hasSession: Boolean, sessionWireless: Boolean?): Boolean =
        !hasSession || sessionWireless == requestedWireless

    /** 诊断报告里的会话传输方式。 */
    fun sessionTransportLabel(hasSession: Boolean, sessionWireless: Boolean?): String = when {
        !hasSession -> "none"
        sessionWireless == null -> "unknown (starting)"
        sessionWireless -> "wireless"
        else -> "USB"
    }
}
