package com.hmdm.persistence.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;

/**
 * A Wi-Fi network every device on a configuration saves, so a device shared between sites already knows each site's
 * network. Unlike the configuration's setup Wi-Fi (wifiSSID, the one network Android's enrollment QR can carry), these
 * reach the device in config.apply and are saved by the agent, which also forgets the ones a later save removes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConfigurationWifiNetwork implements Serializable {
    /** WPA/WPA2 personal. */
    public static final String SECURITY_WPA = "WPA";
    /** WPA3 personal. */
    public static final String SECURITY_SAE = "SAE";
    public static final String SECURITY_WEP = "WEP";
    /** An open network: no password. */
    public static final String SECURITY_NONE = "NONE";

    private String ssid;
    private String securityType;
    /** The passphrase or key as typed; null for an open network. Plain text, as the setup Wi-Fi password is. */
    private String password;
    /** The network doesn't broadcast its name: the device has to ask for it by name to find it. */
    private boolean hidden;

    public String getSsid() { return ssid; }
    public void setSsid(String ssid) { this.ssid = ssid; }
    public String getSecurityType() { return securityType; }
    public void setSecurityType(String securityType) { this.securityType = securityType; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
}
