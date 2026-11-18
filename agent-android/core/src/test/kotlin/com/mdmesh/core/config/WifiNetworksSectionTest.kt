package com.mdmesh.core.config

import android.content.ComponentName
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.kiosk.KioskHomeSwitch
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.core.store.InMemoryKioskStateStore
import com.mdmesh.core.testing.fixedToggles
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.wifi.WifiNetworkOutcome
import com.mdmesh.policy.wifi.WifiNetworks
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.ConfigWifiNetwork
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `config.apply` wifiNetworks through the applier: which networks are saved, forgotten, left alone and reported. */
class WifiNetworksSectionTest {

    /** The device's saved networks, with every call made on them. */
    private class FakeWifi : WifiNetworks {
        val saved = mutableListOf<ConfigWifiNetwork>()
        val calls = mutableListOf<String>()
        var saveOutcome: (ConfigWifiNetwork) -> WifiNetworkOutcome = { WifiNetworkOutcome.Applied }
        var forgetOutcome: (ConfigWifiNetwork) -> WifiNetworkOutcome = { WifiNetworkOutcome.Applied }

        override fun save(network: ConfigWifiNetwork, known: Boolean): WifiNetworkOutcome {
            calls += "save:${network.ssid}:${if (known) "known" else "new"}"
            val outcome = saveOutcome(network)
            if (outcome == WifiNetworkOutcome.Applied && !known) {
                // As Android 12 and later: one saved network per name.
                saved.removeAll { it.ssid == network.ssid }
                saved += network
            }
            return outcome
        }

        override fun forget(network: ConfigWifiNetwork): WifiNetworkOutcome {
            calls += "forget:${network.ssid}:${network.security}"
            val outcome = forgetOutcome(network)
            if (outcome == WifiNetworkOutcome.Applied) saved.removeAll { it.ssid == network.ssid }
            return outcome
        }
    }

    private object NoKiosk : KioskController {
        override fun enter(homeComponent: ComponentName, allowedPackages: List<String>, features: Int) = KioskResult.Ok
        override fun exit() = KioskResult.Ok
        override fun isLocked(context: android.content.Context) = false
        override fun allowedPackages(): List<String> = emptyList()
    }
    private object NoHome : KioskHomeSwitch {
        override fun setClaimEnabled(enabled: Boolean) = Unit
        override fun showLauncher() = Unit
        override fun showOemHome() = Unit
    }

    private val depot = ConfigWifiNetwork("Depot", ConfigWifiNetwork.WPA, "password1")
    private val lobby = ConfigWifiNetwork("Lobby", ConfigWifiNetwork.NONE)
    private val yard = ConfigWifiNetwork("Yard", ConfigWifiNetwork.SAE, "secret", hidden = true)

    private val wifi = FakeWifi()
    private val store: ConfigStateStore = InMemoryConfigStateStore()
    private val applier = ConfigApplier(
        fixedToggles(), KioskApplier(NoKiosk, InMemoryKioskStateStore(), NoHome, ComponentName("a", "b")) { false },
        {}, store, setPowerMode = {}, wifiNetworks = wifi,
    )

    private fun doc(revision: String, vararg networks: ConfigWifiNetwork) =
        ConfigApplyPayload(revision = revision, wifiNetworks = networks.toList())

    @Test fun `every listed network is saved and reported under its name`() = runTest {
        val result = applier.apply(doc("r1", depot, lobby, yard))
        assertEquals(listOf(depot, lobby, yard), wifi.saved)
        assertEquals(
            listOf("Depot", "Lobby", "Yard").associate { "wifiNetworks.$it" to "applied" },
            result.outcomes,
        )
        assertEquals("r1", store.revision())
        assertEquals(listOf(depot, lobby, yard), store.managedWifiNetworks)
    }

    @Test fun `a document sent again leaves the saved networks alone`() = runTest {
        applier.apply(doc("r1", depot, lobby))
        wifi.calls.clear()
        val again = applier.apply(doc("r1", depot, lobby))
        assertEquals(listOf("save:Depot:known", "save:Lobby:known"), wifi.calls)
        assertTrue(ConfigApplier.succeeded(again))
    }

