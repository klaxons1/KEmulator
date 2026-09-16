# Bluetooth JSR-82 Emulation over LAN

This document describes the full, beautiful implementation of Bluetooth (JSR-82) in KEmulator nnmod,
emulated over local network (UDP + TCP) instead of real Bluetooth hardware.

## Overview

Real Bluetooth hardware is not available in most desktop environments, and Java's Bluetooth APIs
(BlueCove, etc.) are outdated and platform-dependent. For J2ME multiplayer games, we need a way for
two emulator instances to find each other and exchange data.

**Solution:** Emulate Bluetooth over LAN:

- **UDP broadcast/multicast** for device discovery (`DiscoveryAgent.startInquiry()`)
- **TCP sockets** for service discovery (SDP) and data transfer (RFCOMM/SPP, L2CAP, OBEX/GOEP)

This allows:
- Two KEmulator instances on same PC (127.0.0.1) to discover each other
- Two PCs on same LAN (e.g., 192.168.1.x) to play multiplayer J2ME games
- No Bluetooth hardware required

## Architecture

```
MIDlet -> javax.bluetooth.* (JSR-82 API)
          |
          v
emulator.bluetooth.BluetoothStack (singleton)
          |
          +-- DiscoveryManager (UDP 63520, multicast 239.255.10.10)
          |     - Broadcasts DISCOVER_REQ
          |     - Listens for DISCOVER_RESP
          |     - Maintains cachedPeers map
          |
          +-- SDPServer (TCP random port)
          |     - Handles SERVICE_SEARCH requests
          |     - Returns list of local services (UUID, name, TCP port)
          |
          +-- ServiceRegistry
          |     - Maps notifier -> BluetoothService
          |     - Maps handle -> service
          |
          +-- BTSPPConnection / BTSPPConnectionNotifier (RFCOMM over TCP)
          +-- BTL2CAPConnection / BTL2CAPConnectionNotifier (L2CAP over TCP with length prefix)
          +-- OBEX (ClientSessionImpl / SessionNotifierImpl over TCP)
```

### Discovery Protocol (UDP)

Packet format: `KEM_BT|TYPE|btAddr|friendlyName|ip|sdpPort|deviceClass|discoverable`

Types:
- `DISCOVER_REQ` - Inquiry request, broadcasted by `startInquiry()`
- `DISCOVER_RESP` - Response if device is discoverable (GIAC/LIAC)
- `BYE` - Device going offline (sent on shutdown)

Each emulator:
- Binds UDP socket on port 63520 with `SO_REUSEADDR` and `SO_BROADCAST`
- Joins multicast group 239.255.10.10:63520
- Listens for requests, responds if discoverable
- On inquiry, broadcasts request every 2 seconds for 8 seconds, collects responses

### SDP Protocol (TCP)

Simple text protocol over TCP:

Client -> Server: `SEARCH <uuid1,uuid2,...>` or `SEARCH ALL`

Server -> Client:
```
int serviceCount
for each service:
  UTF uuidOrPsm
  UTF serviceName
  UTF protocol (btspp/btl2cap/btgoep)
  int tcpPort (actual TCP port where service listens)
  int handle (SDDB handle)
  UTF connectionUrl (btspp://<btAddr>:<uuid>;name=...)
  int deviceServiceClasses
  int attrCount
  int[attrCount] attrIDs
```

### RFCOMM/SPP (btspp://)

- **Server:** `btspp://localhost:<uuid>;name=Foo` creates `ServerSocket(0)` on random TCP port,
  registers service in `ServiceRegistry`, returns `BTSPPConnectionNotifier`
- **Client:** `btspp://<btAddr>:<uuid>` looks up peer IP and SDP port, queries SDP for service's TCP port,
  opens `Socket(ip, tcpPort)`, wraps as `BTSPPConnection` (implements `StreamConnection`)

Data is raw TCP stream, `setTcpNoDelay(true)` for low latency (important for games).

### L2CAP (btl2cap://)

Packet-oriented, emulated with 2-byte length prefix:

```
[2 bytes big-endian length][payload up to MTU]
```

- `send()` truncates to `transmitMTU` per spec
- `receive()` discards rest if buffer too small per spec
- `ready()` checks if packet available

### OBEX/GOEP (btgoep://)

OBEX is OBject EXchange over RFCOMM. Implemented as simple request/response protocol over TCP:

Client -> Server:
```
int placeholder (0)
byte type (0=CONNECT,1=DISCONNECT,2=PUT,3=GET,4=SETPATH,5=DELETE)
int headerCount
for each header: int id, UTF value, UTF className
int dataLength
byte[dataLength] data
```

Server -> Client:
```
int headerCount
for each header: int id, UTF value, UTF className
int responseCode (from ResponseCodes)
for GET: int dataLength + data
```

