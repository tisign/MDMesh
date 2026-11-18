package com.mdmesh.core.kiosk

import android.content.ComponentName
import android.content.Context
import com.mdmesh.core.config.ConfigApplier
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.testing.fixedToggles
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.KioskApplyPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A kiosk asked for while Android's setup wizard is still running (a device just enrolled by QR code): nothing is
 * locked or claimed until setup has finished, then the kiosk that waited is entered, whoever asked for it.
 */
class KioskWaitsForSetupTest {
    private val store = InMemoryKioskStateStore()
    private val config = InMemoryConfigStateStore()
    private val journal = MemJournal()

    /** Everything that would touch the device, in order. */
    private val effects = mutableListOf<String>()
    private var complete = false
    private var enterResult: KioskResult = KioskResult.Ok
    private val payload = KioskApplyPayload(mode = "single", pinPackage = "event.app", password = "482159")

    private val controller = object : KioskController {
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int): KioskResult {
            effects += "enter"
            return enterResult
        }
        override fun exit(): KioskResult { effects += "exit"; return KioskResult.Ok }
        override fun isLocked(context: Context) = false
        override fun allowedPackages(): List<String> = emptyList()
    }
    private val home = object : KioskHomeSwitch {
        override fun setClaimEnabled(enabled: Boolean) { effects += "claim:$enabled" }
        override fun showLauncher() { effects += "launcher" }
        override fun showOemHome() { effects += "oem" }
    }

    /** A new applier each time, as after a restart of the agent: what waits is in the store. */
    private fun kiosk(store: KioskStateStore = this.store) = KioskApplier(
        controller, store, home, ComponentName("agent", "home"), journal = journal, setupComplete = { complete },
    ) { false }

    private fun configApplier(kiosk: KioskApplier = kiosk()) =
        ConfigApplier(fixedToggles(), kiosk, {}, config, setPowerMode = {})

    @Test fun `during setup a kiosk is kept waiting and nothing on the device is touched`() = runTest {
        val kiosk = kiosk()
        assertEquals(KioskResult.Failed(KioskApplier.WAITING_FOR_SETUP), kiosk.enter(payload))
        assertNull("the device is not in a kiosk", store.load())
        assertEquals(payload, store.loadPendingSetup())
        assertTrue("no HOME claim, no allowlist, no launcher over the wizard", effects.isEmpty())
        assertNull("still not complete: nothing to start", kiosk.resumeAfterSetup())
        assertTrue(effects.isEmpty())
    }

    @Test fun `when setup finishes the kiosk that waited is entered, once, also after a restart`() = runTest {
        kiosk().enter(payload)
        complete = true
        val restarted = kiosk()
        assertEquals(KioskResult.Ok, restarted.resumeAfterSetup())
        assertEquals(payload, store.load())
        assertNull(store.loadPendingSetup())
        assertEquals(listOf("claim:true", "enter", "launcher"), effects)
        assertNull("nothing waits any more", restarted.resumeAfterSetup())
        // Staff leave it: the next start must not put the device back.
        restarted.exit()
        assertNull(kiosk().resumeAfterSetup())
        assertNull(store.load())
    }

    @Test fun `a later request replaces the kiosk that waits`() = runTest {
        val kiosk = kiosk()
        kiosk.enter(payload)
        val replacement = payload.copy(pinPackage = "new.app", password = "731946")
        kiosk.enter(replacement)
        complete = true
        kiosk.resumeAfterSetup()
        assertEquals(replacement, store.load())
        assertEquals("entered once", 1, effects.count { it == "enter" })
    }

    @Test fun `a return on the device waits for setup like any entry`() = runTest {
        val kiosk = kiosk()
        assertEquals(KioskResult.Failed(KioskApplier.WAITING_FOR_SETUP), kiosk.enterShown(payload))
        assertTrue(effects.isEmpty())
        assertEquals(payload, store.loadPendingSetup())
    }

    @Test fun `an exit drops the kiosk that waits and starts nothing over the wizard`() = runTest {
        val kiosk = kiosk()
        kiosk.enter(payload)
        assertTrue(kiosk.isPersisted())
        assertEquals(KioskResult.Ok, kiosk.exit())
        assertNull(store.loadPendingSetup())
        assertFalse(kiosk.isPersisted())
        assertTrue("no OEM home, no controller call, no journal entry", effects.isEmpty() && journal.saved == null)
        complete = true
        assertNull(kiosk.resumeAfterSetup())
        assertTrue(effects.isEmpty())
    }

    @Test fun `a PIN exit for a kiosk that only waits is refused like one for a replaced kiosk`() = runTest {
        val kiosk = kiosk()
        kiosk.enter(payload)
        assertEquals(KioskResult.Failed(KioskApplier.EXIT_SUPERSEDED), kiosk.exit(expected = payload))
        assertEquals(payload, store.loadPendingSetup())
    }

    @Test fun `setup finishing between the check and the save lets the entry go ahead`() = runTest {
        // resumeAfterSetup may already have looked and found nothing waiting: this entry must not be left behind.
        val finishing = object : KioskStateStore by store {
            override suspend fun savePendingSetup(payload: KioskApplyPayload?) {
                store.savePendingSetup(payload)
                complete = true
            }
        }
        assertEquals(KioskResult.Ok, kiosk(finishing).enter(payload))
        assertEquals(payload, store.load())
        assertNull("entered, so nothing waits", store.loadPendingSetup())
    }

    @Test fun `an entry that fails once setup is done stays waiting for the next try`() = runTest {
        kiosk().enter(payload)
        complete = true
        enterResult = KioskResult.Failed("dpm")
        assertEquals(KioskResult.Failed("dpm"), kiosk().resumeAfterSetup())
        assertNull(store.load())
        assertEquals(payload, store.loadPendingSetup())
        enterResult = KioskResult.Ok
        assertEquals(KioskResult.Ok, kiosk().resumeAfterSetup())
        assertEquals(payload, store.load())
    }

    @Test fun `on a device that is set up nothing waits`() = runTest {
        complete = true
        assertEquals(KioskResult.Ok, kiosk().enter(payload))
        assertNull(store.loadPendingSetup())
        assertEquals(payload, store.load())
    }

    // --- With the configuration ----------------------------------------------------------------------------------

    @Test fun `a configuration's kiosk waits, the rest of it is applied, and it is not reported applied`() = runTest {
        var location: String? = null
        val applier = ConfigApplier(fixedToggles(), kiosk(), { location = it }, config, setPowerMode = {})
        val result = applier.apply(
            ConfigApplyPayload(revision = "on", kiosk = payload, location = com.mdmesh.proto.ConfigLocation("active")),
        )
        assertEquals("failed: ${KioskApplier.WAITING_FOR_SETUP}", result.outcomes["kiosk"])
        assertEquals("applied", result.outcomes["location"])
        assertEquals("active", location)
        assertNull("the server keeps sending it until the kiosk is on", config.revision())
        assertEquals(payload, store.loadPendingSetup())
    }

    @Test fun `when setup finishes the configuration is applied whole and reported, without the server`() = runTest {
        configApplier().apply(ConfigApplyPayload(revision = "on", kiosk = payload))
        complete = true
        val restarted = configApplier()
        assertEquals(KioskResult.Ok, restarted.resumeKioskAfterSetup())
        assertEquals(payload, store.load())
        assertEquals("in sync at the next check-in", "on", config.revision())
        assertNull("once", restarted.resumeKioskAfterSetup())
    }

    @Test fun `a kiosk asked for from the console waits too, and leaves the configuration alone`() = runTest {
        // An applied configuration without kiosk, then Enter kiosk from the console during setup.
        val applier = configApplier()
        assertTrue(ConfigApplier.succeeded(applier.apply(ConfigApplyPayload(revision = "plain"))))
        kiosk().enter(payload)
        complete = true
        assertEquals(KioskResult.Ok, configApplier().resumeKioskAfterSetup())
        assertEquals(payload, store.load())
        assertEquals("plain", config.revision())
        assertFalse("the configuration was not applied again to take it out", "exit" in effects)
    }

    @Test fun `a configuration that turns kiosk off drops the kiosk that waits`() = runTest {
        val applier = configApplier()
        assertFalse(ConfigApplier.succeeded(applier.apply(ConfigApplyPayload(revision = "on", kiosk = payload))))
        assertTrue(ConfigApplier.succeeded(applier.apply(ConfigApplyPayload(revision = "off"))))
        assertNull(store.loadPendingSetup())
        assertTrue(effects.isEmpty())
        complete = true
        assertNull(applier.resumeKioskAfterSetup())
        assertNull(store.load())
    }

    @Test fun `with nothing waiting, setup finishing changes nothing`() = runTest {
        complete = true
        configApplier().apply(ConfigApplyPayload(revision = "plain"))
        effects.clear()
        assertNull(configApplier().resumeKioskAfterSetup())
        assertTrue(effects.isEmpty())
        assertEquals("plain", config.revision())
    }
}
