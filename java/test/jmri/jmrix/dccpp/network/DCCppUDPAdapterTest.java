package jmri.jmrix.dccpp.network;

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

    @BeforeEach
    public void setUp() {
        JUnitUtil.setUp();
    }

    @AfterEach
    public void tearDown() {
        JUnitUtil.tearDown();
    }
}
