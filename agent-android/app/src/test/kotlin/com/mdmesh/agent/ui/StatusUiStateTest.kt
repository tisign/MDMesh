package com.mdmesh.agent.ui

import com.mdmesh.agent.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusUiStateTest {

    @Suppress("LongParameterList")
    private fun state(
        deviceOwner: Boolean = true,
        locked: Boolean = false,
        configured: Boolean = true,
        deviceId: String? = "dev_1",
        loaded: Boolean = true,
        error: String? = null,
        returning: Boolean = false,
        failure: String? = null,
        saved: Boolean = false,
    ) = StatusUiState.of(
        deviceOwner, locked, configured, deviceId, loaded, error, error?.let { 42L }, "https://mdm", returning, failure,
        kioskSaved = saved,
    )

    @Test fun `with no kiosk saved Return to kiosk is offered exactly as before`() {
        // Device Owner, not locked, a configured kiosk: the only case it shows.
        for (owner in listOf(true, false)) for (locked in listOf(true, false)) for (configured in listOf(true, false)) {
            val ui = state(deviceOwner = owner, locked = locked, configured = configured).kiosk
            assertEquals("$owner $locked $configured", owner && !locked && configured, ui.returnVisible)
            assertEquals(ui.returnVisible, ui.returnEnabled)
            assertFalse("asks first", ui.resumes)
        }
    }

    @Test fun `while a kiosk is saved there is always a way back to it`() {
        // The status screen in front of a saved kiosk is by definition outside the kiosk's own screens. Lock task on
        // (this screen was the locked one) used to hide the button, and an unconfigured (custom) kiosk never had it.
        for (locked in listOf(true, false)) for (configured in listOf(true, false)) {
            val ui = state(locked = locked, configured = configured, saved = true).kiosk
            assertTrue("$locked $configured", ui.returnVisible && ui.returnEnabled)
            assertTrue("straight back, nothing to confirm", ui.resumes)
        }
        assertEquals(R.string.kiosk_resume_action, state(locked = true, saved = true).kiosk.returnLabel)
        // Lock task dropped out from under the kiosk: it is a return, and the card says what is wrong.
        val dropped = state(locked = false, saved = true).kiosk
        assertEquals(R.string.kiosk_return_action, dropped.returnLabel)
        assertEquals(R.string.status_kiosk_body_unlocked, dropped.body)
        assertFalse(dropped.active)
        assertEquals(R.string.status_kiosk_body_locked, state(locked = true, saved = true).kiosk.body)
    }

    @Test fun `only a Device Owner is offered the way back`() {
        val ui = state(deviceOwner = false, locked = true, saved = true).kiosk
        assertFalse(ui.returnVisible)
        assertFalse(ui.resumes)
    }

    @Test fun `while returning the button is disabled and says Returning`() {
        val ui = state(returning = true).kiosk
        assertTrue(ui.returnVisible)
        assertFalse(ui.returnEnabled)
        assertTrue(ui.returning)
        assertEquals(R.string.kiosk_return_busy, ui.returnLabel)
        assertEquals(R.string.kiosk_return_action, state().kiosk.returnLabel)
        assertEquals(R.string.kiosk_return_busy, state(returning = true, locked = true, saved = true).kiosk.returnLabel)
    }

    @Test fun `kiosk pill and text`() {
        val locked = state(locked = true).kiosk
        assertTrue(locked.active)
        assertEquals(R.string.status_kiosk_on, locked.pill)
        assertEquals(R.string.status_kiosk_body_locked, locked.body)
        assertEquals(R.string.status_kiosk_body_ready, state().kiosk.body)
        val none = state(configured = false).kiosk
        assertFalse(none.active)
        assertEquals(R.string.status_kiosk_off, none.pill)
        assertEquals(R.string.status_kiosk_body_off, none.body)
    }

    @Test fun `a return failure shows only while Return is offered`() {
        assertEquals("offline", state(failure = "offline").returnFailure)
        assertNull(state(failure = "offline", locked = true).returnFailure)
        assertEquals("offline", state(failure = "offline", locked = true, saved = true).returnFailure)
        assertNull(state(failure = "offline", deviceOwner = false).returnFailure)
    }

    @Test fun `device id line`() {
        assertEquals(StatusUiState.DeviceIdUi.Loading, state(loaded = false).deviceId)
        assertEquals(StatusUiState.DeviceIdUi.Known("dev_1"), state().deviceId)
        assertEquals(StatusUiState.DeviceIdUi.Enrolling(null, null), state(deviceId = null).deviceId)
        assertEquals(StatusUiState.DeviceIdUi.Enrolling(null, null), state(deviceId = " ").deviceId)
        val failing = state(deviceId = null, error = "timeout").deviceId
        assertEquals(StatusUiState.DeviceIdUi.Enrolling("timeout", 42L), failing)
        // Enrolled: an old sync error doesn't show as an enrolment problem.
        assertEquals(StatusUiState.DeviceIdUi.Known("dev_1"), state(error = "timeout").deviceId)
    }
}
