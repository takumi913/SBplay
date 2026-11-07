package com.shilapi.xcertplay.orchestration

/**
 * 收到 `ACTION_USB_DEVICE_DETACHED` 时，有线会话该怎么办（README 里的“USB 拔出广播”缺口）。
 *
 * 以前没有监听拔出：拔线后只能等 USB 读取自己出错。API 19 的 `requestWait()` 没有超时，
 * 读取能不能、多快醒来全看内核；而且日志里没有一行明确写“线被拔了”。
 *
 * 规则（只用事件，不用计时器，AGENTS.md §2）：
 * - 只有数据通道已打开或控制通道运行中（[sessionPhase]），拔出的又正是正在用的那个设备节点，
 *   才结束会话。此时节点已经没了，读写一定会失败，提前按失败处理只是更快、原因更明确；
 *   之后照旧走宿主的自动重试，等车主重新插上。
 * - 更早的阶段不处理：iPhone 切到 CarPlay 配置时会自己断开再重新接入（重新枚举），
 *   软件“重插”也一样，那时拔出是正常步骤。
 * - 我们自己拆会话时会主动让 iPhone 重新枚举（[teardownExpected]），这种拔出也不处理，
 *   否则车主点“断开连接”后首页会误报“上次连接失败”。
 * - 别的 USB 设备（U 盘、另一个节点）不处理。
 *
 * 纯函数，方便单测；真正的接收和日志在 CarPlayController.onUsbDetached。
 */
internal object WiredDetachPolicy {
    enum class Decision {
        /** 拔出的是正在用的 iPhone：按会话失败处理。 */
        END_SESSION,

        /** 控制器已关闭。 */
        IGNORE_CLOSED,

        /** 我们自己拆会话时触发的重新枚举。 */
        IGNORE_TEARDOWN,

        /** 还没打开数据通道（含重新枚举阶段），拔出可能是正常步骤。 */
        IGNORE_NOT_IN_SESSION,

        /** 拔出的不是正在用的设备节点。 */
        IGNORE_OTHER_DEVICE,
    }

    fun decide(
        detachedDeviceName: String?,
        wiredDeviceName: String?,
        sessionPhase: Boolean,
        teardownExpected: Boolean,
        closed: Boolean,
    ): Decision = when {
        closed -> Decision.IGNORE_CLOSED
        teardownExpected -> Decision.IGNORE_TEARDOWN
        !sessionPhase -> Decision.IGNORE_NOT_IN_SESSION
        detachedDeviceName.isNullOrEmpty() || detachedDeviceName != wiredDeviceName -> Decision.IGNORE_OTHER_DEVICE
        else -> Decision.END_SESSION
    }
}
