package jmri.jmrix.dccpp.network;

import java.io.IOException;
import java.net.DatagramSocket;
import jmri.jmrix.ConnectionStatus;
import jmri.jmrix.dccpp.DCCppCommandStation;
import jmri.jmrix.dccpp.DCCppInitializationManager;
import jmri.jmrix.dccpp.DCCppMessage;
import jmri.jmrix.dccpp.DCCppNetworkPortController;
import jmri.jmrix.dccpp.DCCppTrafficController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapter for a DCC-EX command station via UDP.
 * <p>
 * Holds a single {@link DatagramSocket} used for all sends, direct replies,
 * and unsolicited broadcast reception. After the socket is opened, a
 * {@code <#>} discovery probe is sent via the traffic controller to register
 * with the command station for unicast broadcasts. A 30-second keepalive
 * timer re-sends {@code <#>} to keep the registration alive and to trigger
 * the 90-second socket timeout if the CS disappears.
 *
 * @author Chad Francis (C) 2026
 */
public class DCCppUDPAdapter extends DCCppNetworkPortController {

    static final int DEFAULT_UDP_PORT = 2560;
    static final String DEFAULT_IP_ADDRESS = "192.168.0.200";

    private DatagramSocket socket = null;
    private java.util.TimerTask keepAliveTimer;
    private static final long KEEPALIVE_INTERVAL_MS = 30_000;
    static final int SOCKET_TIMEOUT_MS = 90_000;

    public DCCppUDPAdapter() {
        super();
        setHostName(DEFAULT_IP_ADDRESS);
        setPort(DEFAULT_UDP_PORT);
        allowConnectionRecovery = true;
        manufacturerName = jmri.jmrix.dccpp.DCCppConnectionTypeList.DCCPP;
    }

    @Override
    public void connect() throws IOException {
        opened = false;
        if (getHostAddress() == null || m_port == 0) {
            log.error("No host name or port set: {}:{}", m_HostName, m_port);
            return;
        }
        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            opened = true;
        } catch (java.net.SocketException se) {
            log.error("Socket exception creating UDP connection", se);
            ConnectionStatus.instance().setConnectionState(
                    getSystemConnectionMemo().getUserName(),
                    m_HostName + ":" + m_port,
                    ConnectionStatus.CONNECTION_DOWN);
            throw se;
        }
        ConnectionStatus.instance().setConnectionState(
                getSystemConnectionMemo().getUserName(),
                m_HostName + ":" + m_port,
                ConnectionStatus.CONNECTION_UP);
        // Start (or restart after reconnect) the 30-second keepalive/registration timer.
        keepAliveTimer();
    }

    @Override
    public void configure() {
        DCCppUDPTrafficController tc = new DCCppUDPTrafficController(new DCCppCommandStation());
        tc.connectPort(this);
        getSystemConnectionMemo().setDCCppTrafficController(tc);
        new DCCppInitializationManager(getSystemConnectionMemo());
        // Send initial <#> to register with the CS for unicast broadcasts.
        tc.sendDCCppMessage(DCCppMessage.makeCSMaxNumSlotsMsg(), null);
    }

    @Override
    protected void resetupConnection() {
        DCCppTrafficController tc = getSystemConnectionMemo().getDCCppTrafficController();
        if (tc != null) {
            tc.connectPort(this);
            tc.sendDCCppMessage(DCCppMessage.makeCSMaxNumSlotsMsg(), null);
        }
    }

    public DatagramSocket getSocket() {
        return socket;
    }

    @Override
    public boolean status() {
        return opened;
    }

    @Override
    public boolean okToSend() {
        return status();
    }

    private void keepAliveTimer() {
        if (keepAliveTimer != null) return;
        keepAliveTimer = new java.util.TimerTask() {
            @Override
            public void run() {
                DCCppTrafficController tc = getSystemConnectionMemo().getDCCppTrafficController();
                if (tc != null) {
                    tc.sendDCCppMessage(DCCppMessage.makeCSMaxNumSlotsMsg(), null);
                }
            }
        };
        jmri.util.TimerUtil.schedule(keepAliveTimer, KEEPALIVE_INTERVAL_MS, KEEPALIVE_INTERVAL_MS);
    }

    public void closeConnection() {
        if (keepAliveTimer != null) {
            keepAliveTimer.cancel();
            keepAliveTimer = null;
        }
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        opened = false;
        ConnectionStatus.instance().setConnectionState(
                getSystemConnectionMemo().getUserName(),
                m_HostName + ":" + m_port,
                ConnectionStatus.CONNECTION_DOWN);
    }

    @Override
    public void dispose() {
        closeConnection();
        allowConnectionRecovery = false;
        super.dispose();
    }

    private static final Logger log = LoggerFactory.getLogger(DCCppUDPAdapter.class);
}
