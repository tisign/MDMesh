package com.mdmesh.proto

import kotlinx.serialization.Serializable

/**
 * Payload of `config.apply` (proto/payloads/config-apply.schema.json): the server-computed desired state of the
 * device's configuration. Every field defaults so a newer server can add keys freely.
 *
 * @property revision sha256 of the canonical document; reported back as `appliedConfigRevision` once applied.
 * @property policies only the keys the configuration manages (true = allowed/enabled).
 * @property kiosk present = ensure kiosk with this payload; absent = the configuration does not assert kiosk
 *   (exit only if the previously applied configuration did — see ConfigApplier).
 * @property location capture cadence (`passive`, `active`, or `off` since 1.4), see [DeviceAction.LOCATION_MODE].
 * @property connectivity (1.5) present = set the live-connection mode (`adaptive` or `alwaysOn`), the same modes as
 *   [DeviceAction.POWER_MODE]; absent = not managed, the device keeps its mode.
 */
@Serializable
data class ConfigApplyPayload(
    val revision: String = "",
    val configurationId: Int? = null,
    val policies: Map<String, Boolean> = emptyMap(),
    val kiosk: KioskApplyPayload? = null,
    val location: ConfigLocation? = null,
    val connectivity: ConfigConnectivity? = null,
    /** Null = server does not manage app permissions; empty = deliberately release all earlier overrides. */
    val appPermissions: List<ConfigAppPermissions>? = null,
    /**
     * (1.12) The Wi-Fi networks the device keeps saved, so it joins each by itself. Null = not managed (an older
     * server, or one that holds the section back): nothing is saved or forgotten. Empty = forget every network the
     * agent saved from this section.
     */
    val wifiNetworks: List<ConfigWifiNetwork>? = null,
)

@Serializable
data class ConfigLocation(val mode: String = DeviceAction.LOCATION_PASSIVE)

@Serializable
data class ConfigConnectivity(val mode: String = DeviceAction.POWER_ADAPTIVE)

@Serializable
data class ConfigAppPermissions(
    val packageName: String,
    /** Exact Android runtime permission names mapped to `grant` or `deny`. */
    val permissions: Map<String, String> = emptyMap(),
)

/**
 * One Wi-Fi network of `config.apply` [ConfigApplyPayload.wifiNetworks] (1.12). A network is its name: the list has
 * each [ssid] once, and Android itself keeps a WPA2 and a WPA3 entry of one name as a single saved network.
 *
 * @property security [WPA] (WPA/WPA2 personal), [SAE] (WPA3 personal), [WEP] or [NONE] (open). One this agent doesn't
 *   know is reported `unsupported`, never saved as something else.
 * @property password the passphrase or key; null for an open network.
 * @property hidden the network doesn't broadcast its name.
 */
@Serializable
data class ConfigWifiNetwork(
    val ssid: String,
    val security: String = WPA,
    val password: String? = null,
    val hidden: Boolean = false,
) {
    /** Never the password: this ends up in logs and test failures. */
    override fun toString(): String = "ConfigWifiNetwork(ssid=$ssid, security=$security, hidden=$hidden)"

    companion object {
        const val WPA = "WPA"
        const val SAE = "SAE"
        const val WEP = "WEP"
        const val NONE = "NONE"
    }
}

/** JSON-encoded into `CommandResult.detail` (proto/payloads/config-apply-result.schema.json). */
@Serializable
data class ConfigApplyResult(
    val revision: String,
    /** keys: `policies.<key>`, `kiosk`, `location`, `connectivity`, `wifiNetworks.<ssid>` → [ConfigOutcome] strings. */
    val outcomes: Map<String, String>,
)

object ConfigOutcome {
    const val APPLIED = "applied"
    const val UNSUPPORTED = "unsupported"
    fun failed(reason: String): String = "failed: $reason"

    /** 1.4: deliberately not applied now (e.g. Wi-Fi off while Wi-Fi is the only network); does not block. */
    fun deferred(reason: String): String = "deferred: $reason"
    fun isFailed(outcome: String): Boolean = outcome.startsWith("failed")
}
