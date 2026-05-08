package jmri.jmrix.dccpp.network;

import jmri.util.JUnitUtil;
import org.junit.jupiter.api.*;

/**
 * Tests for {@link DCCppUDPConnectionConfig}.
 *
 * @author Chad Francis (C) 2026
 */
public class DCCppUDPConnectionConfigTest
        extends jmri.jmrix.AbstractSerialConnectionConfigTestBase {

    @BeforeEach
    @Override
    public void setUp() {
        JUnitUtil.setUp();
        JUnitUtil.initDefaultUserMessagePreferences();
        cc = new DCCppUDPConnectionConfig(new DCCppUDPAdapter());
    }

    @AfterEach
    @Override
    public void tearDown() {
        cc = null;
        JUnitUtil.tearDown();
    }
}
