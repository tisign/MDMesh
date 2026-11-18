package com.mdmesh.agent.stress

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import com.mdmesh.agent.KioskExitActivity
import com.mdmesh.agent.R
import com.mdmesh.core.kiosk.AndroidKioskHomeSwitch
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.telemetry.EventLog
import com.mdmesh.kiosk.ExitPinGate
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController

/**
 * The exit PIN prompt a kiosk app asks for ([KioskExitActivity]): the launcher's own gate, opened from the app. The
 * request itself opens nothing; the PIN, its lockout and the kiosk's "no exit on this device" all still decide.
 */
class KioskExitRequestTest : KioskHarness() {

    private var request: ActivityController<KioskExitActivity>? = null
    private val prompt: KioskExitActivity get() = request!!.get()

    /** A kiosk app starts the prompt while [kiosk] is on the device. */
    private fun ask(kiosk: KioskApplyPayload?, intent: Intent = Intent(KioskExitActivity.ACTION)): KioskExitActivity {
        saveKiosk(kiosk)
        request = Robolectric.buildActivity(KioskExitActivity::class.java, intent).setup()
        settle()
        return prompt
    }

    @After fun closePrompt() {
        runCatching { request?.pause()?.stop()?.destroy() }
    }

    private fun exitEvents(): List<String> {
        val log = EventLog(app)
        val all = log.drain()
        log.restore(all)
        return all.filter { it.type == "kioskExit" }.map { it.detail.orEmpty() }
    }

    private fun tap(label: Int) {
        button(showingDialog()!!.window!!.decorView, s(label))!!.performClick()
        settle()
    }

    @Test fun theRightPinExitsTheKioskAndSaysWhereFrom() {
        ask(single)
        assertNotNull("the PIN prompt", pinDialog())
        submitPin(pin)
        assertNull(storedKiosk())
        assertEquals(listOf(KioskExitActivity.EXIT_EVENT), exitEvents())
        assertTrue(prompt.isFinishing)
        assertEquals(0, pinFailures())
    }

    @Test fun cancelGoesBackToTheAppAndTheKioskStays() {
        ask(single)
        tap(R.string.action_cancel)
        assertTrue(prompt.isFinishing)
        assertEquals(single, storedKiosk())
        assertTrue(exitEvents().isEmpty())
    }

    @Test fun aWrongPinStaysOnThePromptAndCounts() {
        ask(single)
        submitPin("0000")
        assertNotNull("still asking", pinDialog())
        assertFalse(prompt.isFinishing)
        assertEquals(1, pinFailures())
        assertEquals(single, storedKiosk())
        // The same prompt takes the right PIN next.
        submitPin(pin)
        assertNull(storedKiosk())
    }

    /** Extras, data, another action: nothing a caller sends is read, let alone trusted. */
    @Test fun nothingInTheRequestOpensTheExit() {
        val forged = Intent("com.example.FORGED", Uri.parse("mdmesh://exit?pin=$pin"))
            .putExtra("pin", pin).putExtra("password", pin).putExtra("authorized", true).putExtra("exitDialog", "pin")
        ask(single, forged)
        assertNotNull("it asks all the same", pinDialog())
        assertEquals(single, storedKiosk())
        assertTrue(exitEvents().isEmpty())
        assertEquals("nothing was tried on the caller's behalf", 0, pinFailures())
    }

    @Test fun aKioskWithNoExitOnTheDeviceGetsNoPrompt() {
        val noExit = listOf(single.copy(exitMode = "remote"), single.copy(password = null), single.copy(password = " "))
        for (kiosk in noExit) {
            ask(kiosk)
            assertNull("${kiosk.exitMode}/${kiosk.password}", showingDialog())
            assertTrue(prompt.isFinishing)
            assertEquals(kiosk, storedKiosk())
            closePrompt()
        }
    }

    @Test fun outsideAKioskItClosesAtOnce() {
        ask(null)
        assertNull(showingDialog())
        assertTrue(prompt.isFinishing)
    }

