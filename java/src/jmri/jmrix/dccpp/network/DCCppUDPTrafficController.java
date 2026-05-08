package jmri.jmrix.dccpp.network;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jmri.jmrix.AbstractMRListener;
import jmri.jmrix.AbstractMRMessage;
import jmri.jmrix.AbstractMRReply;
import jmri.jmrix.AbstractPortController;
import jmri.jmrix.ConnectionStatus;
import jmri.jmrix.dccpp.DCCppCommandStation;
import jmri.jmrix.dccpp.DCCppListener;
import jmri.jmrix.dccpp.DCCppMessage;
import jmri.jmrix.dccpp.DCCppPacketizer;
import jmri.jmrix.dccpp.DCCppReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Traffic controller for DCC-EX over UDP.
 * <p>
 * Sends each outgoing {@link DCCppMessage} as a single unicast
 * {@link DatagramPacket} (wrapped in {@code <} / {@code >}).  Incoming
 * datagrams may contain multiple concatenated {@code <...>} tokens; each is
 * parsed and dispatched as a separate {@link DCCppReply}.
 * <p>
 * Extends {@link DCCppPacketizer} so that all upstream DCC-EX managers work
 * unchanged.  The stream-based {@code connectPort} / {@code forwardToPort} /
 * {@code handleOneIncomingReply} methods are overridden to use the
 * {@link DatagramSocket} owned by {@link DCCppUDPAdapter}.
 *
 * @author Chad Francis (C) 2026
 */
public class DCCppUDPTrafficController extends DCCppPacketizer {

    /** Extracts inner content from each {@code <...>} token in a datagram. */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("<([^>]*)>");

    /** Maximum unfragmented UDP payload size over Ethernet. */
    private static final int UDP_BUF_SIZE = 1472;

    private InetAddress host;
    private int port;
    DCCppUDPAdapter adapter; // package-private for unit tests

    /** True once the first datagram arrives; reset on each connectPort() call. */
    private volatile boolean serverReachable = false;

    public DCCppUDPTrafficController(DCCppCommandStation cs) {
        super(cs);
        allowUnexpectedReply = true;
    }

    @Override
    public void sendDCCppMessage(DCCppMessage m, DCCppListener reply) {
        sendMessage(m, reply);
    }