Server side uses `ServerRequestHandler` callbacks (`onConnect`, `onPut`, `onGet`, etc.).

## JSR-82 API Implementation

### javax.bluetooth

- **LocalDevice**: Singleton, holds local BT address (random or `bluetooth.address` prop),
  friendly name (`bluetooth.friendly.name` or `KEmulator-<last4>`), device class, discoverable mode.
  `getLocalDevice()` never returns null (per spec).
  `isPowerOn()` always true in emulation.
  `getProperty()` returns `bluetooth.api.version=1.1.1`, `obex.api.version=1.1`, etc.

- **DiscoveryAgent**: Delegates to `BluetoothStack`. `startInquiry()` async, calls
  `deviceDiscovered()` for each peer, then `inquiryCompleted()`. `retrieveDevices(CACHED)`
  returns cached peers. `searchServices()` queries remote SDP server, calls
  `servicesDiscovered()` + `serviceSearchCompleted()`. `selectService()` searches all cached devices.

- **RemoteDevice**: Stores BT address (12 hex), friendly name, IP, SDP port, device class.
  `getBluetoothAddress()` returns real address. `getFriendlyName()` returns discovered name.
  `authenticate()`, `authorize()`, `encrypt()` always succeed in emulation.
  `getRemoteDevice(Connection)` tries to extract BT address from connection URL.

- **DeviceClass**: Proper CoD parsing: service classes (bits 13-23), major (8-12), minor (2-7).

- **DataElement**: Full implementation with validation per spec. Supports NULL, U_INT_1/2/4/8/16,
  INT_1/2/4/8/16, URL, UUID, BOOL, STRING, DATSEQ, DATALT. `addElement()`, `insertElementAt()`,
  `getValue()` returns `Enumeration` for DATSEQ/DATALT.

- **UUID**: Proper 16/32/128-bit support. Base UUID `00000000-0000-1000-8000-00805F9B34FB`.
  `new UUID(long)` for short UUIDs, `new UUID(String, boolean)` for 128-bit or short hex.
  `toString()` returns short hex for 16/32-bit, full 128-bit otherwise. `equals()` compares
  normalized 128-bit form.

- **ServiceRecordImpl**: Implements `ServiceRecord`. Stores attributes map, handle, connection URL,
  protocol, UUID, service name, TCP port. `getAttributeValue()`, `getAttributeIDs()`,
  `getHostDevice()`, `populateRecord()`, `getConnectionURL(security,master)` with security params,
  `setAttributeValue()`, `setDeviceServiceClasses()`.

- **L2CAPConnectionImpl**: Wraps `BTL2CAPConnection`, delegates MTU, send/receive.

- **L2CAPConnectionNotifierImpl**: Wraps `BTL2CAPConnectionNotifier`.

### javax.microedition.io.Connector

Added handling for `btspp://`, `btl2cap://`, `btgoep://`:

```java
if (s.startsWith("btspp://") || s.startsWith("btl2cap://") || s.startsWith("btgoep://")) {
    BluetoothStack stack = BluetoothStack.getInstance();
    if (s.contains("://localhost:")) return stack.openServerNotifier(s);
    else return stack.openClientConnection(s);
}
```

### javax.obex

- **HeaderSetImpl**: Map id->object, `setHeader()`, `getHeader()`, `getHeaderList()`,
  `createAuthenticationChallenge()`, `getResponseCode()`.

- **OperationImpl**: Implements `Operation` (extends `ContentConnection`). Buffers output,
  stores received headers, response code.

- **ClientSessionImpl**: Implements `ClientSession` over TCP. `connect()`, `disconnect()`,
  `setPath()`, `delete()`, `get()` returns `Operation` with data, `put()` returns `Operation`
  that sends data on close.

- **SessionNotifierImpl**: Implements `SessionNotifier`. `acceptAndOpen(handler)` accepts TCP,
  starts thread handling OBEX protocol and calling `handler.onPut()`, `onGet()`, etc.

- **ServerRequestHandler**: Now returns real `HeaderSetImpl` from `createHeaderSet()`,
  default `onConnect()` returns `OBEX_HTTP_OK`.

### com.vodafone.bluetooth (proprietary)

- **BluetoothManager**: Singleton using JSR-82 discovery. `startDeviceSeek()` starts inquiry,
  calls `SeekListener.foundDevice()` for each peer, `terminatedDeviceSeek()` on completion.

- **Device**: Wraps `RemoteDevice`, `startServiceSeek()` does service search, calls
  `foundService()` with `RemoteService[]`.

- **BaseService/LocalService/RemoteService**: Simple data holders with service ID (UUID) and name.

- **SessionBase/SessionManager/SessionMember**: Message exchange over BTSPP/TCP.
  `send(recipients, String/byte[], report)` sends message with msgId, recipients, data.
  Receiver thread calls `gotMessage()`, `gotSignal()`, `gotResult()`, `gotConnectionStatus()`.

