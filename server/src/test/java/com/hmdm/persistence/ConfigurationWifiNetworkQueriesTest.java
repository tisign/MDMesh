/*
 * MDMesh: fork of Headwind MDM. Apache-2.0.
 */
package com.hmdm.persistence;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.name.Names;
import com.hmdm.guice.module.PersistenceModule;
import com.hmdm.persistence.domain.ConfigurationWifiNetwork;
import com.hmdm.persistence.mapper.ConfigurationMapper;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.servlet.ServletContext;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A configuration's Wi-Fi networks on the real schema (the server's Liquibase changelog) through the real mapper:
 * they come back as saved and in the console's order, a save replaces the list, and they go with their configuration.
 *
 * <p>Runs when MDMESH_TEST_PG_URL is set (see AgentEnrollmentTransactionTest); with MDMESH_TEST_PG_REQUIRED it fails
 * without one. Works in its own schema, dropped afterwards.</p>
 */
public class ConfigurationWifiNetworkQueriesTest {

    private static final String URL = System.getenv("MDMESH_TEST_PG_URL");
    private static final String USER = System.getenv("MDMESH_TEST_PG_USER");
    private static final String PASSWORD = System.getenv("MDMESH_TEST_PG_PASSWORD");

    private static String schema;
    private static Injector injector;

    @BeforeClass
    public static void setUpClass() throws Exception {
        boolean configured = URL != null && !URL.isEmpty();
        if (!configured && "true".equals(System.getenv("MDMESH_TEST_PG_REQUIRED"))) {
            fail("MDMESH_TEST_PG_REQUIRED is set but MDMESH_TEST_PG_URL is not: CI must run this test, not skip it");
        }
        Assume.assumeTrue("set MDMESH_TEST_PG_URL to run against PostgreSQL", configured);
        schema = "wifi_it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        sql("CREATE SCHEMA " + schema);
        String url = URL + (URL.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        try (Connection c = DriverManager.getConnection(url, user(), password())) {
            Database db = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            new Liquibase("liquibase/db.changelog.xml", new ClassLoaderResourceAccessor(), db).update("common,private");
        }
        Map<String, String> jdbc = new HashMap<>();
        jdbc.put("JDBC.driver", "org.postgresql.Driver");
        jdbc.put("JDBC.url", url);
        jdbc.put("JDBC.username", user());
        jdbc.put("JDBC.password", password());
        injector = Guice.createInjector(new PersistenceModule(servletContext(jdbc)), new AbstractModule() {
            @Override
            protected void configure() {
                Properties props = new Properties();
                props.setProperty("files.directory", "/nonexistent-mdmesh-test-files");
                props.setProperty("role.orgadmin.id", "2");
                props.setProperty("launcher.package", "com.hmdm.launcher");
                props.setProperty("device.fast.search.chars", "5");
                props.setProperty("aapt.command", "aapt");
                props.setProperty("apk.trusted.url", "");
                props.setProperty("base.url", "https://mdm.example.test");
                props.setProperty("hash.secret", "test-hash-secret");
                Names.bindProperties(binder(), props);
            }
        });
    }

    private static ServletContext servletContext(Map<String, String> params) {
        return (ServletContext) Proxy.newProxyInstance(ServletContext.class.getClassLoader(),
                new Class<?>[]{ServletContext.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getInitParameterNames": return Collections.enumeration(params.keySet());
                        case "getInitParameter": return params.get((String) args[0]);
                        default: throw new UnsupportedOperationException(method.getName());
                    }
                });
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        if (schema != null) sql("DROP SCHEMA " + schema + " CASCADE");
    }

    private static String user() {
        return USER == null ? "postgres" : USER;
    }

    private static String password() {
        return PASSWORD == null ? "" : PASSWORD;
    }

