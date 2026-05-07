# DCC-EX UDP Connection — JMRI Implementation Plan

## Background

DCC-EX's `devel-PMA-UDP` branch adds UDP transport to the EX-CommandStation alongside
the existing TCP connection. JMRI already has a working UDP connection (Roco Z21) that
follows the same structural pattern. This plan models the DCC-EX UDP adapter directly on
the Z21 implementation.

---

## How the CS-side UDP works (from `devel-PMA-UDP`)

- **Port:** 2560 — same as TCP, no new port.
- **Commands (JMRI → CS):** One `<...>` ASCII command per UDP packet, unicast to CS IP:2560.
  Max 255 bytes. Identical format to TCP — no new parsing required on either side.
- **Direct replies (CS → JMRI):** Unicast back to the IP:port the command came from.
- **Unsolicited broadcasts (CS → JMRI):** Multicast to `239.255.255.250:2560` AND unicast
  to any client that previously sent a `<#>` packet.
- **Registration:** Sending `<#>` causes the CS to add JMRI's IP to its unicast broadcast
  list. JMRI then receives all unsolicited updates (loco state, power, sensors) via
  unicast on the same socket — no multicast join needed.

---

## Architecture

The Z21 adapter bypasses JMRI's stream abstraction entirely. The adapter holds a single
`DatagramSocket`; the traffic controller sends and receives `DatagramPacket`s directly.
No `InputStream`/`OutputStream` involved.

```
DCCppUDPAdapter                    ← holds DatagramSocket; connect() opens it
  └─ DCCppUDPTrafficController     ← forwardToPort() sends DatagramPacket
                                      handleOneIncomingReply() blocks on socket.receive()
                                      endOfMessage() always returns true
```

Everything above the transport layer — `DCCppThrottleManager`, `DCCppPowerManager`,
`DCCppSensorManager`, etc. — is unchanged.

---

## Connection dialog approach — TBD

Two options are under discussion with the DCC-EX team:

**Option A — separate "DCC++ Ethernet (UDP)" entry in the connection dialog**
- `DCCppUDPConnectionConfig` registered as a distinct connection type alongside the existing "DCC++ Ethernet" (TCP)
- Separate XML persistence class — clean, no conditional logic

**Option B — "Use UDP" checkbox on the existing "DCC++ Ethernet" connection**
- `ConnectionConfig.setInstance()` branches on the flag to create either `DCCppEthernetAdapter` (TCP) or `DCCppUDPAdapter` (UDP)
- One fewer entry in the dialog; matches CS reality that both transports are always active on the same port
- UDP flag stored as an additional XML attribute on the existing `ConnectionConfigXml` class

The adapter and TC classes (`DCCppUDPAdapter`, `DCCppUDPTrafficController`) are identical in both options — the choice only affects how the connection is exposed in the UI and persisted in XML. The plan below describes those classes independent of which option is chosen.

---

## Files to create

All new files live in `java/src/jmri/jmrix/dccpp/network/`.

### 1. `DCCppUDPAdapter.java`

Extends `DCCppNetworkPortController` (gets DCC-EX system connection memo, manufacturer
string, `resetupConnection()` hook for reconnect).

**`connect()`**
- Does NOT call `super.connect()` (which opens a TCP socket).
- Creates a `DatagramSocket` — unbound, OS assigns local port automatically.
- Sets `DatagramSocket.setSoTimeout(90_000)` — 3× keepalive interval, same rationale as
  the TCP reconnect branch. A `SocketTimeoutException` after 90 s of silence breaks the
  receive loop and triggers `recovery()`.
- Sends `<#>` to register with the CS for unicast broadcasts.
- Starts the keepalive timer.

**Keepalive timer (30 s)**
- Sends `<#>` every 30 s.
- Dual purpose: keeps JMRI on the CS's unicast broadcast list, and acts as a heartbeat
  so the 90 s socket timeout fires if the CS disappears.
- Reuse the same `TimerTask` pattern as `DCCppEthernetAdapter`.

**`closeConnection()`**
- Cancels the keepalive timer.
- Closes the `DatagramSocket`.

**`getSocket()`**
- Returns the `DatagramSocket` so the traffic controller can call `send()`/`receive()`.

**`resetupConnection()`**
- Sends `<#>` again to re-register after a reconnect.

---

### 2. `DCCppUDPTrafficController.java`

Extends `AbstractMRTrafficController`. Does not extend any stream-based packetizer.
Follows `Z21TrafficController` closely.