    @Test fun `a network the list drops is forgotten, the others are not touched`() = runTest {
        applier.apply(doc("r1", depot, lobby))
        wifi.calls.clear()
        val result = applier.apply(doc("r2", lobby))
        assertEquals(listOf("forget:Depot:WPA", "save:Lobby:known"), wifi.calls)
        assertEquals(listOf(lobby), wifi.saved)
        assertEquals(mapOf("wifiNetworks.Lobby" to "applied"), result.outcomes)
        assertEquals(listOf(lobby), store.managedWifiNetworks)
    }

    @Test fun `an empty list forgets every network the agent saved`() = runTest {
        applier.apply(doc("r1", depot, lobby))
        val result = applier.apply(doc("r2"))
        assertTrue(wifi.saved.isEmpty())
        assertTrue(result.outcomes.isEmpty())
        assertTrue(store.managedWifiNetworks.isEmpty())
    }

    @Test fun `a document without the section saves and forgets nothing`() = runTest {
        // An older server, or one that holds the section back: what the agent saved stays, and stays its own.
        applier.apply(doc("r1", depot))
        wifi.calls.clear()
        val result = applier.apply(ConfigApplyPayload(revision = "r2"))
        assertTrue(wifi.calls.isEmpty())
        assertTrue(result.outcomes.isEmpty())
        assertEquals(listOf(depot), store.managedWifiNetworks)
        // The section is back without the network: it is still known as the agent's, and forgotten.
        applier.apply(doc("r3"))
        assertTrue(wifi.saved.isEmpty())
    }

    @Test fun `a changed password is saved again`() = runTest {
        applier.apply(doc("r1", depot))
        wifi.calls.clear()
        val changed = depot.copy(password = "password2")
        applier.apply(doc("r2", changed))
        assertEquals(listOf("save:Depot:new"), wifi.calls)
        assertEquals(listOf(changed), store.managedWifiNetworks)
    }

    @Test fun `a network whose security changed is forgotten under the old one, then saved`() = runTest {
        applier.apply(doc("r1", depot))
        wifi.calls.clear()
        val wpa3 = depot.copy(security = ConfigWifiNetwork.SAE)
        val result = applier.apply(doc("r2", wpa3))
        assertEquals(listOf("forget:Depot:WPA", "save:Depot:new"), wifi.calls)
        assertEquals(listOf(wpa3), wifi.saved)
        assertEquals(mapOf("wifiNetworks.Depot" to "applied"), result.outcomes)
        assertEquals(listOf(wpa3), store.managedWifiNetworks)
    }

    @Test fun `an old security entry the device is on is replaced, and never forgotten later`() = runTest {
        // Android 12 and later keep WPA2 and WPA3 of one name as one saved network: forgetting "the old one" once
        // the new one is saved would take the new one with it, and the device would lose the network it should have.
        applier.apply(doc("r1", depot))
        wifi.forgetOutcome = { WifiNetworkOutcome.Deferred("in use") }
        val wpa3 = depot.copy(security = ConfigWifiNetwork.SAE)
        val result = applier.apply(doc("r2", wpa3))
        assertEquals("the new entry's outcome", mapOf("wifiNetworks.Depot" to "applied"), result.outcomes)
        assertEquals(listOf(wpa3), wifi.saved)
        assertEquals("the old entry is not kept to be forgotten", listOf(wpa3), store.managedWifiNetworks)
        wifi.calls.clear()
        wifi.forgetOutcome = { WifiNetworkOutcome.Applied }
        applier.apply(doc("r3", wpa3, lobby))
        assertEquals(listOf("save:Depot:known", "save:Lobby:new"), wifi.calls)
        assertEquals(listOf(wpa3, lobby), wifi.saved)
    }

    @Test fun `a network that is the device's only connection stays until it can go`() = runTest {
        applier.apply(doc("r1", depot, lobby))
        wifi.forgetOutcome = { WifiNetworkOutcome.Deferred("in use") }
        val result = applier.apply(doc("r2", lobby))
        assertEquals("deferred: in use", result.outcomes["wifiNetworks.Depot"])
        assertTrue("deferred does not hold the document back", ConfigApplier.succeeded(result))
        assertEquals("r2", store.revision())
        assertEquals(listOf(depot, lobby), wifi.saved)
        // Tried again with the next document, whatever it changes.
        wifi.forgetOutcome = { WifiNetworkOutcome.Applied }
        val later = applier.apply(doc("r3", lobby))
        assertEquals(listOf(lobby), wifi.saved)
        assertNull(later.outcomes["wifiNetworks.Depot"])
        assertEquals(listOf(lobby), store.managedWifiNetworks)
    }