    /** Wrong PINs here are the launcher's wrong PINs: one count, one lockout, whichever prompt they are typed into. */
    @Test fun theLockoutIsTheLaunchersOwn() {
        ask(single)
        repeat(ExitPinGate.FIRST_ROUND_TRIES) { if (pinDialog() != null) submitPin("0000") }
        assertEquals(ExitPinGate.FIRST_ROUND_TRIES, pinFailures())
        assertNotNull("Exit locked", lockedDialog())
        assertEquals(1, events("kioskPinLockout"))
        tap(R.string.action_ok)
        assertTrue(prompt.isFinishing)
        closePrompt()

        // Asked again: locked, with no field to type the right PIN into.
        ask(single)
        assertNull(pinDialog())
        assertNotNull(lockedDialog())
        closePrompt()

        // And the launcher's own exit is locked by them.
        launch(grid)
        tapExitKiosk()
        assertNull(pinDialog())
        assertNotNull(lockedDialog())
        assertEquals("still one lockout reported", 1, events("kioskPinLockout"))
    }

    /** As on the launcher: a new PIN makes it another kiosk, so the prompt for the old one closes. */
    @Test fun aPinChangedWhileThePromptIsOpenClosesItAndTheNewPinIsTheOneThatCounts() {
        ask(single)
        saveKiosk(single.copy(password = "7391"))
        settle()
        assertNull("the prompt for the old PIN closes", showingDialog())
        assertTrue(prompt.isFinishing)
        closePrompt()

        ask(single.copy(password = "7391"))
        submitPin(pin)
        assertNotNull("the old PIN is wrong now", storedKiosk())
        assertEquals(1, pinFailures())
        submitPin("7391")
        assertNull(storedKiosk())
    }

    @Test fun anExitSwitchedToConsoleOnlyWhileThePromptIsOpenClosesIt() {
        ask(single)
        saveKiosk(single.copy(exitMode = "remote"))
        settle()
        assertNull(showingDialog())
        assertTrue(prompt.isFinishing)
        assertNotNull(storedKiosk())
    }

    @Test fun anotherKioskOrARemoteExitMeanwhileClosesThePrompt() {
        ask(single)
        saveKiosk(grid)
        settle()
        assertNull("not this kiosk's prompt any more", showingDialog())
        assertTrue(prompt.isFinishing)
        assertEquals(grid, storedKiosk())
        closePrompt()

        ask(single)
        saveKiosk(null)
        settle()
        assertNull(showingDialog())
        assertTrue(prompt.isFinishing)
    }

    /** The screen going off, or another app coming up: nobody finds a half-typed PIN later. */
    @Test fun leavingTheScreenTakesThePromptAway() {
        ask(single)
        edit(pinDialog()!!.window!!.decorView)!!.setText("48")
        request!!.pause().stop()
        settle()
        assertNull(showingDialog())
        assertTrue(prompt.isFinishing)
        assertEquals(single, storedKiosk())
    }

    @Test fun aRotationKeepsThePromptAndWhatWasTyped() {
        ask(single)
        edit(pinDialog()!!.window!!.decorView)!!.setText("48")
        val rotated = Configuration(prompt.resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
            val width = screenWidthDp
            screenWidthDp = screenHeightDp
            screenHeightDp = width
        }
        request!!.configurationChange(rotated)
        settle()
        assertFalse(prompt.isFinishing)
        assertEquals("48", edit(pinDialog()!!.window!!.decorView)!!.text.toString())
        submitPin(pin)
        assertNull(storedKiosk())
    }

    /**
     * Android refuses the exit (the agent can't drop the allowlist): the device is still locked, so its kiosk state
     * must stay too. The prompt closed on the right PIN, so it says why nothing happened.
     */
    @Test fun anExitAndroidRefusesChangesNothingAndSaysSo() {
        ask(single)
        val refusing = object : KioskController {
            override fun enter(
                homeComponent: ComponentName,
                allowedPackages: List<String>,
                features: Int,
            ): KioskResult = KioskResult.Ok
            override fun exit(): KioskResult = KioskResult.Failed("not allowed")
            override fun isLocked(context: Context) = true
            override fun allowedPackages(): List<String> = emptyList()
        }
        val alias = ComponentName(app, "com.mdmesh.agent.KioskHomeAlias")
        prompt.applier = KioskApplier(refusing, prompt.store, AndroidKioskHomeSwitch(app, alias), alias) { true }
        submitPin(pin)
        assertEquals("still in its kiosk", single, storedKiosk())
        assertTrue(exitEvents().isEmpty())
        assertNotNull(unavailableDialog())
        assertFalse(prompt.isFinishing)
        tap(R.string.action_ok)
        assertTrue(prompt.isFinishing)
    }
}