    /**
     * Resolve the adapter's hostname, start the transmit and receive threads.
     * Does NOT call {@code super.connectPort()} as that would attempt to open
     * streams from the port controller.
     */
    @Override
    public void connectPort(AbstractPortController p) {
        // Stop any threads left over from a previous connection before
        // overwriting xmtThread/rcvThread.  Setting mCurrentState=IDLESTATE
        // causes transmitWait() to return early (via the state-change check),
        // so the old transmit thread exits its while-loop in microseconds
        // rather than waiting out a full 5-second message timeout.
        Thread oldXmt = xmtThread;
        Thread oldRcv = rcvThread;
        if ((oldXmt != null && oldXmt.isAlive()) || (oldRcv != null && oldRcv.isAlive())) {
            threadStopRequest = true;
            synchronized (xmtRunnable) {
                mCurrentState = IDLESTATE;
                xmtRunnable.notifyAll();
            }
            if (oldXmt != null) oldXmt.interrupt();
            if (oldRcv != null) oldRcv.interrupt();
            try { if (oldXmt != null) oldXmt.join(500); } catch (InterruptedException ignored) {}
        }

        rcvException = false;
        connectionError = false;
        xmtException = false;
        threadStopRequest = false;

        if (!(p instanceof DCCppUDPAdapter)) {
            throw new IllegalArgumentException("connectPort: expected DCCppUDPAdapter, got "
                    + p.getClass().getName());
        }
        controller = p;
        adapter = (DCCppUDPAdapter) p;

        try {
            host = InetAddress.getByName(adapter.getHostName());
        } catch (java.net.UnknownHostException uhe) {
            log.error("Unknown host: {}", adapter.getHostName());
            ConnectionStatus.instance().setConnectionState(
                    p.getSystemConnectionMemo().getUserName(),
                    adapter.getHostName() + ":" + adapter.getPort(),
                    ConnectionStatus.CONNECTION_DOWN);
            return;
        }
        port = adapter.getPort();
        serverReachable = false;
        ConnectionStatus.instance().setConnectionState(
                p.getSystemConnectionMemo().getUserName(),
                adapter.getHostName() + ":" + port,
                ConnectionStatus.CONNECTION_DOWN);

        String[] pkgs = getClass().getName().split("\\.");
        String base = (pkgs.length >= 2 ? pkgs[pkgs.length - 2] + "." : "")
                + (pkgs.length >= 1 ? pkgs[pkgs.length - 1] : "");

        xmtThread = jmri.util.ThreadingUtil.newThread(
                xmtRunnable = () -> {
                    try {
                        transmitLoop();
                    } catch (Throwable e) {
                        if (!threadStopRequest) {
                            log.error("Transmit thread terminated by: {}", e.toString(), e);
                        }
                    }
                });
        xmtThread.setName(base + " Transmit thread");
        xmtThread.setDaemon(true);
        xmtThread.setPriority(Thread.MAX_PRIORITY - 1);
        xmtThread.start();

        rcvThread = jmri.util.ThreadingUtil.newThread(this::receiveLoop);
        rcvThread.setName(base + " Receive thread");
        rcvThread.setPriority(Thread.MAX_PRIORITY);
        rcvThread.setDaemon(true);
        rcvThread.start();
    }

