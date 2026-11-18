package com.mdmesh.policy.wifi

import com.mdmesh.proto.ConfigWifiNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saving and forgetting Wi-Fi networks over a fake of Android's saved networks (no Android types): what is touched,
 * what is left alone, and that a device's only connection is never forgotten from under it.
 */
class WifiNetworksTest {

    /** Android's saved networks, keyed by name and security (as before Android 12, which merges WPA2 and WPA3). */
    private class FakeSavedWifi : SavedWifi {
        val networks = linkedMapOf<Int, ConfigWifiNetwork>()
        val calls = mutableListOf<String>()
        var accepts = true
        var enables = true
        var removes = true
        var connected: Int? = null
        var wifiActive = false
        var otherInternet = false
        var unsupported = emptySet<String>()
        private var nextId = 1

        fun idOf(ssid: String): Int? = networks.entries.firstOrNull { it.value.ssid == ssid }?.key

        override fun upsert(network: ConfigWifiNetwork): Int? {
            calls += "upsert:${network.ssid}"
            if (!accepts) return null
            val id = networks.entries
                .firstOrNull { it.value.ssid == network.ssid && it.value.security == network.security }?.key
                ?: nextId++
            networks[id] = network
            return id
        }
        override fun enable(id: Int): Boolean { calls += "enable:$id"; return enables }
        override fun remove(id: Int): Boolean {
            calls += "remove:$id"
            if (removes) networks.remove(id)
            return removes
        }
        override fun connectedId(): Int? = connected
        override fun onWifi(): Boolean = wifiActive
        override fun hasNonWifiInternet(): Boolean = otherInternet
        override fun supports(security: String): Boolean = security !in unsupported
    }

    private val depot = ConfigWifiNetwork("Depot", ConfigWifiNetwork.WPA, "password1")
    private val lobby = ConfigWifiNetwork("Lobby", ConfigWifiNetwork.NONE)

    private fun networks(saved: SavedWifi?, sdk: Int = 34, deviceOwner: Boolean = true) =
        DeviceOwnerWifiNetworks(saved, { deviceOwner }, sdk)

    @Test fun `a new network is saved and enabled`() {
        val wifi = FakeSavedWifi()
        assertEquals(WifiNetworkOutcome.Applied, networks(wifi).save(depot, known = false))
        assertEquals(listOf(depot), wifi.networks.values.toList())
        assertEquals(listOf("upsert:Depot", "enable:1"), wifi.calls)
    }

    @Test fun `a network already saved exactly so is not touched`() {
        // Asking Android to save again what it has could interrupt the connection the device is using.
        val wifi = FakeSavedWifi()
        assertEquals(WifiNetworkOutcome.Applied, networks(wifi).save(depot, known = true))
        assertTrue(wifi.calls.isEmpty())
    }

    @Test fun `a changed password updates the saved network in place`() {
        val wifi = FakeSavedWifi()
        val n = networks(wifi)
        n.save(depot, known = false)
        val changed = depot.copy(password = "password2")
        assertEquals(WifiNetworkOutcome.Applied, n.save(changed, known = false))
        assertEquals("one network, the new password", listOf(changed), wifi.networks.values.toList())
    }

    @Test fun `what Android refuses is a failure, to be tried again`() {
        val refused = FakeSavedWifi().apply { accepts = false }
        assertTrue(networks(refused).save(depot, known = false) is WifiNetworkOutcome.Failed)
        val disabled = FakeSavedWifi().apply { enables = false }
        assertTrue(networks(disabled).save(depot, known = false) is WifiNetworkOutcome.Failed)
    }

    @Test fun `a device that cannot save the network says unsupported`() {
        val wifi = FakeSavedWifi()
        // No Wi-Fi hardware.
        assertEquals(WifiNetworkOutcome.Unsupported, networks(null).save(depot, known = false))
        // From Android 10 only a Device Owner may save networks; before it, any app.
        assertEquals(WifiNetworkOutcome.Unsupported, networks(wifi, sdk = 29, deviceOwner = false).save(depot, false))
        assertEquals(WifiNetworkOutcome.Applied, networks(wifi, sdk = 28, deviceOwner = false).save(depot, false))
        // WPA3 needs Android 11.
        val wpa3 = ConfigWifiNetwork("Yard", ConfigWifiNetwork.SAE, "secret")
        assertEquals(WifiNetworkOutcome.Unsupported, networks(wifi, sdk = 29).save(wpa3, known = false))
        assertEquals(WifiNetworkOutcome.Applied, networks(wifi, sdk = 30).save(wpa3, known = false))
        // A security type from a newer server: never saved as something else. Also when it looks already saved.
        val unknown = ConfigWifiNetwork("Lab", "OWE")
        assertEquals(WifiNetworkOutcome.Unsupported, networks(wifi).save(unknown, known = false))
        assertEquals(WifiNetworkOutcome.Unsupported, networks(wifi).save(unknown, known = true))
        assertEquals(listOf("Depot", "Yard"), wifi.networks.values.map { it.ssid })
    }