## Configuration

System properties (set via `-D` or `property.txt`):

- `bluetooth.address` - 12 hex digits, e.g., `001122AABBCC`, random if not set
- `bluetooth.friendly.name` - Friendly name, default `KEmulator-<last4>`
- `bluetooth.discoverable` - Discoverable mode, default GIAC
- `KEM_BT_ADDRESS` env var alternative for address

Emulator properties:

- `bluetooth.api.version=1.1.1`
- `obex.api.version=1.1`
- `bluetooth.l2cap.receiveMTU.max=672`
- etc.

## Usage Examples

### Device Discovery

```java
LocalDevice local = LocalDevice.getLocalDevice();
DiscoveryAgent agent = local.getDiscoveryAgent();
agent.startInquiry(DiscoveryAgent.GIAC, new DiscoveryListener() {
    public void deviceDiscovered(RemoteDevice dev, DeviceClass cod) {
        System.out.println("Found: " + dev.getBluetoothAddress() + " " + dev.getFriendlyName(false));
    }
    public void inquiryCompleted(int discType) {
        System.out.println("Inquiry completed");
    }
    public void servicesDiscovered(int transID, ServiceRecord[] rec) {}
    public void serviceSearchCompleted(int transID, int respCode) {}
});
```

### Service Search

```java
UUID[] uuids = new UUID[]{new UUID(0x1101)}; // Serial Port
int transID = agent.searchServices(null, uuids, remoteDevice, listener);
```

### SPP Server

```java
StreamConnectionNotifier notifier = (StreamConnectionNotifier) Connector.open("btspp://localhost:1101;name=MyGame");
StreamConnection conn = notifier.acceptAndOpen();
InputStream in = conn.openInputStream();
OutputStream out = conn.openOutputStream();
```

### SPP Client

```java
String url = serviceRecord.getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
// url = btspp://001122AABBCC:1101;name=MyGame
StreamConnection conn = (StreamConnection) Connector.open(url);
```

### L2CAP

```java
// Server
L2CAPConnectionNotifier notifier = (L2CAPConnectionNotifier) Connector.open("btl2cap://localhost:1001;name=Game");
// Client
L2CAPConnection conn = (L2CAPConnection) Connector.open("btl2cap://001122AABBCC:1001");
conn.send(data);
conn.receive(buf);
```

### OBEX

```java
// Server
SessionNotifier notifier = (SessionNotifier) Connector.open("btgoep://localhost:1105;name=OBEX");
Connection c = notifier.acceptAndOpen(new ServerRequestHandler() {
    public int onPut(Operation op) {
        // Handle file received
        return ResponseCodes.OBEX_HTTP_OK;
    }
});

// Client
ClientSession cs = (ClientSession) Connector.open("btgoep://001122AABBCC:1105");
HeaderSet hs = cs.createHeaderSet();
hs.setHeader(HeaderSet.NAME, "test.txt");
Operation put = cs.put(hs);
OutputStream os = put.openOutputStream();
os.write("Hello".getBytes());
os.close();
put.close();
```

## Testing

1. Start two KEmulator instances on same PC
2. In both, run a MIDlet that does discovery - they should find each other as `KEmulator-XXXX`
3. One acts as server (`btspp://localhost:1101;name=Test`), other as client
4. For LAN: start on two PCs on same network, ensure firewall allows UDP 63520 and random TCP ports

## Limitations

- No real Bluetooth hardware access (by design, for portability)
- Security (authenticate, encrypt, authorize) is no-op, always succeeds
- ServiceRecord attributes are simplified, not full SDP binary encoding
- OBEX authentication not implemented
- Preknown devices not persisted (CACHED only)
- Device class is fixed, not dynamic

## Future Improvements

- Add option to use real Bluetooth via BlueCove/TinyB when available
- Persist preknown devices to file
- Full SDP binary encoding for compatibility with real BT stacks
- OBEX authentication
- File transfer UI for OBEX

## Files Changed

- `src/main/emulator/bluetooth/*` - New emulation core
- `src/midp/javax/bluetooth/*` - Full JSR-82 implementation
- `src/midp/javax/obex/ServerRequestHandler.java` - Returns real HeaderSet
- `src/main/emulator/bluetooth/obex/*` - OBEX implementation
- `src/midp/javax/microedition/io/Connector.java` - Routing for btspp/btl2cap/btgoep
- `src/oem/com/vodafone/bluetooth/*` - Full Vodafone implementation
- `src/main/emulator/Emulator.java` - Updated BT version props
- `src/main/emulator/ui/swt/Property.java` - Label change from Stub to LAN Emulation
- `src/main/emulator/custom/CustomMethod.java` - Cleanup on close
