package com.mdmesh.policy.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.mdmesh.proto.ConfigWifiNetwork

/** What saving or forgetting one network came to; mapped onto a `config.apply` outcome by the caller. */
sealed interface WifiNetworkOutcome {
    data object Applied : WifiNetworkOutcome

    /** Final: this device can't (not Device Owner, no Wi-Fi, a security type this Android or agent doesn't have). */
    data object Unsupported : WifiNetworkOutcome

    /** Deliberately not done now, and not a failure: see [DeviceOwnerWifiNetworks.forget]. */
    data class Deferred(val reason: String) : WifiNetworkOutcome

    data class Failed(val reason: String) : WifiNetworkOutcome
}

/**
 * Keeps Wi-Fi networks saved on the device, for the `config.apply` wifiNetworks section (protocol 1.12): a device
 * shared between sites then joins each site's network by itself.
 */
interface WifiNetworks {
    /**
     * Save [network], or bring the one saved under the same name and security up to date (a new password, hidden).
     *
     * @param known this exact network was saved through here before. It is then left alone: Android is not asked to
     *   save again what it already has, which could interrupt the connection the device is using.
     */
    fun save(network: ConfigWifiNetwork, known: Boolean): WifiNetworkOutcome

    /** Make the device forget [network], as it was last saved through [save]. */
    fun forget(network: ConfigWifiNetwork): WifiNetworkOutcome
}

/** Safe fallback for JVM wiring and tests before the Android implementation is supplied. */
object UnsupportedWifiNetworks : WifiNetworks {
    override fun save(network: ConfigWifiNetwork, known: Boolean): WifiNetworkOutcome = WifiNetworkOutcome.Unsupported
    override fun forget(network: ConfigWifiNetwork): WifiNetworkOutcome = WifiNetworkOutcome.Unsupported
}

/** The platform surface [DeviceOwnerWifiNetworks] needs, so its decisions are testable without Android. */
interface SavedWifi {
    /**
     * Adds [network], or updates the one Android has under the same name and security, and gives its id; null when
     * Android refused. An update with nothing changed is how a saved network's id is found: Android lists saved
     * networks only to callers with location access, which a managed device may have turned off. From Android 12
     * the WPA2 and the WPA3 entry of one name are a single saved network with one id.
     */
    fun upsert(network: ConfigWifiNetwork): Int?

    /** Lets Android join the network by itself (a new one starts disabled). Never connects to it right now. */
    fun enable(id: Int): Boolean

    fun remove(id: Int): Boolean

    /** The id of the network the device is on; null when it is on none, or Android won't say (no location access). */
    fun connectedId(): Int?

    /** Wi-Fi carries the device's traffic right now. */
    fun onWifi(): Boolean

    /** True when a validated internet network that is NOT Wi-Fi exists (cellular, Ethernet, ...). */
    fun hasNonWifiInternet(): Boolean

    /**
     * This device's Wi-Fi can join a network of [security] at all: its radio may lack WPA3, and from Android 15 a
     * device may have dropped WEP. Android refuses to save such a network, which would otherwise be retried for ever.
     */
    fun supports(security: String): Boolean = true
}

/**
 * [WifiNetworks] as Device Owner, through `WifiManager.addNetwork` and friends. Those calls are deprecated since API
 * 29 and fail for ordinary apps targeting it, but Device Owners are exempt (WifiManager javadoc, "Deprecation
 * Exemptions"), and unlike network suggestions they give a network that is really saved: it shows in Settings and
 * needs no approval from the person holding the device.
 *
 * [sdkInt] is passed in so the logic runs as a plain JVM test.
 */
