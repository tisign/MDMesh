package com.mdmesh.core.config

import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.policy.wifi.WifiNetworkOutcome
import com.mdmesh.policy.wifi.WifiNetworks
import com.mdmesh.proto.ConfigOutcome
import com.mdmesh.proto.ConfigWifiNetwork

/**
 * The `config.apply` wifiNetworks section (protocol 1.12): the device keeps the listed networks saved, and forgets
 * the ones it saved from an earlier list that this one drops. What it saved is remembered in
 * [ConfigStateStore.managedWifiNetworks], not read back from Android, which lists saved networks only to callers with
 * location access.
 *
 * Each network reports under `wifiNetworks.<ssid>`:
 *  - `applied`: saved (or already saved, exactly so);
 *  - `unsupported`: this device can't have it (a security type its Android lacks, not Device Owner);
 *  - `failed: <reason>`: Android refused. The document is then not saved, so the server sends it again;
 *  - `deferred: <reason>`, for a network being forgotten: it is the device's only connection, so it stays until the
 *    device is online another way ([com.mdmesh.policy.wifi.DeviceOwnerWifiNetworks.forget]). Tried again with every
 *    document.
 *
 * Networks the agent never saved (the setup network from the enrollment QR, ones typed in by hand) are not its to
 * forget, and are left alone.
 */
internal class WifiNetworksSection(private val wifi: WifiNetworks, private val store: ConfigStateStore) {

    /**
     * @param desired the document's list; null = not managed: nothing is saved or forgotten.
     * @param bootReapply the saved document applied again after a boot or an agent update. Every network is saved
     *   again (one forgotten by hand comes back, at a moment when a Wi-Fi reconnect costs nothing), and none is
     *   forgotten: that document may be older than the one that last changed the device.
     */
    fun apply(desired: List<ConfigWifiNetwork>?, bootReapply: Boolean, outcomes: MutableMap<String, String>) {
        desired ?: return
        val managed = store.managedWifiNetworks
        val names = desired.map { it.ssid }.toSet()
        val dropped = managed.filter { it.ssid !in names }
        val kept = (if (bootReapply) dropped else forget(dropped, outcomes)).toMutableList()
        for (network in desired) {
            val before = managed.firstOrNull { it.ssid == network.ssid }
            if (!bootReapply) forgetOldSecurity(before, network)
            val outcome = wifi.save(network, known = !bootReapply && before == network)
            outcomes[key(network.ssid)] = text(outcome)
            // An update Android refused leaves the network as it was saved before.
            (if (outcome == WifiNetworkOutcome.Applied) network else before)?.let { kept += it }
        }
        store.managedWifiNetworks = kept
    }

    /** Forgets [dropped], the names the list no longer has; returns the ones still on the device, to try again. */
    private fun forget(
        dropped: List<ConfigWifiNetwork>,
        outcomes: MutableMap<String, String>,
    ): List<ConfigWifiNetwork> = dropped.filter { old ->
        val outcome = wifi.forget(old)
        val gone = outcome == WifiNetworkOutcome.Applied || outcome == WifiNetworkOutcome.Unsupported
        if (!gone) outcomes[key(old.ssid)] = text(outcome)
        !gone
    }

    /**
     * [network] replaces [before] under another security type. Before Android 12 that is a second saved network, so
     * the old entry is forgotten first. Once, and whatever comes of it: from Android 12 the two are one saved network
     * (WPA2 and WPA3 of a name are merged), so forgetting "the old one" later would take the new one with it. An old
     * entry that stays because the device is on it is replaced by the save that follows.
     */
    private fun forgetOldSecurity(before: ConfigWifiNetwork?, network: ConfigWifiNetwork) {
        if (before != null && before.security != network.security) wifi.forget(before)
    }

    private fun text(outcome: WifiNetworkOutcome): String = when (outcome) {
        WifiNetworkOutcome.Applied -> ConfigOutcome.APPLIED
        WifiNetworkOutcome.Unsupported -> ConfigOutcome.UNSUPPORTED
        is WifiNetworkOutcome.Deferred -> ConfigOutcome.deferred(outcome.reason)
        is WifiNetworkOutcome.Failed -> ConfigOutcome.failed(outcome.reason)
    }

    companion object {
        const val PREFIX = "wifiNetworks."

        fun key(ssid: String): String = "$PREFIX$ssid"
    }
}
