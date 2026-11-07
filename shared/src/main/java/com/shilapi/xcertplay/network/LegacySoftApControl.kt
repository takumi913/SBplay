package com.shilapi.xcertplay.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.util.Log
import com.shilapi.xcertplay.compat.systemServiceOrNull
import java.util.BitSet

/**
 * CS11（Android 4.4）车机热点自动开启——对齐 EasyPlay（领克版）反编译口径（LegacyHotspotController）。
 * 只沿用原车热点配置；4.4/4.4W AP 与 Wi-Fi 客户端互斥（开前关客户端、失败恢复）；
 * 反射仅需 CHANGE_WIFI_STATE（安装即授予），无 root、无 shell；受理后仍须轮询 getWifiApState 至 13。
 */
object LegacySoftApControl {
    private const val TAG = "DiPlay-LegacyAp"
    private const val AP_STATE_ENABLING = 12
    private const val AP_STATE_ENABLED = 13
    private const val WIFI_STATE_DISABLING = 0
    private const val WIFI_STATE_DISABLED = 1
    private const val WIFI_STATE_ENABLING = 2
    private const val WIFI_STATE_ENABLED = 3
    private const val CLIENT_SWITCH_TIMEOUT_NANOS = 6_000_000_000L
    private const val POLL_MILLIS = 250L

    enum class StoredSecurity { OPEN, WPA_PSK, WEP, UNSUPPORTED }
    data class StoredApConfiguration(val ssid: String?, val password: String?, val security: StoredSecurity)
    data class UsableApConfiguration(val ssid: String, val password: String?, val security: StoredSecurity)

    /** 平台是否具备 4.4 系 setWifiApEnabled 反射面（现代设备 false，不影响既有回退链）。 */
    fun supported(): Boolean = runCatching { setWifiApEnabledMethod() }.isSuccess

    private fun setWifiApEnabledMethod() = WifiManager::class.java.getMethod(
        "setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType,
    )

    // ---- 读取 ----
    fun readStoredConfiguration(context: Context): StoredApConfiguration? =
        context.systemServiceOrNull(WifiManager::class.java)?.let(::readStored)

    private fun readStored(wifi: WifiManager): StoredApConfiguration? {
        val raw = runCatching {
            WifiManager::class.java.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration
        }.getOrNull() ?: return null
        return storedConfiguration(raw)
    }

    /** 读取且通过可用性校验的原车配置（供 MANUAL 流程采用真实 SSID/口令/安全类型）。 */
    fun readUsableStored(context: Context): UsableApConfiguration? =
        usable(readStoredConfiguration(context))

    internal fun storedConfiguration(config: WifiConfiguration): StoredApConfiguration =
        StoredApConfiguration(
            ssid = unquote(config.SSID),
            password = unquote(preSharedKey(config)),
            security = storedSecurity(config.allowedKeyManagement, hasWepKey(config)),
        )

    // preSharedKey/wepKeys 在较新 SDK 有被移除风险：走反射最稳（SSID/allowedKeyManagement 直读已被本仓库使用）
    @SuppressLint("PrivateApi")
    private fun preSharedKey(config: WifiConfiguration): String? = runCatching {
        WifiConfiguration::class.java.getField("preSharedKey").get(config) as? String
    }.getOrNull()

    @SuppressLint("PrivateApi")
    private fun hasWepKey(config: WifiConfiguration): Boolean = runCatching {
        (WifiConfiguration::class.java.getField("wepKeys").get(config) as? Array<*>)
            ?.any { it is String && it.isNotEmpty() } == true
    }.getOrDefault(false)

    internal fun storedSecurity(keyManagement: BitSet?, hasWepKey: Boolean): StoredSecurity {
        if (hasWepKey) return StoredSecurity.WEP
        val km = keyManagement ?: return StoredSecurity.UNSUPPORTED
        return when {
            km.get(1) || km.get(4) -> StoredSecurity.WPA_PSK
            km.cardinality() == 0 || (km.cardinality() == 1 && km.get(0)) -> StoredSecurity.OPEN
            else -> StoredSecurity.UNSUPPORTED
        }
    }

    internal fun usable(stored: StoredApConfiguration?): UsableApConfiguration? {
        val ssid = stored?.ssid ?: return null
        if (ssid.isBlank() || '\u0000' in ssid || ssid.encodeToByteArray().size > 32) return null
        return when (stored.security) {
            StoredSecurity.OPEN -> UsableApConfiguration(ssid, null, StoredSecurity.OPEN)
            StoredSecurity.WPA_PSK -> {
                val password = stored.password ?: return null
                val passphrase = password.length in 8..63 && password.all { it.code in 32..126 }
                val rawPsk = password.length == 64 &&
                    password.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
                if (passphrase || rawPsk) UsableApConfiguration(ssid, password, StoredSecurity.WPA_PSK)
                else null
            }
            StoredSecurity.WEP, StoredSecurity.UNSUPPORTED -> null
        }
    }

