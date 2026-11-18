package com.hmdm.util;

import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.ConfigurationWifiNetwork;
import com.hmdm.extension.DesiredConfigContributor;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.json.agent.DesiredWifiNetwork;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The Wi-Fi networks a configuration's devices save: what may be saved, and what the device is sent. */
public class WifiNetworkRulesTest {

    private static ConfigurationWifiNetwork network(String ssid, String type, String password) {
        ConfigurationWifiNetwork n = new ConfigurationWifiNetwork();
        n.setSsid(ssid); n.setSecurityType(type); n.setPassword(password);
        return n;
    }

    private static String repeat(char c, int times) {
        char[] chars = new char[times];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static void ok(String type, String password) {
        assertNull(type + " " + (password == null ? 0 : password.length()),
                WifiNetworkRules.problem(network("Depot", type, password)));
    }

    private static void refused(String type, String password) {
        assertNotNull(type + " " + (password == null ? 0 : password.length()),
                WifiNetworkRules.problem(network("Depot", type, password)));
    }

    @Test
    public void passwordsHaveTheLengthAndroidAcceptsForTheirSecurity() {
        ok("WPA", repeat('a', 8)); ok("WPA", repeat('a', 63)); ok("WPA", repeat('f', 64));
        refused("WPA", repeat('a', 7)); refused("WPA", repeat('g', 64)); refused("WPA", repeat('a', 65));
        ok("SAE", "a"); ok("SAE", repeat('a', 63)); refused("SAE", repeat('a', 64));
        for (int length : new int[] {5, 13, 16}) ok("WEP", repeat('k', length));
        for (int length : new int[] {10, 26, 32}) ok("WEP", repeat('a', length));
        refused("WEP", repeat('k', 6)); refused("WEP", repeat('z', 10));
        ok("NONE", null); ok("NONE", "");
        refused("NONE", "secret12");
        for (String type : new String[] {"WPA", "SAE", "WEP"}) { refused(type, null); refused(type, ""); }
        refused("WPA", "pässwörter"); // Android refuses a passphrase that isn't ASCII
    }

    @Test
    public void namesAreOneToThirtyTwoBytesOfUtf8() {
        assertNull(WifiNetworkRules.problem(network(repeat('a', 32), "NONE", null)));
        assertNotNull(WifiNetworkRules.problem(network(repeat('a', 33), "NONE", null)));
        // 11 characters, 33 bytes.
        assertNotNull(WifiNetworkRules.problem(network(repeat('€', 11), "NONE", null)));
        assertNull(WifiNetworkRules.problem(network("Café Nord", "NONE", null)));
        assertNotNull(WifiNetworkRules.problem(network("", "NONE", null)));
        assertNotNull(WifiNetworkRules.problem(network("   ", "NONE", null)));
        assertNotNull(WifiNetworkRules.problem(network(null, "NONE", null)));
        assertNotNull(WifiNetworkRules.problem(network("a\nb", "NONE", null)));
    }

    @Test
    public void anUnknownSecurityTypeIsRefused() {
        refused("EAP", "password1");
        refused(null, "password1");
        refused("wpa", "password1");
    }

    @Test
    public void aListHasNoNameTwiceAndAtMostThirtyTwoNetworks() {
        List<ConfigurationWifiNetwork> twice = Arrays.asList(
                network("Depot", "WPA", "password1"), network("Depot", "NONE", null));
        assertNotNull(WifiNetworkRules.problem(twice));
        // A different case is a different network.
        assertNull(WifiNetworkRules.problem(Arrays.asList(
                network("Depot", "WPA", "password1"), network("depot", "NONE", null))));
        List<ConfigurationWifiNetwork> many = new ArrayList<ConfigurationWifiNetwork>();
        for (int i = 0; i < WifiNetworkRules.MAX_NETWORKS; i++) many.add(network("Site " + i, "NONE", null));
        assertNull(WifiNetworkRules.problem(many));
        many.add(network("One more", "NONE", null));
        assertNotNull(WifiNetworkRules.problem(many));
        assertNull(WifiNetworkRules.problem((List<ConfigurationWifiNetwork>) null));
        assertNull(WifiNetworkRules.problem(Collections.<ConfigurationWifiNetwork>emptyList()));
    }

    @Test
    public void aProblemNeverRepeatsThePassword() {
        String secret = "hunter2";
        String problem = WifiNetworkRules.problem(network("Depot", "WPA", secret));
        assertNotNull(problem);
        assertFalse(problem, problem.contains(secret));
    }

    // --- The document the device is sent -----------------------------------------------------------------------

    private static DesiredConfig build(List<ConfigurationWifiNetwork> networks) {
        Configuration c = new Configuration();
        c.setId(4);
        c.setWifiNetworks(networks);
        return DesiredConfigBuilder.build(c, null, Collections.<DesiredConfigContributor>emptyList());
    }

    @Test
    public void networksNotLoadedLeaveTheDocumentAndItsRevisionAsBefore() {
        DesiredConfig d = build(null);
        assertNull(d.getWifiNetworks());
        assertFalse(DesiredConfigBuilder.canonicalJson(d).contains("wifiNetworks"));
    }

    @Test
    public void anEmptyListIsSentSoTheDeviceForgetsEarlierNetworks() {
        DesiredConfig d = build(Collections.<ConfigurationWifiNetwork>emptyList());
        assertNotNull(d.getWifiNetworks());
        assertTrue(d.getWifiNetworks().isEmpty());
        assertNotEquals(build(null).getRevision(), d.getRevision());
    }

    @Test
    public void theDeviceGetsEachNetworkWithItsSecurityPasswordAndHiddenFlag() {
        ConfigurationWifiNetwork hidden = network("Depot", "SAE", "secret-9");
        hidden.setHidden(true);
        DesiredConfig d = build(Arrays.asList(hidden, network("Café", "NONE", null)));
        assertEquals(2, d.getWifiNetworks().size());
        DesiredWifiNetwork cafe = d.getWifiNetworks().get(0);
        assertEquals("Café", cafe.getSsid());
        assertEquals("NONE", cafe.getSecurity());
        assertNull(cafe.getPassword());
        assertNull("hidden is only sent when true", cafe.getHidden());
        DesiredWifiNetwork depot = d.getWifiNetworks().get(1);
        assertEquals("SAE", depot.getSecurity());
        assertEquals("secret-9", depot.getPassword());
        assertEquals(Boolean.TRUE, depot.getHidden());
    }

    @Test
    public void movingARowInTheConsoleDoesNotChangeTheRevision() {
        ConfigurationWifiNetwork a = network("Alpha", "WPA", "password1");
        ConfigurationWifiNetwork b = network("Bravo", "WPA", "password2");
        assertEquals(build(Arrays.asList(a, b)).getRevision(), build(Arrays.asList(b, a)).getRevision());
        // A new password does.
        assertNotEquals(build(Arrays.asList(a, b)).getRevision(),
                build(Arrays.asList(a, network("Bravo", "WPA", "password3"))).getRevision());
    }

    @Test
    public void aStoredRowTheChecksWouldRefuseIsNotSentToFailOnEveryDevice() {
        DesiredConfig d = build(Arrays.asList(network("Depot", "WPA", "short"), network("Yard", "NONE", null)));
        assertEquals(1, d.getWifiNetworks().size());
        assertEquals("Yard", d.getWifiNetworks().get(0).getSsid());
    }
}
