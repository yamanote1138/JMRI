package jmri.jmrix.dccpp.network.configurexml;

import jmri.util.JUnitUtil;
import jmri.jmrix.dccpp.network.DCCppUDPConnectionConfig;
import org.junit.jupiter.api.*;

/**
 * XML persistence round-trip test for {@link DCCppUDPConnectionConfig}.
 *
 * @author Chad Francis (C) 2026
 */
public class DCCppUDPConnectionConfigXmlTest
        extends jmri.jmrix.configurexml.AbstractNetworkConnectionConfigXmlTestBase {

    @BeforeEach
    @Override
    public void setUp() {
        JUnitUtil.setUp();
        xmlAdapter = new DCCppUDPConnectionConfigXml();
        cc = new DCCppUDPConnectionConfig();
    }

    @AfterEach
    @Override
    public void tearDown() {
        xmlAdapter = null;
        cc = null;
        JUnitUtil.resetWindows(false, false);
        JUnitUtil.tearDown();
    }
}
