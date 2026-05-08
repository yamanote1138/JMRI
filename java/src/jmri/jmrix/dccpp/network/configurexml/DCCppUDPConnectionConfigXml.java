package jmri.jmrix.dccpp.network.configurexml;

import jmri.jmrix.configurexml.AbstractNetworkConnectionConfigXml;
import jmri.jmrix.dccpp.network.DCCppUDPAdapter;
import jmri.jmrix.dccpp.network.DCCppUDPConnectionConfig;

/**
 * XML persistence for {@link DCCppUDPConnectionConfig}.
 *
 * @author Chad Francis (C) 2026
 */
public class DCCppUDPConnectionConfigXml extends AbstractNetworkConnectionConfigXml {

    public DCCppUDPConnectionConfigXml() {
        super();
    }

    @Override
    protected void getInstance() {
        if (adapter == null) {
            adapter = new DCCppUDPAdapter();
        }
    }

    @Override
    protected void getInstance(Object object) {
        adapter = ((DCCppUDPConnectionConfig) object).getAdapter();
    }

    @Override
    protected void register() {
        this.register(new DCCppUDPConnectionConfig(adapter));
    }
}
