package com.shilapi.xcertplay

import com.shilapi.xcertplay.ConnectionModePolicy.TabClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionModePolicyTest {
    @Test fun withoutASessionTheSavedModeIsShown() {
        assertTrue(ConnectionModePolicy.displayedWireless(savedWireless = true, sessionWireless = null))
        assertFalse(ConnectionModePolicy.displayedWireless(savedWireless = false, sessionWireless = null))
    }

    @Test fun aRunningSessionWinsOverTheSavedMode() {
        // e.g. a USB plug-in switched the host to wired while the saved value still said wireless
        assertFalse(ConnectionModePolicy.displayedWireless(savedWireless = true, sessionWireless = false))
        assertTrue(ConnectionModePolicy.displayedWireless(savedWireless = false, sessionWireless = true))
    }

    @Test fun tabClicksSaveOnlyWhenNoSessionRuns() {
        assertEquals(TabClick.SWITCH_AND_SAVE,
            ConnectionModePolicy.onTabClick(currentWireless = true, clickedWireless = false, hasSession = false))
        assertEquals(TabClick.BLOCKED_BY_SESSION,
            ConnectionModePolicy.onTabClick(currentWireless = true, clickedWireless = false, hasSession = true))
        assertEquals(TabClick.UNCHANGED,
            ConnectionModePolicy.onTabClick(currentWireless = false, clickedWireless = false, hasSession = true))
        assertEquals(TabClick.UNCHANGED,
            ConnectionModePolicy.onTabClick(currentWireless = true, clickedWireless = true, hasSession = false))
    }

    @Test fun connectNeverRewritesTheModeUnderASessionOfTheOtherTransport() {
        assertTrue(ConnectionModePolicy.maySaveOnConnect(requestedWireless = true, hasSession = false, sessionWireless = null))
        assertTrue(ConnectionModePolicy.maySaveOnConnect(requestedWireless = false, hasSession = true, sessionWireless = false))
        assertFalse(ConnectionModePolicy.maySaveOnConnect(requestedWireless = true, hasSession = true, sessionWireless = false))
        // A session whose transport is not published yet is treated as "other": the open path saves after stop.
        assertFalse(ConnectionModePolicy.maySaveOnConnect(requestedWireless = true, hasSession = true, sessionWireless = null))
    }

    @Test fun reportLabelsTheSessionTransportSeparately() {
        assertEquals("none", ConnectionModePolicy.sessionTransportLabel(hasSession = false, sessionWireless = true))
        assertEquals("unknown (starting)", ConnectionModePolicy.sessionTransportLabel(hasSession = true, sessionWireless = null))
        assertEquals("wireless", ConnectionModePolicy.sessionTransportLabel(hasSession = true, sessionWireless = true))
        assertEquals("USB", ConnectionModePolicy.sessionTransportLabel(hasSession = true, sessionWireless = false))
    }
}
