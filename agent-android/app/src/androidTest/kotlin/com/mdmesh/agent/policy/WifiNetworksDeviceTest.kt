package com.mdmesh.agent.policy

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mdmesh.policy.wifi.AndroidSavedWifi
import com.mdmesh.policy.wifi.DeviceOwnerWifiNetworks
import com.mdmesh.policy.wifi.WifiNetworkOutcome
import com.mdmesh.proto.ConfigWifiNetwork
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The `config.apply` wifiNetworks section against Android's real Wi-Fi service: a Device Owner really may save,
 * update and forget networks through the calls ordinary apps lost in Android 10, and each security type is saved in
 * a form Android accepts. Run on a disposable Device Owner emulator (Android 12 or later, for the listing the
 * assertions read back); manual only, like [com.mdmesh.agent.OfflineKioskReturnTest]. Only networks named
 * `mdmesh-test-…` are touched, and they are removed afterwards.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION") // WifiConfiguration: see DeviceOwnerWifiNetworks.
class WifiNetworksDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var wifi: WifiManager
    private lateinit var networks: DeviceOwnerWifiNetworks

    private val wpa = ConfigWifiNetwork("${PREFIX}wpa", ConfigWifiNetwork.WPA, "password-1")
    private val rawKey = ConfigWifiNetwork("${PREFIX}raw", ConfigWifiNetwork.WPA, "0123456789abcdef".repeat(4))
    private val wpa3 = ConfigWifiNetwork("${PREFIX}wpa3", ConfigWifiNetwork.SAE, "secret", hidden = true)
    private val open = ConfigWifiNetwork("${PREFIX}open café \"1\"", ConfigWifiNetwork.NONE)

    @Before
    fun setUp() {
        assumeTrue("Requires an Android emulator", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assumeTrue("Requires Android 12 or later", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        assumeTrue("Requires a disposable Device Owner emulator", dpm.isDeviceOwnerApp(context.packageName))
        wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        networks = DeviceOwnerWifiNetworks(AndroidSavedWifi.from(context), { true })
        removeTestNetworks()
    }

    @After
    fun tearDown() {
        if (::wifi.isInitialized) removeTestNetworks()
    }

    /** What this app saved, by name: Android lists a Device Owner its own networks without location access. */
    private fun saved(): Map<String, WifiConfiguration> = wifi.callerConfiguredNetworks
        .filter { it.SSID.removeSurrounding("\"").startsWith(PREFIX) }
        .associateBy { it.SSID.removeSurrounding("\"") }

    private fun removeTestNetworks() {
        saved().values.forEach { wifi.removeNetwork(it.networkId) }
    }

    @Test
    fun aDeviceOwnerSavesEachKindOfNetworkEnabled() {
        for (network in listOf(wpa, rawKey, wpa3, open)) {
            assertEquals(network.ssid, WifiNetworkOutcome.Applied, networks.save(network, known = false))
        }
        val saved = saved()
        assertEquals(setOf(wpa.ssid, rawKey.ssid, wpa3.ssid, open.ssid), saved.keys)
        assertTrue("hidden is kept", saved.getValue(wpa3.ssid).hiddenSSID)
        assertFalse(saved.getValue(wpa.ssid).hiddenSSID)
        for ((ssid, config) in saved) {
            assertNotEquals("$ssid is enabled", WifiConfiguration.Status.DISABLED, config.status)
        }
    }

    @Test
    fun savingANetworkAgainUpdatesItInPlace() {
        assertEquals(WifiNetworkOutcome.Applied, networks.save(wpa, known = false))
        val id = saved().getValue(wpa.ssid).networkId
        val changed = wpa.copy(password = "password-2", hidden = true)
        assertEquals(WifiNetworkOutcome.Applied, networks.save(changed, known = false))
        val after = saved()
        assertEquals("still one network of that name", 1, after.size)
        assertEquals("the same saved network", id, after.getValue(wpa.ssid).networkId)
        assertTrue(after.getValue(wpa.ssid).hiddenSSID)
    }

    @Test
    fun aNetworkTheDeviceIsNotOnIsForgottenAndTheOthersStay() {
        networks.save(wpa, known = false)
        networks.save(open, known = false)
        assertEquals(WifiNetworkOutcome.Applied, networks.forget(wpa))
        assertEquals(setOf(open.ssid), saved().keys)
        // Forgetting what is already gone (forgotten by hand) is not a failure, and leaves nothing behind.
        assertEquals(WifiNetworkOutcome.Applied, networks.forget(wpa))
        assertEquals(setOf(open.ssid), saved().keys)
    }

    /**
     * A network changed from WPA2 to WPA3, the way WifiNetworksSection does it: the old entry is forgotten, then the
     * new one saved. One network of that name is left. (Android 12 and later keep the two as one saved network, so
     * the old entry must never be forgotten after the new one is saved: that removes both.)
     */
    @Test
    fun aNetworkChangedToAnotherSecurityIsStillOneNetwork() {
        networks.save(wpa, known = false)
        val asWpa3 = wpa.copy(security = ConfigWifiNetwork.SAE, password = "secret-3")
        assertEquals(WifiNetworkOutcome.Applied, networks.forget(wpa))
        assertEquals(WifiNetworkOutcome.Applied, networks.save(asWpa3, known = false))
        assertEquals(1, savedIds(wpa.ssid).size)
        // The old entry was in use and stayed: the new one takes over that name.
        removeTestNetworks()
        networks.save(wpa, known = false)
        assertEquals(WifiNetworkOutcome.Applied, networks.save(asWpa3, known = false))
        assertEquals("one saved network, WPA2 and WPA3 merged", 1, savedIds(wpa.ssid).size)
        // Which is why the old entry is never forgotten afterwards: that forgets the name altogether.
        assertEquals(WifiNetworkOutcome.Applied, networks.forget(wpa))
        assertTrue(savedIds(wpa.ssid).isEmpty())
    }

    /** The saved networks of that name. Android lists a merged WPA2/WPA3 network once per type, under one id. */
    private fun savedIds(ssid: String): Set<Int> =
        wifi.callerConfiguredNetworks.filter { it.SSID == "\"$ssid\"" }.map { it.networkId }.toSet()

    @Test
    fun aWepNetworkIsSavedOrReportedUnsupportedNeverFailed() {
        val wep = ConfigWifiNetwork("${PREFIX}wep", ConfigWifiNetwork.WEP, "abcde")
        val outcome = networks.save(wep, known = false)
        assertTrue("WEP: $outcome", outcome == WifiNetworkOutcome.Applied || outcome == WifiNetworkOutcome.Unsupported)
        assertEquals(outcome == WifiNetworkOutcome.Applied, wep.ssid in saved())
    }

    private companion object {
        const val PREFIX = "mdmesh-test-"
    }
}
