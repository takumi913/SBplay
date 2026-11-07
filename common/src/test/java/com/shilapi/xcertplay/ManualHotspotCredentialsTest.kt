package com.shilapi.xcertplay

import com.shilapi.xcertplay.network.LegacySoftApControl
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualHotspotCredentialsTest {
    private fun firmware(
        ssid: String = "CS11",
        password: String? = "12345678",
        security: LegacySoftApControl.StoredSecurity = LegacySoftApControl.StoredSecurity.WPA_PSK,
    ) = LegacySoftApControl.UsableApConfiguration(ssid, password, security)

    private fun choose(
        firmware: LegacySoftApControl.UsableApConfiguration?,
        savedSsid: String = "",
        savedPassphrase: String = "",
        savedSecurity: ManualHotspotSecurity = ManualHotspotSecurity.OPEN,
    ) = ManualHotspotCredentials.choose(firmware, savedSsid, savedPassphrase, savedSecurity)

    @Test fun validFirmwareHotspotOverridesEmptyOrStaleSavedValues() {
        val ready = choose(firmware(), savedSsid = "Old AP") as ManualHotspotCredentials.Result.Ready
        assertEquals("CS11", ready.ssid)
        assertEquals("12345678", ready.passphrase)
        assertEquals(ManualHotspotSecurity.WPA2, ready.security)
        assertEquals(ManualHotspotCredentials.Source.CAR_FIRMWARE, ready.source)
    }

    @Test fun openFirmwareApUsesAnEmptyPasswordAndOpenSecurity() {
        val ready = choose(firmware(password = null,
            security = LegacySoftApControl.StoredSecurity.OPEN)) as ManualHotspotCredentials.Result.Ready
        assertEquals("", ready.passphrase)
        assertEquals(ManualHotspotSecurity.OPEN, ready.security)
    }

    @Test fun noReadableFirmwareFallsBackToSavedCredentials() {
        val ready = choose(null, savedSsid = "Manual AP", savedPassphrase = "abcdefgh",
            savedSecurity = ManualHotspotSecurity.WPA2) as ManualHotspotCredentials.Result.Ready
        assertEquals("Manual AP", ready.ssid)
        assertEquals("abcdefgh", ready.passphrase)
        assertEquals(ManualHotspotCredentials.Source.SAVED, ready.source)
    }

    @Test fun missingBothSourcesIsARecoverableFailure() {
        assertEquals(ManualHotspotCredentials.Result.Unavailable(ManualHotspotCredentials.Issue.MISSING_SAVED),
            choose(null))
    }

    @Test fun raw64DigitFirmwareKeyIsRejectedBeforeRuntimeConfig() {
        val result = choose(firmware(password = "0123456789abcdef".repeat(4)))
        assertEquals(ManualHotspotCredentials.Result.Unavailable(ManualHotspotCredentials.Issue.FIRMWARE_UNSUPPORTED),
            result)
    }

    @Test fun conflictingSavedSecurityIsRejected() {
        val result = choose(null, "Saved AP", "12345678", ManualHotspotSecurity.OPEN)
        assertEquals(ManualHotspotCredentials.Result.Unavailable(ManualHotspotCredentials.Issue.SAVED_SECURITY_MISMATCH),
            result)
    }

    @Test fun invalidSavedSsidOrShortPasswordNeverReachesRuntimeConfig() {
        assertTrue(choose(null, "  ", "12345678", ManualHotspotSecurity.WPA2)
            is ManualHotspotCredentials.Result.Unavailable)
        assertTrue(choose(null, "Saved AP", "123", ManualHotspotSecurity.WPA2)
            is ManualHotspotCredentials.Result.Unavailable)
    }
}