    @Test fun `a network Android refuses fails the document, so the server sends it again`() = runTest {
        wifi.saveOutcome = {
            if (it.ssid == "Yard") WifiNetworkOutcome.Failed("Android did not save it") else WifiNetworkOutcome.Applied
        }
        val result = applier.apply(doc("r1", depot, yard))
        assertEquals("applied", result.outcomes["wifiNetworks.Depot"])
        assertEquals("failed: Android did not save it", result.outcomes["wifiNetworks.Yard"])
        assertFalse(ConfigApplier.succeeded(result))
        assertNull("not saved as applied", store.revision())
        assertEquals("only what is on the device", listOf(depot), store.managedWifiNetworks)
        // The re-send: Depot is known and left alone, Yard is tried again.
        wifi.calls.clear()
        wifi.saveOutcome = { WifiNetworkOutcome.Applied }
        assertTrue(ConfigApplier.succeeded(applier.apply(doc("r1", depot, yard))))
        assertEquals(listOf("save:Depot:known", "save:Yard:new"), wifi.calls)
    }

    @Test fun `an update Android refuses keeps the network as it was saved`() = runTest {
        applier.apply(doc("r1", depot))
        wifi.saveOutcome = { WifiNetworkOutcome.Failed("refused") }
        applier.apply(doc("r2", depot.copy(password = "password2")))
        assertEquals("still the agent's, with the password it has", listOf(depot), store.managedWifiNetworks)
    }

    @Test fun `a network this device cannot have is unsupported and does not hold the document back`() = runTest {
        wifi.saveOutcome = {
            if (it.security == ConfigWifiNetwork.SAE) WifiNetworkOutcome.Unsupported else WifiNetworkOutcome.Applied
        }
        val result = applier.apply(doc("r1", depot, yard))
        assertEquals("unsupported", result.outcomes["wifiNetworks.Yard"])
        assertTrue(ConfigApplier.succeeded(result))
        assertEquals(listOf(depot), store.managedWifiNetworks)
    }

    @Test fun `a forget Android refuses fails the document and is tried again`() = runTest {
        applier.apply(doc("r1", depot))
        wifi.forgetOutcome = { WifiNetworkOutcome.Failed("Android did not forget the network") }
        val result = applier.apply(doc("r2"))
        assertEquals("failed: Android did not forget the network", result.outcomes["wifiNetworks.Depot"])
        assertFalse(ConfigApplier.succeeded(result))
        assertEquals(listOf(depot), store.managedWifiNetworks)
    }

    @Test fun `after a boot every network is saved again and none is forgotten`() = runTest {
        applier.apply(doc("r1", depot, lobby))
        // A newer document dropped Lobby but failed elsewhere, so r1 is still the saved one.
        wifi.saved.clear() // as if someone forgot them by hand
        wifi.calls.clear()
        store.managedWifiNetworks = listOf(depot, lobby, yard)
        val result = applier.reapplyPersisted()!!
        assertEquals(listOf("save:Depot:new", "save:Lobby:new"), wifi.calls)
        assertEquals(listOf(depot, lobby), wifi.saved)
        assertTrue(ConfigApplier.succeeded(result))
        assertEquals("Yard is still to be forgotten by a delivered document", 3, store.managedWifiNetworks.size)
    }

    @Test fun `without a Wi-Fi implementation every network is unsupported`() = runTest {
        val plain = ConfigApplier(
            fixedToggles(), KioskApplier(NoKiosk, InMemoryKioskStateStore(), NoHome, ComponentName("a", "b")) { false },
            {}, InMemoryConfigStateStore(), setPowerMode = {},
        )
        assertEquals(mapOf("wifiNetworks.Depot" to "unsupported"), plain.apply(doc("r1", depot)).outcomes)
    }
}
