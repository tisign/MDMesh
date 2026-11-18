package com.hmdm.util;

import com.hmdm.persistence.domain.ConfigurationWifiNetwork;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a configuration's Wi-Fi network must look like to be saved and sent to devices: the limits Android itself puts
 * on a network it is asked to save, so a network that passes here isn't refused on every device instead. The console
 * checks the same rules as the admin types (web/src/data/wifiNetworks.ts).
 */
public final class WifiNetworkRules {
    /** More sites than one fleet of shared devices plausibly travels between; bounds the document sent to devices. */
    public static final int MAX_NETWORKS = 32;
    /** An SSID is at most 32 bytes (IEEE 802.11); counted in UTF-8, as Android encodes it. */
    public static final int MAX_SSID_BYTES = 32;

    private static final Pattern HEX = Pattern.compile("[0-9a-fA-F]+");

    private WifiNetworkRules() {}

    /** Why [n] can't be saved, or null when it can. Says which field, never the password itself. */
    public static String problem(ConfigurationWifiNetwork n) {
        if (n == null) return "a network is missing";
        String ssid = n.getSsid();
        if (ssid == null || ssid.trim().isEmpty()) return "a network has no name (SSID)";
        if (ssid.getBytes(StandardCharsets.UTF_8).length > MAX_SSID_BYTES) {
            return "network name \"" + ssid + "\" is longer than " + MAX_SSID_BYTES + " bytes";
        }
        // A control character can't be typed on a device and breaks the quoting Android stores the name in.
        for (int i = 0; i < ssid.length(); i++) {
            if (Character.isISOControl(ssid.charAt(i))) return "a network name contains a control character";
        }
        String type = n.getSecurityType();
        String password = n.getPassword();
        boolean none = password == null || password.isEmpty();
        if (ConfigurationWifiNetwork.SECURITY_NONE.equals(type)) {
            return none ? null : "open network \"" + ssid + "\" must not have a password";
        }
        if (none) return "network \"" + ssid + "\" needs a password";
        if (!isPrintableAscii(password)) {
            return "the password of network \"" + ssid + "\" must be plain (ASCII) characters";
        }
        int length = password.length();
        boolean hex = HEX.matcher(password).matches();
        if (ConfigurationWifiNetwork.SECURITY_WPA.equals(type)) {
            return (length >= 8 && length <= 63) || (length == 64 && hex) ? null
                    : "the WPA password of network \"" + ssid + "\" must be 8 to 63 characters, or 64 hex digits";
        }
        if (ConfigurationWifiNetwork.SECURITY_SAE.equals(type)) {
            return length <= 63 ? null : "the WPA3 password of network \"" + ssid + "\" must be at most 63 characters";
        }
        if (ConfigurationWifiNetwork.SECURITY_WEP.equals(type)) {
            boolean ascii = length == 5 || length == 13 || length == 16;
            boolean hexKey = hex && (length == 10 || length == 26 || length == 32);
            return ascii || hexKey ? null : "the WEP key of network \"" + ssid
                    + "\" must be 5, 13 or 16 characters, or 10, 26 or 32 hex digits";
        }
        return "network \"" + ssid + "\" has an unknown security type (WPA, SAE, WEP or NONE)";
    }

    /**
     * Why the list can't be saved, or null when it can: the first network with a {@link #problem}, a name used twice
     * (Android keeps one network per name and security, and two rows for one name can't both be right), or too many.
     */
    public static String problem(List<ConfigurationWifiNetwork> networks) {
        if (networks == null) return null;
        if (networks.size() > MAX_NETWORKS) return "at most " + MAX_NETWORKS + " Wi-Fi networks";
        Set<String> seen = new HashSet<String>();
        for (ConfigurationWifiNetwork n : networks) {
            String problem = problem(n);
            if (problem != null) return problem;
            if (!seen.add(n.getSsid())) return "network \"" + n.getSsid() + "\" is listed twice";
        }
        return null;
    }

    private static boolean isPrintableAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c > 0x7e) return false;
        }
        return true;
    }
}