class DeviceOwnerWifiNetworks(
    private val saved: SavedWifi?,
    private val isDeviceOwner: () -> Boolean,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : WifiNetworks {

    override fun save(network: ConfigWifiNetwork, known: Boolean): WifiNetworkOutcome {
        val wifi = usable(network)
        return when {
            wifi == null -> WifiNetworkOutcome.Unsupported
            known -> WifiNetworkOutcome.Applied
            else -> when (val id = wifi.upsert(network)) {
                null -> WifiNetworkOutcome.Failed("Android did not save the network")
                else -> enabled(wifi, id)
            }
        }
    }

    private fun enabled(wifi: SavedWifi, id: Int): WifiNetworkOutcome = if (wifi.enable(id)) {
        WifiNetworkOutcome.Applied
    } else {
        WifiNetworkOutcome.Failed("the network is saved, but Android did not enable it")
    }

    /**
     * Deferred while forgetting could cut the device off for good: it is on this network (or Android won't say which
     * one it is on) and has no other way to the internet. A device whose only connection is removed can't be reached
     * to put it back, so the network stays until the device is online some other way, usually at its next site. The
     * caller keeps asking.
     */
    override fun forget(network: ConfigWifiNetwork): WifiNetworkOutcome {
        // What this device could never save, it has nothing of to forget.
        val wifi = usable(network) ?: return WifiNetworkOutcome.Applied
        val id = wifi.upsert(network)
        return when {
            id == null -> WifiNetworkOutcome.Failed("Android did not find the network")
            isOnlyConnection(wifi, id) -> WifiNetworkOutcome.Deferred(IN_USE)
            wifi.remove(id) -> WifiNetworkOutcome.Applied
            else -> WifiNetworkOutcome.Failed("Android did not forget the network")
        }
    }

    private fun isOnlyConnection(wifi: SavedWifi, id: Int): Boolean {
        if (wifi.hasNonWifiInternet()) return false
        val connected = wifi.connectedId()
        return if (connected != null) connected == id else wifi.onWifi()
    }

    /** The Wi-Fi surface when this device can save [network] at all; null otherwise. */
    private fun usable(network: ConfigWifiNetwork): SavedWifi? {
        val known = network.security in SECURITIES
        // WPA3 needs setSecurityParams (API 30): before it, the flag that makes a network WPA3 is not public.
        val securityOk = network.security != ConfigWifiNetwork.SAE || sdkInt >= Build.VERSION_CODES.R
        // Below API 29 any app may save a network; from 29 only a Device Owner may.
        val allowed = sdkInt < Build.VERSION_CODES.Q || isDeviceOwner()
        return saved?.takeIf { known && securityOk && allowed && it.supports(network.security) }
    }

    companion object {
        /** [WifiNetworkOutcome.Deferred]'s reason when the network is the device's only connection. */
        const val IN_USE = "it is this device's only connection; forgotten once the device is online another way"

        private val SECURITIES = setOf(
            ConfigWifiNetwork.WPA, ConfigWifiNetwork.SAE, ConfigWifiNetwork.WEP, ConfigWifiNetwork.NONE,
        )
    }
}

/** [SavedWifi] on Android. */
class AndroidSavedWifi private constructor(
    private val wifi: WifiManager,
    private val connectivity: ConnectivityManager,
) : SavedWifi {

    companion object {
        /** Null on a device without Wi-Fi hardware (no [WifiManager]): every network is then unsupported. */
        fun from(context: Context): AndroidSavedWifi? {
            val app = context.applicationContext
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            return if (wifi != null && connectivity != null) AndroidSavedWifi(wifi, connectivity) else null
        }

        private val HEX = Regex("[0-9a-fA-F]+")

        /** As WifiConfiguration wants a name or an ASCII key: in double quotes. */
        private fun quoted(s: String): String = "\"$s\""

        /** A key of [hexLengths] hex digits is given raw; anything else is a passphrase, quoted. */
        private fun key(secret: String, vararg hexLengths: Int): String =
            if (secret.length in hexLengths && HEX.matches(secret)) secret else quoted(secret)

        private const val WPA_HEX_LENGTH = 64
        private const val WEP_40_HEX = 10
        private const val WEP_104_HEX = 26
        private const val WEP_128_HEX = 32

        /** The network as Android's (deprecated, Device Owner only) saved-network API takes it. */
        @Suppress("DEPRECATION") // WifiConfiguration: see DeviceOwnerWifiNetworks.
        internal fun configurationOf(network: ConfigWifiNetwork): WifiConfiguration =
            WifiConfiguration().apply {
                SSID = quoted(network.ssid)
                hiddenSSID = network.hidden
                val password = network.password.orEmpty()
                when (network.security) {
                    ConfigWifiNetwork.NONE -> security(WifiConfiguration.SECURITY_TYPE_OPEN) {
                        allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                    }
                    ConfigWifiNetwork.WEP -> {
                        security(WifiConfiguration.SECURITY_TYPE_WEP) {
                            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                            allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                            allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
                        }
                        wepKeys[0] = key(password, WEP_40_HEX, WEP_104_HEX, WEP_128_HEX)
                        wepTxKeyIndex = 0
                    }
                    ConfigWifiNetwork.SAE -> {
                        // Only reached on API 30+ (DeviceOwnerWifiNetworks.usable).
                        security(WifiConfiguration.SECURITY_TYPE_SAE) {}
                        preSharedKey = quoted(password)
                    }
                    else -> {
                        security(WifiConfiguration.SECURITY_TYPE_PSK) {
                            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                        }
                        preSharedKey = key(password, WPA_HEX_LENGTH)
                    }
                }
            }

        /** From API 30 Android derives every flag from the type; before it they are set one by one ([legacy]). */
        @Suppress("DEPRECATION")
        private inline fun WifiConfiguration.security(type: Int, legacy: WifiConfiguration.() -> Unit) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setSecurityParams(type) else legacy()
        }
    }

    override fun supports(security: String): Boolean = runCatching {
        when (security) {
            ConfigWifiNetwork.SAE -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wifi.isWpa3SaeSupported
            ConfigWifiNetwork.WEP ->
                Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || wifi.isWepSupported
            else -> true
        }
    }.getOrDefault(true) // can't tell: try, and report what Android says

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // CHANGE_WIFI_STATE is declared in the app manifest
    override fun upsert(network: ConfigWifiNetwork): Int? = runCatching {
        wifi.addNetwork(configurationOf(network)).takeIf { it >= 0 }
    }.getOrNull()

    @Suppress("DEPRECATION")
    override fun enable(id: Int): Boolean =
        runCatching { wifi.enableNetwork(id, false) && persist() }.getOrDefault(false)

    @Suppress("DEPRECATION")
    override fun remove(id: Int): Boolean = runCatching { wifi.removeNetwork(id) && persist() }.getOrDefault(false)

    /** Before API 26 a change was only kept across a reboot once saved; since then it is, and the call does nothing. */
    @Suppress("DEPRECATION")
    private fun persist(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || wifi.saveConfiguration()

    @Suppress("DEPRECATION") // connectionInfo: the synchronous read; -1 when not connected or not allowed to know.
    override fun connectedId(): Int? = runCatching { wifi.connectionInfo?.networkId?.takeIf { it >= 0 } }.getOrNull()

    @SuppressLint("MissingPermission") // ACCESS_NETWORK_STATE is declared in the app manifest
    override fun onWifi(): Boolean = runCatching {
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(true) // can't tell: assume it is, so nothing is forgotten from under the device

    @Suppress("DEPRECATION") // allNetworks: the only synchronous way to list every network.
    @SuppressLint("MissingPermission")
    override fun hasNonWifiInternet(): Boolean = runCatching {
        connectivity.allNetworks.any { n ->
            val caps = connectivity.getNetworkCapabilities(n) ?: return@any false
            WifiRadioPolicy.isOtherInternet(
                internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            )
        }
    }.getOrDefault(false)
}