    // ---- 开启 ----
    @Volatile private var clientDisabledByUs = false

    /** true = 平台已受理；调用方仍须轮询状态确认。失败路径自动恢复被关的 Wi-Fi 客户端。 */
    fun requestEnable(context: Context, log: (String) -> Unit = { Log.i(TAG, it) }): Boolean {
        val wifi = context.systemServiceOrNull(WifiManager::class.java)
        if (wifi == null) { log("legacy AP: WifiManager unavailable"); return false }
        if (!supported()) { log("legacy AP: setWifiApEnabled unavailable"); return false }
        return try {
            requestEnable(context, wifi, log)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            restoreWifiClientIfNeeded(context, log)
            false
        } catch (error: Throwable) {
            log("legacy AP: enable failed ${error.javaClass.simpleName}: ${error.message}")
            restoreWifiClientIfNeeded(context, log)
            false
        }
    }

    private fun requestEnable(context: Context, wifi: WifiManager, log: (String) -> Unit): Boolean {
        val initial = apState(wifi)
        if (initial == AP_STATE_ENABLED || initial == AP_STATE_ENABLING) {
            log("legacy AP: already up (state=$initial)")
            return true
        }
        val clientState = wifiState(wifi)
        if (clientState == WIFI_STATE_ENABLED || clientState == WIFI_STATE_ENABLING) {
            log("legacy AP: switching the Wi-Fi client off (softAP needs the radio on 4.4)")
            clientDisabledByUs = true
            if (runCatching { wifi.setWifiEnabled(false) }.getOrDefault(false) != true) {
                log("legacy AP: the platform refused to switch Wi-Fi off")
                clientDisabledByUs = false
                return false
            }
            val deadline = System.nanoTime() + CLIENT_SWITCH_TIMEOUT_NANOS
            while (System.nanoTime() < deadline) {
                val state = wifiState(wifi)
                if (state != WIFI_STATE_DISABLING && state != WIFI_STATE_ENABLING &&
                    state != WIFI_STATE_ENABLED
                ) break
                Thread.sleep(POLL_MILLIS)
            }
            log("legacy AP: Wi-Fi client state now ${wifiState(wifi)} (1=disabled)")
        }
        val raw = runCatching {
            WifiManager::class.java.getMethod("getWifiApConfiguration").invoke(wifi) as? WifiConfiguration
        }.getOrNull()
        val usableConfiguration = usable(raw?.let(::storedConfiguration))
        if (raw == null || usableConfiguration == null) {
            log("legacy AP: saved hotspot configuration missing or unsupported; leaving it to the car settings")
            restoreWifiClientIfNeeded(context, log)
            return false
        }
        val accepted = runCatching {
            setWifiApEnabledMethod().invoke(wifi, raw, true) as? Boolean
        }.getOrElse { error ->
            log("legacy AP: setWifiApEnabled rejected ${error.javaClass.simpleName}: ${error.message}")
            restoreWifiClientIfNeeded(context, log)
            return false
        }
        if (accepted != true) {
            log("legacy AP: platform declined to start the hotspot")
            restoreWifiClientIfNeeded(context, log)
            return false
        }
        log("legacy AP: started saved hotspot ssid='${usableConfiguration.ssid}' security=${usableConfiguration.security}")
        return true
    }

    /** 开启最终未成功时恢复被我们关掉的 Wi-Fi 客户端（AP 已起则保持现状）。 */
    fun restoreWifiClientIfNeeded(context: Context, log: (String) -> Unit = { Log.i(TAG, it) }) {
        if (!clientDisabledByUs) return
        clientDisabledByUs = false
        val wifi = context.systemServiceOrNull(WifiManager::class.java) ?: return
        val state = apState(wifi)
        if (state == AP_STATE_ENABLED || state == AP_STATE_ENABLING) {
            log("legacy AP: hotspot is up; keeping the Wi-Fi client off")
            return
        }
        val restored = runCatching { wifi.setWifiEnabled(true) }.getOrDefault(false)
        log("legacy AP: Wi-Fi client restore requested success=$restored")
    }

    fun apState(context: Context): Int? = context.systemServiceOrNull(WifiManager::class.java)?.let(::apState)

    private fun apState(wifi: WifiManager): Int? = runCatching {
        WifiManager::class.java.getMethod("getWifiApState").invoke(wifi) as? Int
    }.getOrNull()

    private fun wifiState(wifi: WifiManager): Int = runCatching { wifi.wifiState }.getOrDefault(-1)

    private fun unquote(value: String?): String? {
        if (value == null) return null
        return if (value.length >= 2 && value.first() == '"' && value.last() == '"')
            value.substring(1, value.length - 1) else value
    }
}