    private static void sql(String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement()) {
            for (String st : statements) s.execute(st);
        }
    }

    private static long scalar(String query) throws Exception {
        try (Connection c = DriverManager.getConnection(URL, user(), password()); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(query)) {
            r.next();
            return r.getLong(1);
        }
    }

    private static int configuration(String name) throws Exception {
        String customer = "c" + UUID.randomUUID().toString().substring(0, 8);
        long customerId = scalar("INSERT INTO " + schema + ".customers (name, filesDir, prefix) VALUES ('" + customer
                + "', '" + customer + "', '" + customer + "') RETURNING id");
        return (int) scalar("INSERT INTO " + schema + ".configurations (name, customerId) VALUES ('" + name + "', "
                + customerId + ") RETURNING id");
    }

    private static ConfigurationWifiNetwork network(String ssid, String type, String password, boolean hidden) {
        ConfigurationWifiNetwork n = new ConfigurationWifiNetwork();
        n.setSsid(ssid); n.setSecurityType(type); n.setPassword(password); n.setHidden(hidden);
        return n;
    }

    @Test
    public void networksComeBackAsSavedInTheConsolesOrder() throws Exception {
        int id = configuration("shared tablets");
        ConfigurationMapper mapper = injector.getInstance(ConfigurationMapper.class);
        assertTrue(mapper.getWifiNetworks(id).isEmpty());

        // Not alphabetical, a name with a quote and a non-ASCII letter, an open network, a hidden one.
        mapper.insertWifiNetworks(id, Arrays.asList(
                network("Zentrale", "WPA", "pass word'1", false),
                network("Café \"Nord\"", "NONE", null, false),
                network("Depot", "SAE", "secret-9", true)));

        List<ConfigurationWifiNetwork> stored = mapper.getWifiNetworks(id);
        assertEquals(3, stored.size());
        assertEquals("Zentrale", stored.get(0).getSsid());
        assertEquals("WPA", stored.get(0).getSecurityType());
        assertEquals("pass word'1", stored.get(0).getPassword());
        assertFalse(stored.get(0).isHidden());
        assertEquals("Café \"Nord\"", stored.get(1).getSsid());
        assertEquals("NONE", stored.get(1).getSecurityType());
        assertNull(stored.get(1).getPassword());
        assertEquals("Depot", stored.get(2).getSsid());
        assertEquals("SAE", stored.get(2).getSecurityType());
        assertTrue(stored.get(2).isHidden());
    }

    @Test
    public void aSaveReplacesTheListAndAnotherConfigurationKeepsItsOwn() throws Exception {
        int id = configuration("site a");
        int other = configuration("site b");
        ConfigurationMapper mapper = injector.getInstance(ConfigurationMapper.class);
        mapper.insertWifiNetworks(id, Arrays.asList(network("One", "NONE", null, false), network("Two", "NONE", null, false)));
        // The same name on another configuration is its own row.
        mapper.insertWifiNetworks(other, Collections.singletonList(network("One", "WPA", "password1", false)));

        // ConfigurationDAO's update: remove, then insert what the save sent.
        mapper.removeWifiNetworks(id);
        mapper.insertWifiNetworks(id, Collections.singletonList(network("Two", "WEP", "abcde", false)));

        List<ConfigurationWifiNetwork> stored = mapper.getWifiNetworks(id);
        assertEquals(1, stored.size());
        assertEquals("Two", stored.get(0).getSsid());
        assertEquals("WEP", stored.get(0).getSecurityType());
        assertEquals("password1", mapper.getWifiNetworks(other).get(0).getPassword());
    }

    @Test
    public void networksGoWithTheirConfiguration() throws Exception {
        int id = configuration("retired");
        ConfigurationMapper mapper = injector.getInstance(ConfigurationMapper.class);
        mapper.insertWifiNetworks(id, Collections.singletonList(network("Old site", "NONE", null, false)));
        sql("DELETE FROM " + schema + ".configurations WHERE id = " + id);
        assertEquals(0, scalar("SELECT COUNT(*) FROM " + schema + ".configurationWifiNetworks WHERE configurationId = " + id));
    }

    @Test
    public void theDatabaseItselfRefusesASecurityTypeTheAgentDoesNotKnow() throws Exception {
        int id = configuration("typo");
        ConfigurationMapper mapper = injector.getInstance(ConfigurationMapper.class);
        try {
            mapper.insertWifiNetworks(id, Collections.singletonList(network("Lab", "EAP", "password1", false)));
            fail("stored a network no device could be sent");
        } catch (RuntimeException expected) {
            assertTrue(mapper.getWifiNetworks(id).isEmpty());
        }
    }
}
