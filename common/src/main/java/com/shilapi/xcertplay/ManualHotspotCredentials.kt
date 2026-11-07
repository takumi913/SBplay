package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.LegacySoftApControl
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/**
 * Selects one authoritative credential pair for the CS11's existing car hotspot.
 *
 * The settings screen and the host must use the same selection: firmware AP
 * settings first; saved manual credentials only if firmware settings are absent.
 * No credentials are logged or written into diagnostic checkpoints.
 */
internal object ManualHotspotCredentials {
    enum class Source { CAR_FIRMWARE, SAVED }
    enum class Issue { FIRMWARE_UNSUPPORTED, MISSING_SAVED, SAVED_SECURITY_MISMATCH }

    sealed class Result {
        data class Ready(
            val ssid: String,
            val passphrase: String,
            val security: ManualHotspotSecurity,
            val source: Source,
        ) : Result()
        data class Unavailable(val issue: Issue) : Result()
    }

    fun resolve(context: Context): Result = choose(
        firmware = runCatching { LegacySoftApControl.readUsableStored(context) }.getOrNull(),
        savedSsid = AirPlayPersistence.loadManualHotspotSsid(context),
        savedPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(context),
        savedSecurity = AirPlayPersistence.loadManualHotspotSecurity(context),
    )

    /** Pure selector so firmware-vs-saved behavior is covered without a CS11 runtime. */
    fun choose(
        firmware: LegacySoftApControl.UsableApConfiguration?,
        savedSsid: String,
        savedPassphrase: String,
        savedSecurity: ManualHotspotSecurity,
    ): Result {
        if (firmware != null) {
            val passphrase = firmware.password.orEmpty()
            // A raw 64-digit WPA PSK is accepted by Android's AP driver, but it is
            // not a shareable 8..63-character WPA passphrase for CarPlay handoff.
            if (ManualHotspotValidation.error(firmware.ssid, passphrase) != null) {
                return Result.Unavailable(Issue.FIRMWARE_UNSUPPORTED)
            }
            val security = when (firmware.security) {
                LegacySoftApControl.StoredSecurity.OPEN ->
                    if (passphrase.isEmpty()) ManualHotspotSecurity.OPEN else null
                LegacySoftApControl.StoredSecurity.WPA_PSK ->
                    if (passphrase.isNotEmpty()) ManualHotspotSecurity.WPA2 else null
                else -> null
            } ?: return Result.Unavailable(Issue.FIRMWARE_UNSUPPORTED)
            return Result.Ready(firmware.ssid, passphrase, security, Source.CAR_FIRMWARE)
        }
        if (ManualHotspotValidation.error(savedSsid, savedPassphrase) != null) {
            return Result.Unavailable(Issue.MISSING_SAVED)
        }
        if ((savedSecurity == ManualHotspotSecurity.OPEN) != savedPassphrase.isEmpty()) {
            return Result.Unavailable(Issue.SAVED_SECURITY_MISMATCH)
        }
        return Result.Ready(savedSsid, savedPassphrase, savedSecurity, Source.SAVED)
    }

    fun messageResource(issue: Issue): Int = when (issue) {
        Issue.FIRMWARE_UNSUPPORTED -> R.string.manual_hotspot_firmware_credentials_unsupported
        Issue.MISSING_SAVED -> R.string.manual_hotspot_missing_credentials
        Issue.SAVED_SECURITY_MISMATCH -> R.string.manual_hotspot_saved_security_mismatch
    }
}
