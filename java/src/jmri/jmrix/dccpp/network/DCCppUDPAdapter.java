package jmri.jmrix.dccpp.network;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Enumeration;
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
    // DCC-EX broadcasts state changes on this multicast group; joining it lets us
    // receive async <l>/<H>/<p> updates instead of relying on <#> poll cycles.
    static final String MULTICAST_GROUP = "239.255.255.250";

    private MulticastSocket socket = null;
    private SocketAddress joinedGroup = null;
    private NetworkInterface joinedInterface = null;
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
            // Bind on DEFAULT_UDP_PORT with SO_REUSEADDR so multiple JMRI processes
            // (or repeated reconnects) on the same host don't collide.
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(DEFAULT_UDP_PORT));
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

        // Join the DCC-EX multicast group on whichever local interface can reach
        // the configured host. If we can't pick one, log and fall back to unicast-only.
        try {
            InetAddress hostAddr = InetAddress.getByName(getHostName());
            NetworkInterface iface = pickInterfaceForHost(hostAddr);
            if (iface != null) {
                SocketAddress group = new InetSocketAddress(MULTICAST_GROUP, DEFAULT_UDP_PORT);
                socket.joinGroup(group, iface);
                joinedGroup = group;
                joinedInterface = iface;
                log.info("Joined multicast {} on interface {} for host {}",
                        MULTICAST_GROUP, iface.getName(), getHostName());
            } else {
                log.warn("No matching network interface for host {} - multicast disabled, falling back to <#> polls only",
                        getHostName());
            }
        } catch (UnknownHostException uhe) {
            log.warn("Could not resolve {} for multicast interface selection - multicast disabled",
                    getHostName(), uhe);
        } catch (IOException ioe) {
            log.warn("Could not join multicast group {} - multicast disabled", MULTICAST_GROUP, ioe);
        }

        ConnectionStatus.instance().setConnectionState(
                getSystemConnectionMemo().getUserName(),
                m_HostName + ":" + m_port,
                ConnectionStatus.CONNECTION_UP);
        // Start (or restart after reconnect) the 30-second keepalive/registration timer.
        keepAliveTimer();
    }

    /**
     * Pick the local NetworkInterface whose subnet contains the given host address.
     * Returns null if none matches (e.g. host on a different network entirely).
     * Skips interfaces that are down, loopback-only, or don't support multicast.
     */
    private NetworkInterface pickInterfaceForHost(InetAddress host) {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) {
                    continue;
                }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (subnetContains(ia.getAddress(), ia.getNetworkPrefixLength(), host)) {
                        return ni;
                    }
                }
            }
        } catch (java.net.SocketException se) {
            log.warn("Could not enumerate network interfaces", se);
        }
        return null;
    }

    /**
     * True when {@code host} falls inside the subnet defined by {@code iface}/{@code prefix}.
     * Package-private for unit testing; pure function with no side effects.
     */
    static boolean subnetContains(InetAddress iface, int prefix, InetAddress host) {
        byte[] ifaceBytes = iface.getAddress();
        byte[] hostBytes = host.getAddress();
        if (ifaceBytes.length != hostBytes.length) {
            return false; // IPv4 vs IPv6 mismatch
        }
        int fullBytes = prefix / 8;
        int remBits = prefix % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (ifaceBytes[i] != hostBytes[i]) return false;
        }
        if (remBits == 0) return true;
        int mask = (0xFF << (8 - remBits)) & 0xFF;
        return (ifaceBytes[fullBytes] & mask) == (hostBytes[fullBytes] & mask);
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
            if (joinedGroup != null && joinedInterface != null) {
                try {
                    socket.leaveGroup(joinedGroup, joinedInterface);
                } catch (IOException ignored) {
                    // Best-effort cleanup; socket is being closed anyway.
                }
                joinedGroup = null;
                joinedInterface = null;
            }
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
