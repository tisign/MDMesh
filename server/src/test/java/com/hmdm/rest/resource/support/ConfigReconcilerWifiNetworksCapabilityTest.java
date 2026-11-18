package com.hmdm.rest.resource.support;

import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import com.hmdm.persistence.domain.ConfigurationWifiNetwork;
import com.hmdm.persistence.domain.Device;
import com.hmdm.persistence.domain.ManagedAppPermission;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.testsupport.Fakes;
import com.hmdm.util.DesiredConfigBuilder;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

/** An agent from before the Wi-Fi networks section is never sent it, and can't acknowledge a document it never got. */
public class ConfigReconcilerWifiNetworksCapabilityTest {
    private static final class FakeUnsecureDAO extends UnsecureDAO {
        final Configuration configuration;
        final List<ConfigurationWifiNetwork> networks;
        final List<ManagedAppPermission> rules;

        FakeUnsecureDAO(Configuration configuration, List<ConfigurationWifiNetwork> networks,
                        List<ManagedAppPermission> rules) {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                    "/tmp", 0, "com.mdmesh.agent");
            this.configuration = configuration;
            this.networks = networks;
            this.rules = rules;
        }

        @Override public Configuration getConfigurationById(Integer id) { return configuration; }
        @Override public List<Application> getPlainConfigurationAppsOptimized(Integer id) {
            Application app = new Application();
            app.setId(4); app.setPkg("org.example.camera"); app.setAction(DesiredConfigBuilder.ACTION_INSTALL);
            return Collections.singletonList(app);
        }
        @Override public List<ManagedAppPermission> getManagedAppPermissions(Integer id) { return rules; }
        @Override public List<ConfigurationWifiNetwork> getWifiNetworks(Integer id) { return networks; }
    }

    private static ConfigReconciler reconciler(List<ConfigurationWifiNetwork> networks, List<ManagedAppPermission> rules) {
        Configuration configuration = new Configuration();
        configuration.setId(7);
        configuration.setCustomerId(1);
        return new ConfigReconciler(new FakeUnsecureDAO(configuration, networks, rules), null, Fakes.noExtensionPoints());
    }

    private static ConfigReconciler reconciler(List<ConfigurationWifiNetwork> networks) {
        return reconciler(networks, Collections.<ManagedAppPermission>emptyList());
    }

    private static Device device() {
        Device device = new Device(); device.setConfigurationId(7);
        return device;
    }

    private static ConfigurationWifiNetwork depot() {
        ConfigurationWifiNetwork n = new ConfigurationWifiNetwork();
        n.setSsid("Depot"); n.setSecurityType("WPA"); n.setPassword("password1");
        return n;
    }

    private static Set<String> tokens(String... tokens) {
        return new HashSet<String>(Arrays.asList(tokens));
    }

    /** Agent 0.5.0: applies app permissions, knows nothing of Wi-Fi networks. */
    private static final Set<String> OLD_AGENT =
            tokens(DesiredConfigBuilder.CAPABILITY, DesiredConfigBuilder.APP_PERMISSIONS_CAPABILITY);
    private static final Set<String> NEW_AGENT = tokens(DesiredConfigBuilder.CAPABILITY,
            DesiredConfigBuilder.APP_PERMISSIONS_CAPABILITY, DesiredConfigBuilder.WIFI_NETWORKS_CAPABILITY);

    @Test
    public void anOlderAgentIsNotSentTheNetworks() {
        ConfigReconciler reconciler = reconciler(Collections.singletonList(depot()));
        DesiredConfig older = reconciler.currentDocument(device(), OLD_AGENT);
        DesiredConfig capable = reconciler.currentDocument(device(), NEW_AGENT);
        assertNull(older.getWifiNetworks());
        assertEquals(1, capable.getWifiNetworks().size());
        assertEquals("password1", capable.getWifiNetworks().get(0).getPassword());
        assertNotEquals(older.getRevision(), capable.getRevision());
        // What the older agent gets is exactly the document without the section.
        capable.setWifiNetworks(null);
        assertEquals(DesiredConfigBuilder.revision(capable), older.getRevision());
    }

    /** A server update must not show every older agent out of sync: a configuration without networks asks nothing new. */
    @Test
    public void anOlderAgentIsInSyncWithAConfigurationWithoutNetworks() {
        ConfigReconciler reconciler = reconciler(Collections.<ConfigurationWifiNetwork>emptyList());
        String sentToOldAgent = reconciler.currentDocument(device(), OLD_AGENT).getRevision();
        assertEquals(sentToOldAgent, reconciler.currentRevision(device(), OLD_AGENT));
        String sentToNewAgent = reconciler.currentDocument(device(), NEW_AGENT).getRevision();
        assertEquals(sentToNewAgent, reconciler.currentRevision(device(), NEW_AGENT));
        // The new agent gets the explicit empty list (it forgets networks saved earlier), under its own revision.
        assertNotEquals(sentToOldAgent, sentToNewAgent);
    }

    /** It can't save them, so its device doesn't know the sites' networks: it applies the rest, never in sync. */
    @Test
    public void anOlderAgentIsNeverInSyncWithAConfigurationWithNetworks() {
        ConfigReconciler reconciler = reconciler(Collections.singletonList(depot()));
        String sentToOldAgent = reconciler.currentDocument(device(), OLD_AGENT).getRevision();
        assertNotEquals(sentToOldAgent, reconciler.currentRevision(device(), OLD_AGENT));
        assertEquals(reconciler.currentDocument(device(), NEW_AGENT).getRevision(),
                reconciler.currentRevision(device(), NEW_AGENT));
    }

    /** The two optional sections are judged apart: missing one that is empty doesn't hide the other. */
    @Test
    public void anAgentWithoutEitherSectionIsComparedWithExactlyWhatItGets() {
        Set<String> oldest = tokens(DesiredConfigBuilder.CAPABILITY);
        ManagedAppPermission rule = new ManagedAppPermission();
        rule.setApplicationId(4); rule.setPermission("android.permission.CAMERA"); rule.setState("deny");

        // Nothing in either: in sync with the plain document.
        ConfigReconciler empty = reconciler(Collections.<ConfigurationWifiNetwork>emptyList());
        assertEquals(empty.currentDocument(device(), oldest).getRevision(), empty.currentRevision(device(), oldest));

        // Rules it can't apply, no networks: out of sync for the rules, as before this section existed.
        ConfigReconciler rules = reconciler(Collections.<ConfigurationWifiNetwork>emptyList(),
                Collections.singletonList(rule));
        assertNotEquals(rules.currentDocument(device(), oldest).getRevision(), rules.currentRevision(device(), oldest));
        // The agent that applies rules but not networks is in sync with it: no network is missing on its device.
        assertEquals(rules.currentDocument(device(), OLD_AGENT).getRevision(), rules.currentRevision(device(), OLD_AGENT));
    }
}