**`connectPort(AbstractPortController p)`**
- Casts to `DCCppUDPAdapter`, resolves `InetAddress` from hostname, stores host+port.
- Starts transmit and receive threads (same as Z21).

**`forwardToPort(AbstractMRMessage m, AbstractMRListener reply)`**
- Converts message to bytes: `m.toString().getBytes(StandardCharsets.US_ASCII)`.
- Creates a `DatagramPacket` addressed to `(host, port)`.
- Calls `adapter.getSocket().send(packet)`.
- No stream, no flush — one send call per message.

**`handleOneIncomingReply()`**
- Allocates a 1472-byte buffer matching the CS's `UDP_RESPONSE_MAX` — the maximum
  unfragmented UDP payload over Ethernet.
- Blocks on `adapter.getSocket().receive(packet)`.
- Constructs: `new DCCppReply(new String(buffer, 0, packet.getLength(), StandardCharsets.US_ASCII))`.
  `DCCppReply(String)` already exists and handles full-message construction.
- Dispatches via `dispatchReply()` — same path as the TCP stack.

**`endOfMessage(AbstractMRReply r)`**
- Always returns `true`. Each UDP datagram is one complete DCC-EX message; there is no
  incremental assembly.

**`newReply()`**
- Returns `new DCCppReply()`.

**`terminateThreads()`**
- Closes the socket to unblock the pending `receive()` call, then calls `super.terminateThreads()`.
  Same pattern as `Z21TrafficController.terminateThreads()`.

---

### 3. UI and persistence (depends on option chosen)

**Option A:**
- `DCCppUDPConnectionConfig.java` — extends `AbstractNetworkConnectionConfig`; `name()` returns `"DCC++ Ethernet (UDP)"`; `setInstance()` creates `DCCppUDPAdapter`
- `configurexml/DCCppUDPConnectionConfigXml.java` — standard XML persistence, near-identical to the TCP Ethernet version
- `DCCppConnectionTypeList.java` — add `DCCppUDPConnectionConfig` to `getAvailableProtocolClasses()`

**Option B:**
- Add `useUDP` boolean field to `DCCppEthernetAdapter` and a checkbox to `network/ConnectionConfig`
- `network/ConnectionConfig.setInstance()` branches on the flag
- `network/configurexml/ConnectionConfigXml.java` — store/restore `useUDP` attribute; absence defaults to `false` (TCP), preserving all existing layout files

---

## Key design decisions

**Why not multicast?**
The CS unicasts all broadcasts to any client that sent `<#>`. This means JMRI uses one
`DatagramSocket` for everything — sends, direct replies, and unsolicited updates — exactly
like Z21. No multicast group join, no second socket.

**Why the same port as TCP (2560)?**
That's what the CS branch uses. A user running both TCP and UDP connections simultaneously
to the same CS would have a conflict, but that's not a realistic scenario. If it becomes
one, the CS would need to differentiate.

**Reply construction**
`DCCppReply(String)` is the correct constructor. The CS sends complete `<...>` messages
in each datagram, so the full reply string is available immediately from `packet.getData()`.
No byte-by-byte accumulation needed.

**Reconnect**
`DatagramSocket.setSoTimeout(90_000)` causes `SocketTimeoutException` (a subclass of
`InterruptedIOException`) after 90 s of silence. This breaks `handleOneIncomingReply()`,
sets `rcvException = true`, and triggers the existing `recovery()` → `ReconnectWait`
path already in `AbstractPortController`. No new reconnect logic required.

---

## Risk assessment

**Low risk overall.** The Z21 TC provides a proven template for every non-trivial part
of this implementation. The DCC-EX reply format is unchanged from TCP so no new parsing
is required. Nothing upstream of the transport layer is touched.

The main risk is the unsolicited-broadcast delivery model — if the CS is restarted or
the network is interrupted, JMRI must re-send `<#>` to resume receiving broadcasts.
The reconnect path handles this via `resetupConnection()`, and the CS's `udpDiscoveryClients`
list is only cleared on restart (no idle expiry), so a live connection will not silently
lose its registration.

---

## Testing approach

- Unit tests for `DCCppUDPTrafficController`: mock `DatagramSocket`, verify `forwardToPort()`
  constructs the correct packet bytes, verify `handleOneIncomingReply()` dispatches the
  correct `DCCppReply`.
- Connection config XML round-trip test (hostname, port persist/restore) — copy pattern
  from `configurexml/ConnectionConfigXmlTest.java`.
- Integration test against real EX-CS hardware: send `<#>`, confirm registration; send
  `<s>`, confirm status reply; toggle track power, confirm unsolicited broadcast arrives.