    /**
     * Encodes the message as {@code <inner>} and sends it as a unicast
     * {@link DatagramPacket}.
     */
    @SuppressFBWarnings(value = {"TLW_TWO_LOCK_WAIT", "UW_UNCOND_WAIT"},
            justification = "Two locks needed for UDP retry synchronization")
    @Override
    protected synchronized void forwardToPort(AbstractMRMessage m, AbstractMRListener reply) {
        log.debug("forwardToPort: [{}]", m);
        mLastSender = reply;

        Runnable r = new XmtNotifier(m, mLastSender, this);
        javax.swing.SwingUtilities.invokeLater(r);

        String wireMsg = "<" + m.toString() + ">";
        byte[] data = wireMsg.getBytes(StandardCharsets.US_ASCII);
        DatagramPacket pkt = new DatagramPacket(data, data.length, host, port);

        try {
            while (m.getRetries() >= 0) {
                if (portReadyToSend(controller)) {
                    adapter.getSocket().send(pkt);
                    log.debug("UDP sent: {}", wireMsg);
                    break;
                } else if (m.getRetries() >= 0) {
                    log.debug("Retry [{}], attempts remaining: {}", m, m.getRetries());
                    m.setRetries(m.getRetries() - 1);
                    try {
                        synchronized (xmtRunnable) {
                            xmtRunnable.wait(m.getTimeout());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        if (!threadStopRequest) {
                            log.error("retry wait interrupted");
                        }
                    }
                } else {
                    log.warn("forwardToPort: port not ready");
                }
            }
        } catch (IOException e) {
            xmtException = true;
            portWarn(e);
        }
    }

    /**
     * Blocks on {@link DatagramPacket#receive}, then parses every
     * {@code <...>} token from the payload and dispatches each as a
     * {@link DCCppReply}.
     */
    @Override
    public void handleOneIncomingReply() throws IOException {
        byte[] buffer = new byte[UDP_BUF_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            adapter.getSocket().receive(packet);
        } catch (java.net.SocketException | NullPointerException se) {
            // Socket was closed by terminateThreads() — receiveLoop exits via threadStopRequest.
            log.debug("Socket exception on receive — connection closed?");
            rcvException = true;
            return;
        }
        // SocketTimeoutException (extends InterruptedIOException) is intentionally NOT caught here.
        // receiveLoop() catches InterruptedIOException, breaks, and calls recovery().
        if (threadStopRequest) return;

        if (!serverReachable) {
            serverReachable = true;
            ConnectionStatus.instance().setConnectionState(
                    adapter.getSystemConnectionMemo().getUserName(),
                    adapter.getHostName() + ":" + adapter.getPort(),
                    ConnectionStatus.CONNECTION_UP);
        }

        String payload = new String(buffer, 0, packet.getLength(), StandardCharsets.US_ASCII);
        log.debug("UDP received: {}", payload);

        List<DCCppReply> replies = new ArrayList<>();
        Matcher m = TOKEN_PATTERN.matcher(payload);
        while (m.find()) {
            replies.add(DCCppReply.parseDCCppReply(m.group(1)));
        }
        replies.forEach(this::dispatchUDPReply);
    }

    /**
     * Dispatches a single {@link DCCppReply} through the listener chain and
     * advances the transmit-thread state machine.  Mirrors the logic in
     * {@code AbstractMRTrafficController.handleOneIncomingReply()}.
     */
    @SuppressFBWarnings(value = {"UW_UNCOND_WAIT", "WA_NOT_IN_LOOP", "NO_NOTIFY_NOT_NOTIFYALL"},
            justification = "Wait is for external hardware that may not respond; notify on single waiter")
    private void dispatchUDPReply(DCCppReply msg) {
        replyInDispatch = true;
        log.debug("dispatch: {} state {}", msg, mCurrentState);

        Runnable r = new RcvNotifier(msg, mLastSender, this);
        distributeReply(r);

        if (!msg.isUnsolicited()) {
            switch (mCurrentState) {
                case WAITMSGREPLYSTATE:
                    if (msg.isRetransmittableErrorMsg()) {
                        synchronized (xmtRunnable) {
                            mCurrentState = AUTORETRYSTATE;
                            replyInDispatch = false;
                            xmtRunnable.notify();
                        }
                    } else {
                        synchronized (xmtRunnable) {
                            mCurrentState = NOTIFIEDSTATE;
                            replyInDispatch = false;
                            xmtRunnable.notify();
                        }
                    }
                    break;
                case WAITREPLYINPROGMODESTATE:
                    mCurrentMode = PROGRAMINGMODE;
                    replyInDispatch = false;
                    synchronized (xmtRunnable) {
                        mCurrentState = OKSENDMSGSTATE;
                        xmtRunnable.notify();
                    }
                    break;
                case WAITREPLYINNORMMODESTATE:
                    mCurrentMode = NORMALMODE;
                    replyInDispatch = false;
                    synchronized (xmtRunnable) {
                        mCurrentState = OKSENDMSGSTATE;
                        xmtRunnable.notify();
                    }
                    break;
                default:
                    replyInDispatch = false;
                    if (allowUnexpectedReply) {
                        log.debug("Unexpected reply in state {}: {}", mCurrentState, msg);
                        synchronized (xmtRunnable) {
                            xmtRunnable.notify();
                        }
                    }
                    break;
            }
        } else {
            log.debug("Unsolicited reply: {}", msg);
            replyInDispatch = false;
        }
    }

    /** Each UDP datagram is already a complete message. */
    @Override
    protected boolean endOfMessage(AbstractMRReply r) {
        return true;
    }

    @Override
    protected AbstractMRReply newReply() {
        return new DCCppReply();
    }

    /** Close the socket (unblocks the pending {@code receive()}) then stop threads. */
    @Override
    public void terminateThreads() {
        threadStopRequest = true;
        if (adapter != null && adapter.getSocket() != null && !adapter.getSocket().isClosed()) {
            adapter.getSocket().close();
        }
        super.terminateThreads();
    }

    private static final Logger log = LoggerFactory.getLogger(DCCppUDPTrafficController.class);
}
