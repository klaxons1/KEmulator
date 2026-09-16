package emulator.bluetooth;

import javax.bluetooth.DiscoveryAgent;
import javax.bluetooth.DiscoveryListener;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles Bluetooth device discovery over LAN using UDP broadcast/multicast.
 * 
 * Each emulator instance:
 * - Listens on UDP port 63520 for discovery requests/responses
 * - On startInquiry(), broadcasts DISCOVER_REQ and collects DISCOVER_RESP
 * - On receiving DISCOVER_REQ, responds with DISCOVER_RESP if discoverable
 */
public class DiscoveryManager implements Runnable {

    private final BluetoothStack stack;
    private DatagramSocket socket;
    private MulticastSocket multicastSocket;
    private Thread thread;
    private volatile boolean running = false;

    // Cached peers: btAddress -> peer
    private final Map<String, BluetoothPeer> cachedPeers = new ConcurrentHashMap<>();

    // Inquiry state
    private volatile boolean inquiryRunning = false;
    private volatile DiscoveryListener inquiryListener;
    private Thread inquiryThread;

    public DiscoveryManager(BluetoothStack stack) {
        this.stack = stack;
    }

    public synchronized void start() throws IOException {
        if (running) return;

        // Try to create socket with reuse
        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.setBroadcast(true);
        socket.bind(new InetSocketAddress(BluetoothConstants.DISCOVERY_PORT));

        try {
            multicastSocket = new MulticastSocket(BluetoothConstants.DISCOVERY_PORT);
            multicastSocket.setReuseAddress(true);
            multicastSocket.joinGroup(InetAddress.getByName(BluetoothConstants.DISCOVERY_MULTICAST_GROUP));
        } catch (IOException e) {
            System.out.println("[BT] Multicast not available, using broadcast only: " + e.getMessage());
            multicastSocket = null;
        }

        running = true;
        thread = new Thread(this, "KEm-BT-Discovery");
        thread.setDaemon(true);
        thread.start();
        System.out.println("[BT] Discovery started on port " + BluetoothConstants.DISCOVERY_PORT);
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        if (multicastSocket != null) {
            try {
                multicastSocket.leaveGroup(InetAddress.getByName(BluetoothConstants.DISCOVERY_MULTICAST_GROUP));
            } catch (IOException ignored) {}
            multicastSocket.close();
            multicastSocket = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
        cancelInquiry();
    }

    @Override
    public void run() {
        byte[] buf = new byte[1024];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                if (socket != null) {
                    socket.receive(packet);
                    handlePacket(packet);
                }
            } catch (IOException e) {
                if (running) {
                    // e.printStackTrace();
                }
                // Small delay to avoid busy loop on error
                try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void handlePacket(DatagramPacket packet) {
        String msg = new String(packet.getData(), 0, packet.getLength()).trim();
        // Expected format: KEM_BT|TYPE|...
        if (!msg.startsWith(BluetoothConstants.MAGIC)) return;
        String[] parts = msg.split("\\|");
        if (parts.length < 2) return;
        String type = parts[1];

        String senderIp = packet.getAddress().getHostAddress();

        if (type.equals(BluetoothConstants.TYPE_DISCOVER_REQ)) {
            // Format: KEM_BT|DISCOVER_REQ|btAddr|friendlyName|ip|sdpPort
            if (parts.length < 6) return;
            String remoteBtAddr = parts[2];
            // Don't respond to ourselves
            if (remoteBtAddr.equalsIgnoreCase(stack.getLocalAddress())) return;
            if (stack.getDiscoverable() == DiscoveryAgent.NOT_DISCOVERABLE) return;

            // Respond with our info
            String resp = String.join("|",
                    BluetoothConstants.MAGIC,
                    BluetoothConstants.TYPE_DISCOVER_RESP,
                    stack.getLocalAddress(),
                    stack.getFriendlyName(),
                    BluetoothUtils.getLocalIpString(),
                    String.valueOf(stack.getSdpServer().getPort()),
                    String.valueOf(stack.getDeviceClass()),
                    String.valueOf(stack.getDiscoverable())
            );
            sendResponse(resp, packet.getAddress(), packet.getPort());
            // Also add requester to cache (if we have its info)
            try {
                String reqFriendly = parts[3];
                String reqIp = parts[4];
                int reqSdp = Integer.parseInt(parts[5]);
                addOrUpdatePeer(remoteBtAddr, reqFriendly, reqIp, reqSdp, 0);
            } catch (Exception ignored) {}

        } else if (type.equals(BluetoothConstants.TYPE_DISCOVER_RESP)) {
            // Format: KEM_BT|DISCOVER_RESP|btAddr|friendlyName|ip|sdpPort|deviceClass|discoverable
            if (parts.length < 6) return;
            String btAddr = parts[2];
            if (btAddr.equalsIgnoreCase(stack.getLocalAddress())) return; // ignore self
            String friendlyName = parts.length > 3 ? parts[3] : btAddr;
            String ip = parts.length > 4 ? parts[4] : senderIp;
            int sdpPort = 0;
            int devClass = 0;
            try {
                if (parts.length > 5) sdpPort = Integer.parseInt(parts[5]);
                if (parts.length > 6) devClass = Integer.parseInt(parts[6]);
            } catch (NumberFormatException ignored) {}

            BluetoothPeer peer = addOrUpdatePeer(btAddr, friendlyName, ip, sdpPort, devClass);

            // If inquiry is running, notify listener
            if (inquiryRunning && inquiryListener != null) {
                try {
                    // Avoid duplicate notifications - we check if already notified?
                    // For simplicity, notify each time we get response
                    javax.bluetooth.DeviceClass dc = new javax.bluetooth.DeviceClass(devClass);
                    inquiryListener.deviceDiscovered(peer.getRemoteDevice(), dc);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } else if (type.equals(BluetoothConstants.TYPE_BYE)) {
            if (parts.length < 3) return;
            String btAddr = parts[2];
            cachedPeers.remove(btAddr.toUpperCase());
        }
    }

    private BluetoothPeer addOrUpdatePeer(String btAddr, String friendlyName, String ip, int sdpPort, int devClass) {
        String key = btAddr.toUpperCase();
        BluetoothPeer existing = cachedPeers.get(key);
        if (existing != null) {
            existing.setFriendlyName(friendlyName);
            existing.setIpAddress(ip);
            if (sdpPort != 0) existing.setSdpPort(sdpPort);
            existing.setDeviceClass(devClass);
            existing.touch();
            return existing;
        } else {
            BluetoothPeer peer = new BluetoothPeer(btAddr, friendlyName, ip, sdpPort, devClass);
            cachedPeers.put(key, peer);
            System.out.println("[BT] Discovered peer: " + peer);
            return peer;
        }
    }

    private void sendResponse(String msg, InetAddress address, int port) {
        try {
            byte[] data = msg.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
            socket.send(packet);
        } catch (IOException e) {
            // e.printStackTrace();
        }
    }

    public void broadcastDiscoveryRequest() {
        String msg = String.join("|",
                BluetoothConstants.MAGIC,
                BluetoothConstants.TYPE_DISCOVER_REQ,
                stack.getLocalAddress(),
                stack.getFriendlyName(),
                BluetoothUtils.getLocalIpString(),
                String.valueOf(stack.getSdpServer().getPort())
        );
        byte[] data = msg.getBytes();
        // Broadcast to 255.255.255.255
        try {
            DatagramPacket broadcastPacket = new DatagramPacket(data, data.length,
                    InetAddress.getByName("255.255.255.255"), BluetoothConstants.DISCOVERY_PORT);
            socket.send(broadcastPacket);
        } catch (IOException ignored) {}

        // Multicast
        if (multicastSocket != null) {
            try {
                DatagramPacket multicastPacket = new DatagramPacket(data, data.length,
                        InetAddress.getByName(BluetoothConstants.DISCOVERY_MULTICAST_GROUP),
                        BluetoothConstants.DISCOVERY_PORT);
                multicastSocket.send(multicastPacket);
            } catch (IOException ignored) {}
        }

        // Also send to localhost for same-machine instances
        try {
            DatagramPacket localhostPacket = new DatagramPacket(data, data.length,
                    InetAddress.getByName("127.0.0.1"), BluetoothConstants.DISCOVERY_PORT);
            socket.send(localhostPacket);
        } catch (IOException ignored) {}
    }

    public synchronized boolean startInquiry(int accessCode, DiscoveryListener listener) {
        if (inquiryRunning) return false;
        if (listener == null) return false;

        inquiryRunning = true;
        inquiryListener = listener;

        inquiryThread = new Thread(() -> {
            try {
                System.out.println("[BT] Starting inquiry...");
                // Clear old cache? Keep but will refresh
                broadcastDiscoveryRequest();

                // Repeat broadcast every 2 seconds during inquiry
                long start = System.currentTimeMillis();
                while (inquiryRunning && (System.currentTimeMillis() - start) < BluetoothConstants.DEFAULT_INQUIRY_DURATION_MS) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (inquiryRunning) {
                        broadcastDiscoveryRequest();
                    }
                }

                if (inquiryRunning) {
                    // Inquiry completed normally
                    inquiryRunning = false;
                    try {
                        listener.inquiryCompleted(DiscoveryListener.INQUIRY_COMPLETED);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    System.out.println("[BT] Inquiry completed, found " + cachedPeers.size() + " peers");
                }
            } finally {
                inquiryRunning = false;
            }
        }, "KEm-BT-Inquiry");
        inquiryThread.setDaemon(true);
        inquiryThread.start();
        return true;
    }

    public synchronized boolean cancelInquiry(DiscoveryListener listener) {
        if (!inquiryRunning) return false;
        // Spec says listener param must be the same that started inquiry, but we ignore check for simplicity
        inquiryRunning = false;
        if (inquiryThread != null) {
            inquiryThread.interrupt();
            inquiryThread = null;
        }
        if (inquiryListener != null) {
            try {
                inquiryListener.inquiryCompleted(DiscoveryListener.INQUIRY_TERMINATED);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        inquiryListener = null;
        System.out.println("[BT] Inquiry cancelled");
        return true;
    }

    public synchronized void cancelInquiry() {
        inquiryRunning = false;
        if (inquiryThread != null) {
            inquiryThread.interrupt();
            inquiryThread = null;
        }
        inquiryListener = null;
    }

    public javax.bluetooth.RemoteDevice[] retrieveDevices(int option) {
        if (option == DiscoveryAgent.CACHED) {
            Collection<BluetoothPeer> peers = cachedPeers.values();
            javax.bluetooth.RemoteDevice[] result = new javax.bluetooth.RemoteDevice[peers.size()];
            int i = 0;
            for (BluetoothPeer p : peers) {
                result[i++] = p.getRemoteDevice();
            }
            return result.length > 0 ? result : null;
        } else if (option == DiscoveryAgent.PREKNOWN) {
            // For preknown, we could load from file, but return null for now
            // Could also return cached as preknown for simplicity
            return null;
        }
        return null;
    }

    public BluetoothPeer getPeerByAddress(String btAddress) {
        if (btAddress == null) return null;
        return cachedPeers.get(btAddress.toUpperCase());
    }

    public Collection<BluetoothPeer> getCachedPeers() {
        return new ArrayList<>(cachedPeers.values());
    }
}