    @Test fun `a security type this device's Wi-Fi has dropped is unsupported, not a failure`() {
        // Android 15 devices may no longer join WEP networks, and old radios lack WPA3: Android refuses to save
        // those, and a failure would be sent again every hour for ever.
        val wifi = FakeSavedWifi().apply { unsupported = setOf(ConfigWifiNetwork.WEP) }
        val wep = ConfigWifiNetwork("Legacy", ConfigWifiNetwork.WEP, "abcde")
        assertEquals(WifiNetworkOutcome.Unsupported, networks(wifi).save(wep, known = false))
        assertEquals(WifiNetworkOutcome.Applied, networks(wifi).save(depot, known = false))
        assertEquals(listOf("upsert:Depot", "enable:1"), wifi.calls)
    }

    @Test fun `a network the device is not using is forgotten`() {
        val wifi = FakeSavedWifi()
        val n = networks(wifi)
        n.save(depot, known = false); n.save(lobby, known = false)
        wifi.wifiActive = true
        wifi.connected = wifi.idOf("Lobby")
        assertEquals(WifiNetworkOutcome.Applied, n.forget(depot))
        assertEquals(listOf(lobby), wifi.networks.values.toList())
    }

    @Test fun `the device's only connection is not forgotten from under it`() {
        val wifi = FakeSavedWifi()
        val n = networks(wifi)
        n.save(depot, known = false)
        wifi.wifiActive = true
        wifi.connected = wifi.idOf("Depot")
        assertEquals(WifiNetworkOutcome.Deferred(DeviceOwnerWifiNetworks.IN_USE), n.forget(depot))
        assertEquals("still saved", listOf(depot), wifi.networks.values.toList())
        // Another way to the internet (mobile data, Ethernet): the device stays reachable, so it goes.
        wifi.otherInternet = true
        assertEquals(WifiNetworkOutcome.Applied, n.forget(depot))
        assertTrue(wifi.networks.isEmpty())
    }

    @Test fun `when Android will not say which network the device is on, none is forgotten while it is on Wi-Fi`() {
        // Location off: the connected network's id is hidden. It may be this one.
        val wifi = FakeSavedWifi()
        val n = networks(wifi)
        n.save(depot, known = false)
        wifi.wifiActive = true
        wifi.connected = null
        assertEquals(WifiNetworkOutcome.Deferred(DeviceOwnerWifiNetworks.IN_USE), n.forget(depot))
        // Not on Wi-Fi at all (offline, or about to be anyway): nothing to protect.
        wifi.wifiActive = false
        assertEquals(WifiNetworkOutcome.Applied, n.forget(depot))
    }

    @Test fun `forgetting what Android will not find or remove is a failure, to be tried again`() {
        val wifi = FakeSavedWifi()
        val n = networks(wifi)
        n.save(depot, known = false)
        wifi.removes = false
        assertTrue(n.forget(depot) is WifiNetworkOutcome.Failed)
        wifi.accepts = false
        assertTrue(n.forget(depot) is WifiNetworkOutcome.Failed)
        assertEquals(listOf(depot), wifi.networks.values.toList())
    }

    @Test fun `a network the device could never save has nothing to forget`() {
        assertEquals(WifiNetworkOutcome.Applied, networks(null).forget(depot))
        val wifi = FakeSavedWifi()
        assertEquals(WifiNetworkOutcome.Applied, networks(wifi, sdk = 29).forget(ConfigWifiNetwork("Yard", "SAE", "x")))
        assertTrue(wifi.calls.isEmpty())
    }

    @Test fun `the fallback saves and forgets nothing`() {
        assertEquals(WifiNetworkOutcome.Unsupported, UnsupportedWifiNetworks.save(depot, known = false))
        assertEquals(WifiNetworkOutcome.Unsupported, UnsupportedWifiNetworks.forget(depot))
    }
}
