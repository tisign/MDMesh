package com.hmdm.rest.resource;

import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.ConfigurationWifiNetwork;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.testsupport.Fakes;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.ws.rs.BadRequestException;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** PUT /configurations: wifiNetworks are checked before anything is saved. */
public class ConfigurationResourceWifiNetworksTest {

    @Before
    public void setUp() {
        SecurityContext.init(Fakes.user(5, 1, "configurations"));
    }

    @After
    public void tearDown() {
        SecurityContext.release();
    }

    private static ConfigurationWifiNetwork network(String ssid, String type, String password) {
        ConfigurationWifiNetwork n = new ConfigurationWifiNetwork();
        n.setSsid(ssid); n.setSecurityType(type); n.setPassword(password);
        return n;
    }

    private static Configuration with(ConfigurationWifiNetwork... networks) {
        Configuration c = new Configuration();
        c.setId(3);
        c.setName("warehouse");
        c.setApplications(new ArrayList<Application>());
        c.setWifiNetworks(networks == null ? null : new ArrayList<>(Arrays.asList(networks)));
        return c;
    }

    /** The message the console shows for a save refused before any DAO is touched (none is wired). */
    private static String refusal(Configuration c) {
        try {
            new ConfigurationResource().updateConfiguration(c);
            fail("saved");
            return null;
        } catch (BadRequestException expected) {
            assertEquals(400, expected.getResponse().getStatus());
            return ((Response) expected.getResponse().getEntity()).getMessage();
        }
    }

    @Test
    public void aNetworkThatDevicesWouldRefuseIsNotSaved() {
        String message = refusal(with(network("Depot", "WPA", "short")));
        assertTrue(message, message.startsWith("wifiNetworks: "));
        assertTrue(message, message.contains("Depot"));
        assertFalse("never the password", message.contains("short"));
        refusal(with(network("", "NONE", null)));
        refusal(with(network("Depot", "EAP", "password1")));
        refusal(with(network("Depot", "WPA", "password1"), network("Depot", "WPA", "password2")));
    }

    @Test
    public void anOpenNetworksEmptyPasswordFieldIsNoPassword() {
        Configuration c = with(network("Lobby", "NONE", ""), network("Depot", "WPA", "password1"));
        ConfigurationResource.normalizeWifiNetworks(c);
        assertNull(c.getWifiNetworks().get(0).getPassword());
        assertEquals("password1", c.getWifiNetworks().get(1).getPassword());
    }

    @Test
    public void aRequestWithoutTheListKeepsWhatIsStored() {
        Configuration c = with((ConfigurationWifiNetwork[]) null);
        ConfigurationResource.normalizeWifiNetworks(c);
        assertNull(c.getWifiNetworks());
    }

    /** The names the console sends and reads (web/src/api/configurations.ts WifiNetwork) are the ones mapped here. */
    @Test
    public void theConsolesJsonIsTheNetworkList() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        Configuration c = json.readValue("{\"name\":\"warehouse\",\"applications\":[],\"wifiNetworks\":["
                + "{\"ssid\":\"Depot\",\"securityType\":\"SAE\",\"password\":\"secret-9\",\"hidden\":true},"
                + "{\"ssid\":\"Lobby\",\"securityType\":\"NONE\",\"password\":null}]}", Configuration.class);
        assertEquals(2, c.getWifiNetworks().size());
        ConfigurationWifiNetwork depot = c.getWifiNetworks().get(0);
        assertEquals("Depot", depot.getSsid());
        assertEquals("SAE", depot.getSecurityType());
        assertEquals("secret-9", depot.getPassword());
        assertTrue(depot.isHidden());
        assertFalse("hidden left out is false", c.getWifiNetworks().get(1).isHidden());
        ConfigurationResource.normalizeWifiNetworks(c);

        com.fasterxml.jackson.databind.JsonNode sent = json.valueToTree(c).get("wifiNetworks");
        assertEquals("Depot", sent.get(0).get("ssid").asText());
        assertEquals("SAE", sent.get(0).get("securityType").asText());
        assertEquals("secret-9", sent.get(0).get("password").asText());
        assertTrue(sent.get(0).get("hidden").asBoolean());
        // A configuration read without its networks (a list row) says so with null, which a save then keeps.
        assertTrue(json.valueToTree(new Configuration()).get("wifiNetworks").isNull());
        assertNull(json.readValue("{\"name\":\"x\"}", Configuration.class).getWifiNetworks());
    }

    @Test
    public void aCopyKeepsTheNetworks() {
        Configuration copy = with(network("Depot", "WPA", "password1")).newCopy();
        assertEquals(1, copy.getWifiNetworks().size());
        assertEquals("Depot", copy.getWifiNetworks().get(0).getSsid());
    }
}
