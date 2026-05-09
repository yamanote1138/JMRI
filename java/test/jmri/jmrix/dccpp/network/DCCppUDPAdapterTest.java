package jmri.jmrix.dccpp.network;

import java.net.InetAddress;
import jmri.util.JUnitUtil;
import org.junit.jupiter.api.*;
import static org.junit.Assert.*;

/**
 * Tests for {@link DCCppUDPAdapter}.
 *
 * @author Chad Francis (C) 2026
 */
public class DCCppUDPAdapterTest {

    @Test
    public void testCtor() {
        DCCppUDPAdapter a = new DCCppUDPAdapter();
        assertNotNull(a);
    }

    @Test
    public void testDefaults() {
        DCCppUDPAdapter a = new DCCppUDPAdapter();
        assertEquals(DCCppUDPAdapter.DEFAULT_UDP_PORT, a.getPort());
        assertEquals(DCCppUDPAdapter.DEFAULT_IP_ADDRESS, a.getHostName());
    }

    @Test
    public void testSocketNullBeforeConnect() {
        DCCppUDPAdapter a = new DCCppUDPAdapter();
        assertNull(a.getSocket());
        assertFalse(a.status());
    }

    @Test
    public void testSubnetContainsSameSubnet() throws Exception {
        // 192.168.1.0/24 contains 192.168.1.84
        InetAddress iface = InetAddress.getByName("192.168.1.10");
        InetAddress host = InetAddress.getByName("192.168.1.84");
        assertTrue(DCCppUDPAdapter.subnetContains(iface, 24, host));
    }

    @Test
    public void testSubnetContainsDifferentSubnet() throws Exception {
        // 192.168.1.0/24 does NOT contain 192.168.2.84
        InetAddress iface = InetAddress.getByName("192.168.1.10");
        InetAddress host = InetAddress.getByName("192.168.2.84");
        assertFalse(DCCppUDPAdapter.subnetContains(iface, 24, host));
    }

    @Test
    public void testSubnetContainsNarrowPrefix() throws Exception {
        // /28 only covers .0–.15; .84 is outside
        InetAddress iface = InetAddress.getByName("192.168.1.10");
        InetAddress host = InetAddress.getByName("192.168.1.84");
        assertFalse(DCCppUDPAdapter.subnetContains(iface, 28, host));
    }

    @Test
    public void testSubnetContainsZeroPrefix() throws Exception {
        // /0 matches anything
        InetAddress iface = InetAddress.getByName("192.168.1.10");
        InetAddress host = InetAddress.getByName("10.0.0.1");
        assertTrue(DCCppUDPAdapter.subnetContains(iface, 0, host));
    }

    @Test
    public void testSubnetContainsNonByteAlignedPrefix() throws Exception {
        // /22 boundary check: 192.168.4.0/22 covers 192.168.4-7.x
        InetAddress iface = InetAddress.getByName("192.168.4.1");
        assertTrue(DCCppUDPAdapter.subnetContains(iface, 22, InetAddress.getByName("192.168.7.250")));
        assertFalse(DCCppUDPAdapter.subnetContains(iface, 22, InetAddress.getByName("192.168.8.1")));
    }

    @Test
    public void testSubnetContainsIPv4VsIPv6() throws Exception {
        // Mixed address families never match
        InetAddress v4 = InetAddress.getByName("192.168.1.10");
        InetAddress v6 = InetAddress.getByName("::1");
        assertFalse(DCCppUDPAdapter.subnetContains(v4, 24, v6));
    }

    @BeforeEach
    public void setUp() {
        JUnitUtil.setUp();
    }

    @AfterEach
    public void tearDown() {
        JUnitUtil.tearDown();
    }
}
