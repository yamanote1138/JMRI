package jmri.jmrix.dccpp.network;

import javax.swing.JPanel;

/**
 * Connection configuration for DCC-EX via UDP.
 *
 * @author Chad Francis (C) 2026
 */
public class DCCppUDPConnectionConfig extends jmri.jmrix.AbstractNetworkConnectionConfig {

    public DCCppUDPConnectionConfig(jmri.jmrix.NetworkPortAdapter p) {
        super(p);
    }

    public DCCppUDPConnectionConfig() {
        super();
    }

    @Override
    public String name() {
        return "DCC++ Ethernet (UDP)";
    }

    @Override
    protected void setInstance() {
        if (adapter == null) {
            adapter = new DCCppUDPAdapter();
        }
    }

    @Override
    public void loadDetails(JPanel details) {
        super.loadDetails(details);
        hostNameField.setText(adapter.getHostName());
        portFieldLabel.setText(Bundle.getMessage("CommunicationPortLabel"));
        portField.setText(String.valueOf(adapter.getPort()));
        portField.setEnabled(true);
    }
}
