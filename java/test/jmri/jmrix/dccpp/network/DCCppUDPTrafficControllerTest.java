package jmri.jmrix.dccpp.network;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import jmri.jmrix.dccpp.DCCppCommandStation;
import jmri.jmrix.dccpp.DCCppInterface;
import jmri.jmrix.dccpp.DCCppListener;
import jmri.jmrix.dccpp.DCCppMessage;
import jmri.jmrix.dccpp.DCCppReply;
import jmri.util.JUnitUtil;

import org.junit.jupiter.api.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link DCCppUDPTrafficController}.
 * <p>
 * Uses a mock {@link DatagramSocket} injected via a stub adapter so tests run
 * without real network I/O.
 *
 * @author Chad Francis (C) 2026
 */
public class DCCppUDPTrafficControllerTest {

    /**
     * TC subclass that runs {@code distributeReply()} synchronously on the
     * calling thread instead of via {@code SwingUtilities.invokeAndWait}.
     * This avoids EDT overhead in headless unit tests while keeping all other
     * dispatch logic identical.
     */
    private static class SyncTC extends DCCppUDPTrafficController {
        SyncTC(DCCppCommandStation cs) { super(cs); }

        @Override
        protected void distributeReply(Runnable r) { r.run(); }
    }

    /**
     * Minimal adapter stub that:
     * <ul>
     *   <li>Exposes an injected mock socket.</li>
     *   <li>Disables connection recovery so the reconnect machinery never
     *       fires — keeps test logs clean.</li>
     * </ul>
     */
    private static class StubAdapter extends DCCppUDPAdapter {
        private final DatagramSocket mockSocket;

        StubAdapter(DatagramSocket mockSocket) {
            this.mockSocket = mockSocket;
            opened = true;
            allowConnectionRecovery = false;
        }

        @Override public DatagramSocket getSocket() { return mockSocket; }
        @Override public String getHostName() { return "127.0.0.1"; }
        @Override public int getPort() { return 2560; }
    }

    /** Returns a Mockito answer that fills a DatagramPacket and returns immediately. */
    private static org.mockito.stubbing.Answer<Void> deliverPacket(byte[] payload) {
        return inv -> {
            DatagramPacket pkt = inv.getArgument(0);
            System.arraycopy(payload, 0, pkt.getData(), 0, payload.length);
            pkt.setLength(payload.length);
            return null;
        };
    }

    /** Returns a Mockito answer that blocks receive() until the thread is interrupted. */
    private static org.mockito.stubbing.Answer<Void> blockUntilInterrupted() {
        return inv -> {
            try { Thread.sleep(Long.MAX_VALUE); } catch (InterruptedException ie) {
                throw new java.net.SocketException("interrupted");
            }
            return null;
        };
    }

    private DCCppUDPTrafficController tc;

    @BeforeEach
    public void setUp() {
        JUnitUtil.setUp();
    }

    @AfterEach
    public void tearDown() {
        if (tc != null) {
            tc.terminateThreads();
            tc = null;
        }
        JUnitUtil.tearDown();
    }

    // ── forwardToPort ──────────────────────────────────────────────────────────

    @Test
    public void testForwardToPortSendsCorrectBytes() throws Exception {
        DatagramSocket mockSocket = mock(DatagramSocket.class);
        doAnswer(blockUntilInterrupted()).when(mockSocket).receive(any(DatagramPacket.class));

        StubAdapter adapter = new StubAdapter(mockSocket);
        tc = new DCCppUDPTrafficController(new DCCppCommandStation());
        tc.connectPort(adapter);

        tc.sendDCCppMessage(DCCppMessage.makeCSStatusMsg(), null);

        // Allow transmit thread to process (startup delay ≤ 1500 ms)
        Thread.sleep(2500);

        verify(mockSocket, atLeastOnce()).send(argThat(pkt -> {
            String sent = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.US_ASCII);
            return sent.equals("<s>");
        }));
    }

    // ── handleOneIncomingReply ─────────────────────────────────────────────────

    @Test
    public void testIncomingDatagramDispatchedAsReply() throws Exception {
        DatagramSocket mockSocket = mock(DatagramSocket.class);
        byte[] payload = "<p1 MAIN>".getBytes(StandardCharsets.US_ASCII);
        doAnswer(deliverPacket(payload))
                .doAnswer(blockUntilInterrupted())
                .when(mockSocket).receive(any(DatagramPacket.class));

        StubAdapter adapter = new StubAdapter(mockSocket);
        tc = new SyncTC(new DCCppCommandStation());

        List<DCCppReply> replies = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        tc.addDCCppListener(DCCppInterface.ALL, new DCCppListener() {
            @Override public void message(DCCppReply r) { replies.add(r); latch.countDown(); }
            @Override public void message(DCCppMessage m) {}
            @Override public void notifyTimeout(DCCppMessage m) {}
        });

        tc.connectPort(adapter);

        assertTrue("Should dispatch reply within 2 s", latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, replies.size());
        assertEquals('p', replies.get(0).getOpCodeChar());
        assertEquals("p1 MAIN", replies.get(0).toString());
    }

    @Test
    public void testMultiTokenDatagramDispatchedAsSeparateReplies() throws Exception {
        DatagramSocket mockSocket = mock(DatagramSocket.class);
        byte[] payload = "<p1 MAIN><p0 PROG>".getBytes(StandardCharsets.US_ASCII);
        doAnswer(deliverPacket(payload))
                .doAnswer(blockUntilInterrupted())
                .when(mockSocket).receive(any(DatagramPacket.class));

        StubAdapter adapter = new StubAdapter(mockSocket);
        tc = new SyncTC(new DCCppCommandStation());

        List<DCCppReply> replies = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        tc.addDCCppListener(DCCppInterface.ALL, new DCCppListener() {
            @Override public void message(DCCppReply r) { replies.add(r); latch.countDown(); }
            @Override public void message(DCCppMessage m) {}
            @Override public void notifyTimeout(DCCppMessage m) {}
        });

        tc.connectPort(adapter);

        assertTrue("Should dispatch 2 replies within 2 s", latch.await(2, TimeUnit.SECONDS));
        assertEquals(2, replies.size());
        assertEquals("p1 MAIN", replies.get(0).toString());
        assertEquals("p0 PROG", replies.get(1).toString());
    }

    // ── Reconnect path ─────────────────────────────────────────────────────────

    /**
     * {@code SocketTimeoutException} must propagate out of
     * {@code handleOneIncomingReply()} as {@code InterruptedIOException} so
     * that {@code receiveLoop()} can call {@code recovery()}.
     * <p>
     * We verify propagation by calling {@code handleOneIncomingReply()}
     * directly (no receive thread started) and asserting that
     * {@code InterruptedIOException} is thrown.
     */
    @Test
    public void testSocketTimeoutPropagatesAsInterruptedIOException() throws Exception {
        DatagramSocket mockSocket = mock(DatagramSocket.class);
        doThrow(new SocketTimeoutException("simulated 90 s timeout"))
                .when(mockSocket).receive(any(DatagramPacket.class));

        // Wire up TC manually without starting threads, to isolate the method under test.
        StubAdapter adapter = new StubAdapter(mockSocket);
        tc = new DCCppUDPTrafficController(new DCCppCommandStation());
        tc.controller = adapter;
        tc.adapter    = adapter;

        assertThrows(java.io.InterruptedIOException.class,
                () -> tc.handleOneIncomingReply());
    }
}
