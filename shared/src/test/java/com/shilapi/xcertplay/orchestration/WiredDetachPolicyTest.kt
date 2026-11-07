package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.orchestration.WiredDetachPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class WiredDetachPolicyTest {
    private val node = "/dev/bus/usb/001/005"

    private fun decide(
        detached: String? = node,
        wired: String? = node,
        sessionPhase: Boolean = true,
        teardownExpected: Boolean = false,
        closed: Boolean = false,
    ) = WiredDetachPolicy.decide(detached, wired, sessionPhase, teardownExpected, closed)

    @Test fun theNodeInUseLeavingAnOpenSessionEndsIt() {
        assertEquals(Decision.END_SESSION, decide())
    }

    @Test fun reenumerationBeforeTheDataPathsIsANormalStep() {
        // The CarPlay configuration switch and the soft replug both detach and re-attach the phone.
        assertEquals(Decision.IGNORE_NOT_IN_SESSION, decide(sessionPhase = false))
        assertEquals(Decision.IGNORE_NOT_IN_SESSION, decide(wired = null, sessionPhase = false))
    }

    @Test fun ourOwnTeardownIsNotACablePull() {
        assertEquals(Decision.IGNORE_TEARDOWN, decide(teardownExpected = true))
    }

    @Test fun aClosedControllerIgnoresEverything() {
        assertEquals(Decision.IGNORE_CLOSED, decide(closed = true, teardownExpected = true))
    }

    @Test fun otherDevicesAreIgnored() {
        assertEquals(Decision.IGNORE_OTHER_DEVICE, decide(detached = "/dev/bus/usb/001/007"))
        assertEquals(Decision.IGNORE_OTHER_DEVICE, decide(detached = null))
        assertEquals(Decision.IGNORE_OTHER_DEVICE, decide(detached = ""))
        assertEquals(Decision.IGNORE_OTHER_DEVICE, decide(wired = null))
    }
}
