package com.example.camera.managers;

import android.util.Log;

import com.example.camera.classes.*;
import com.example.camera.classes.Networking.CompleteData;
import com.example.camera.classes.Networking.Connection;
import com.example.camera.classes.Networking.DataPacket;
import com.example.camera.classes.Networking.FrameIdentifier;
import com.example.camera.classes.Networking.PacketType;
import com.example.camera.utils.NetworkingUtils;

import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PeerConnectionManager {
    private static final String TAG = "PeerConnectionManager";
    private static final int PACKET_SIZE = 1400;
    private static final int PORT = 12345;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2;
    private static final int CLEANUP_MS = 15000;
    private static final int MAX_QUEUE_SIZE = 500;
    private static final int RECEIVE_SOCKET_TIMEOUT_MS = 500;

    private static final PeerConnectionManager INSTANCE = new PeerConnectionManager();

    private final ConcurrentHashMap<FrameIdentifier, List<DataPacket>> _incompleteFrames = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<DataPacket> _packetQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private final AtomicLong _latestTimestamp = new AtomicLong(0);

    private final AtomicInteger _completeFramesReceived = new AtomicInteger(0);
    private final AtomicInteger _packetsSent = new AtomicInteger(0);

    private final ConcurrentHashMap<PacketType, Supplier<byte[]>> _dataSuppliers = new ConcurrentHashMap<>();
    private Consumer<CompleteData> _onCompleteDataReceived = data -> {};

    private final List<Connection> _connections = Collections.synchronizedList(new ArrayList<>());

    // Changed to AtomicReference for thread-safe access and null checks
    private final AtomicReference<DatagramSocket> _receiveSocketRef = new AtomicReference<>();

    private Thread _receiveThread;
    private Thread _processThread;
    private Thread _cleanupThread;
    private Thread _frameCounterThread;
    private Thread _packetCounterThread;

    // Use AtomicBoolean instead of volatile for better thread safety with complex operations
    private final AtomicBoolean _isRunning = new AtomicBoolean(false);

    private PeerConnectionManager() {}

    public static PeerConnectionManager getInstance() {
        return INSTANCE;
    }

    public void setDataSupplier(PacketType type, Supplier<byte[]> supplier) {
        this._dataSuppliers.put(type, supplier);
    }

    public void setOnCompleteDataReceived(Consumer<CompleteData> callback) {
        this._onCompleteDataReceived = callback;
    }

    public void connectToParticipants() {
        // Use a proper synchronized shutdown before starting
        shutdown();

        // Only proceed if shutdown was successful
        if (_isRunning.compareAndSet(false, true)) {
            startReceiveThread();
            startProcessThread();
            startCleanupThread();
            startFrameCounterThread();
            startPacketCounterThread();
            _connections.clear();

            Room room = Room.getConnectedRoom();
            if (room == null) {
                Log.e(TAG, "Cannot connect to participants - no room connected");
                shutdown();
                return;
            }

            String self;
            User connectedUser = User.getConnectedUser();
            if (connectedUser != null) {
                self = connectedUser.getUsername();
            } else {
                self = "";
                Log.e(TAG, "Cannot connect to participants - no user connected");
                shutdown();
                return;
            }

            room.getParticipants().forEach((username, ip) -> {
                if (!username.equals(self)) {
                    _connections.add(createConnection(username, ip));
                }
            });

            Log.i(TAG, "Successfully connected to " + _connections.size() + " participants");
        } else {
            Log.w(TAG, "Cannot connect - manager is already running");
        }
    }

    public void shutdown() {
        Log.d(TAG, "Shutting down PeerConnectionManager...");

        // Set running flag first to prevent new operations
        if (!_isRunning.compareAndSet(true, false)) {
            // If already shutting down or not running, just clean up to be safe
            Log.d(TAG, "PeerConnectionManager already shut down or not running");
            cleanupResourcesUnsafe();
            return;
        }

        // Stop all send threads first
        synchronized (_connections) {
            for (Connection conn : _connections) {
                Thread sendThread = conn.getSendThread();
                if (sendThread != null && sendThread.isAlive()) {
                    sendThread.interrupt();
                    try {
                        sendThread.join(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        Log.w(TAG, "Interrupted while waiting for send thread to terminate");
                    }
                }
            }
            _connections.clear();
        }

        // Close the receive socket - do this safely
        DatagramSocket receiveSocket = _receiveSocketRef.getAndSet(null);
        if (receiveSocket != null && !receiveSocket.isClosed()) {
            receiveSocket.close();
            Log.d(TAG, "Receive socket closed.");
        }

        // Safely terminate all threads
        safelyTerminateThread(_receiveThread, "Receive");
        safelyTerminateThread(_processThread, "Process");
        safelyTerminateThread(_cleanupThread, "Cleanup");
        safelyTerminateThread(_frameCounterThread, "FrameCounter");
        safelyTerminateThread(_packetCounterThread, "PacketCounter");

        // Clear references and collections
        cleanupResourcesUnsafe();

        Log.d(TAG, "PeerConnectionManager shutdown complete.");
    }

    private void safelyTerminateThread(Thread thread, String threadName) {
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(1000);
                if (thread.isAlive()) {
                    Log.w(TAG, threadName + " thread did not terminate after timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Interrupted while waiting for " + threadName + " thread to terminate");
            }
        }
    }

    private void cleanupResourcesUnsafe() {
        // Clear all references to threads
        _receiveThread = null;
        _processThread = null;
        _cleanupThread = null;
        _frameCounterThread = null;
        _packetCounterThread = null;

        // Clear collections
        _incompleteFrames.clear();
        _packetQueue.clear();
    }

    private Connection createConnection(String username, String ip) {
        Thread sendThread = createSendThread(ip);
        sendThread.setDaemon(true);
        sendThread.start();
        return new Connection(username, ip, sendThread);
    }

    private void startReceiveThread() {
        if (_receiveThread != null && _receiveThread.isAlive()) {
            Log.w(TAG, "Receive thread already running, skipping start.");
            return;
        }

        _receiveThread = new Thread(() -> {
            DatagramSocket receiveSocket = null;

            try {
                // Create socket within the thread that uses it
                receiveSocket = new DatagramSocket(PORT);
                receiveSocket.setReceiveBufferSize(PACKET_SIZE * 10);
                receiveSocket.setSoTimeout(RECEIVE_SOCKET_TIMEOUT_MS);

                // Store in atomic reference for safe access from other threads
                _receiveSocketRef.set(receiveSocket);

                byte[] buffer = new byte[PACKET_SIZE * 2];

                while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        receiveSocket.receive(packet);

                        DataPacket parsedPacket = parsePacket(packet);

                        // Handle packet queue overflow more gracefully
                        if (!_packetQueue.offer(parsedPacket)) {
                            DataPacket dropped = _packetQueue.poll();
                            if (dropped != null) {
                                Log.v(TAG, "Packet queue full, dropped oldest packet");
                            }
                            _packetQueue.offer(parsedPacket);
                        }
                    } catch (SocketTimeoutException ste) {
                        // Expected timeout, just continue the loop
                    } catch (SocketException se) {
                        if (!_isRunning.get()) {
                            Log.d(TAG, "Receive socket closed during shutdown. Exiting receive thread gracefully.");
                        } else {
                            Log.e(TAG, "Unexpected SocketException while running: " + se.getMessage(), se);
                        }
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Receive thread error: " + e.getMessage(), e);
                    }
                }
            } catch (SocketException se) {
                Log.e(TAG, "Could not open receive socket on port " + PORT + ": " + se.getMessage(), se);
            } catch (Exception e) {
                Log.e(TAG, "Receive thread fatal error: " + e.getMessage(), e);
            } finally {
                // Always clean up socket in finally block
                DatagramSocket socketToClose = receiveSocket != null ? receiveSocket : _receiveSocketRef.getAndSet(null);
                if (socketToClose != null && !socketToClose.isClosed()) {
                    socketToClose.close();
                    Log.d(TAG, "Receive socket explicitly closed in finally block.");
                }
                Log.d(TAG, "Receive thread terminated.");
            }
        });

        _receiveThread.setName("PeerConnectionReceiver");
        _receiveThread.setDaemon(true);
        _receiveThread.setPriority(Thread.MAX_PRIORITY);
        _receiveThread.start();
        Log.d(TAG, "Receive thread started.");
    }

    private void startProcessThread() {
        if (_processThread != null && _processThread.isAlive()) return;

        _processThread = new Thread(() -> {
            List<DataPacket> batchPackets = new ArrayList<>();

            while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    batchPackets.clear();

                    // Use poll with a timeout to prevent indefinite blocking
                    DataPacket head = _packetQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (head == null) {
                        continue;
                    }

                    batchPackets.add(head);
                    _packetQueue.drainTo(batchPackets, 19); // Try to process up to 20 at once (1 + 19)

                    for (DataPacket packet : batchPackets) {
                        processReceivedPacket(packet);
                    }
                } catch (InterruptedException e) {
                    Log.d(TAG, "Process thread interrupted. Exiting.");
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Log.e(TAG, "Process thread error: " + e.getMessage(), e);
                }
            }
            Log.d(TAG, "Process thread terminated.");
        });

        _processThread.setName("PeerConnectionProcessor");
        _processThread.setDaemon(true);
        _processThread.setPriority(Thread.NORM_PRIORITY + 2);
        _processThread.start();
    }

    private void startCleanupThread() {
        if (_cleanupThread != null && _cleanupThread.isAlive()) return;

        _cleanupThread = new Thread(() -> {
            while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CLEANUP_MS);
                    cleanupOldFrames();
                } catch (InterruptedException e) {
                    Log.d(TAG, "Cleanup thread interrupted. Exiting.");
                    Thread.currentThread().interrupt();
                }
            }
            Log.d(TAG, "Cleanup thread terminated.");
        });

        _cleanupThread.setName("PeerConnectionCleaner");
        _cleanupThread.setDaemon(true);
        _cleanupThread.start();
    }

    private void startFrameCounterThread() {
        if (_frameCounterThread != null && _frameCounterThread.isAlive()) return;

        _frameCounterThread = new Thread(() -> {
            while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1000);
                    int count = _completeFramesReceived.getAndSet(0);
                    Log.i(TAG, "Complete frames received in last second: " + count);
                } catch (InterruptedException e) {
                    Log.d(TAG, "Frame Counter thread interrupted. Exiting.");
                    Thread.currentThread().interrupt();
                }
            }
            Log.d(TAG, "Frame Counter thread terminated.");
        });

        _frameCounterThread.setName("FrameCounterLogger");
        _frameCounterThread.setDaemon(true);
        _frameCounterThread.start();
    }

    private void startPacketCounterThread() {
        if (_packetCounterThread != null && _packetCounterThread.isAlive()) return;

        _packetCounterThread = new Thread(() -> {
            while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(1000);
                    int count = _packetsSent.getAndSet(0);
                    Log.i(TAG, "Packets sent in last second: " + count);
                } catch (InterruptedException e) {
                    Log.d(TAG, "Packet Counter thread interrupted. Exiting.");
                    Thread.currentThread().interrupt();
                }
            }
            Log.d(TAG, "Packet Counter thread terminated.");
        });

        _packetCounterThread.setName("PacketCounterLogger");
        _packetCounterThread.setDaemon(true);
        _packetCounterThread.start();
    }

    private void cleanupOldFrames() {
        long currentTime = System.currentTimeMillis();
        long cutoffTime = currentTime - CLEANUP_MS;

        int removedFrames = 0;
        for (Iterator<Map.Entry<FrameIdentifier, List<DataPacket>>> it = _incompleteFrames.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<FrameIdentifier, List<DataPacket>> entry = it.next();
            if (entry.getKey().getTimestamp() < cutoffTime) {
                it.remove();
                removedFrames++;
            }
        }

        if (removedFrames > 0) {
            Log.d(TAG, "Cleaned up " + removedFrames + " stale incomplete frames");
        }
    }

    private DataPacket parsePacket(DatagramPacket datagram) {
        try {
            byte[] data = datagram.getData();
            ByteBuffer buffer = ByteBuffer.wrap(data, 0, datagram.getLength());

            byte[] usernameBytes = new byte[8];
            buffer.get(usernameBytes);
            String username = new String(usernameBytes).trim();

            long timestamp = buffer.getLong();
            int sequence = buffer.getInt();
            int total = buffer.getInt();
            byte packetTypeByte = buffer.get();
            PacketType packetType = PacketType.fromByte(packetTypeByte);

            int payloadLength = datagram.getLength() - buffer.position();
            byte[] payload = new byte[payloadLength];
            buffer.get(payload);

            return new DataPacket(username, timestamp, sequence, total, packetType, payload);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing packet: " + e.getMessage(), e);
            // Return a properly formatted but empty packet to avoid null pointer exceptions
            return new DataPacket("error", System.currentTimeMillis(), 0, 1,
                    PacketType.fromByte((byte)0), new byte[0]);
        }
    }

    private void processReceivedPacket(DataPacket packet) {
        // Skip processing invalid packets
        if (packet == null || packet.getPayload() == null || packet.getUsername() == null) {
            Log.w(TAG, "Received invalid packet, skipping");
            return;
        }

        // Skip outdated packets
        if (packet.getTimestamp() < _latestTimestamp.get() - CLEANUP_MS) {
            return;
        }

        // Update latest timestamp if newer
        if (packet.getTimestamp() > _latestTimestamp.get()) {
            _latestTimestamp.set(packet.getTimestamp());
        }

        FrameIdentifier key = new FrameIdentifier(packet.getTimestamp(), packet.getUsername(), packet.getPacketType());

        // Use computeIfAbsent for atomic creation of new packet list if needed
        List<DataPacket> packets = _incompleteFrames.computeIfAbsent(key, k ->
                Collections.synchronizedList(new ArrayList<>(packet.getTotalPackets())));

        synchronized (packets) {
            // Skip duplicates
            if (packets.stream().anyMatch(p -> p.getSequenceNumber() == packet.getSequenceNumber())) {
                return;
            }

            packets.add(packet);

            // Sort and validate sequence numbers
            if (packets.size() > 1) {
                packets.sort(Comparator.comparingInt(DataPacket::getSequenceNumber));

                // Validate sequence numbers are continuous
                boolean hasGaps = false;
                for (int i = 0; i < packets.size() - 1; i++) {
                    if (packets.get(i + 1).getSequenceNumber() - packets.get(i).getSequenceNumber() != 1) {
                        hasGaps = true;
                        break;
                    }
                }

                if (hasGaps) {
                    // Don't process yet, wait for missing packets
                    return;
                }
            }

            // Check if we've received all packets
            if (packets.size() == packet.getTotalPackets()) {
                try {
                    // Verify the expected sequence range is complete (0 to total-1)
                    if (packets.get(0).getSequenceNumber() != 0 ||
                            packets.get(packets.size() - 1).getSequenceNumber() != packet.getTotalPackets() - 1) {
                        Log.w(TAG, "Frame has missing packets. Expected 0-" + (packet.getTotalPackets() - 1) +
                                ", got " + packets.get(0).getSequenceNumber() + "-" +
                                packets.get(packets.size() - 1).getSequenceNumber());
                        return;
                    }

                    byte[] complete = assemblePackets(packets);
                    if (complete.length > 0) {
                        CompleteData completedData = new CompleteData(
                                packet.getUsername(),
                                packet.getTimestamp(),
                                packet.getPacketType(),
                                complete
                        );

                        // Pass complete data to listener
                        try {
                            _onCompleteDataReceived.accept(completedData);
                            _completeFramesReceived.incrementAndGet();
                        } catch (Exception e) {
                            Log.e(TAG, "Error in complete data callback: " + e.getMessage(), e);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error assembling packets for " + key + ": " + e.getMessage(), e);
                } finally {
                    // Always remove after processing regardless of outcome
                    _incompleteFrames.remove(key);
                }
            }
        }
    }

    private byte[] assemblePackets(List<DataPacket> packets) {
        if (packets == null || packets.isEmpty()) {
            return new byte[0];
        }

        // Calculate total size and verify each packet
        int totalSize = 0;
        for (DataPacket packet : packets) {
            if (packet == null || packet.getPayload() == null) {
                Log.e(TAG, "Null packet or payload found during assembly");
                return new byte[0];
            }
            totalSize += packet.getPayload().length;
        }

        try {
            ByteBuffer buffer = ByteBuffer.allocate(totalSize);
            for (DataPacket packet : packets) {
                buffer.put(packet.getPayload());
            }
            return buffer.array();
        } catch (Exception e) {
            Log.e(TAG, "Error assembling packets: " + e.getMessage(), e);
            return new byte[0];
        }
    }

    private Thread createSendThread(String receiverIp) {
        return new Thread(() -> {
            DatagramSocket socket = null;

            try {
                socket = new DatagramSocket();
                socket.setSendBufferSize(PACKET_SIZE * 10);

                // Track last sent data by type
                Map<PacketType, byte[]> lastSentData = new ConcurrentHashMap<>();

                while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        boolean dataSentThisIteration = false;

                        for (Map.Entry<PacketType, Supplier<byte[]>> entry : _dataSuppliers.entrySet()) {
                            if (!_isRunning.get() || Thread.currentThread().isInterrupted()) {
                                break;
                            }

                            PacketType type = entry.getKey();
                            Supplier<byte[]> supplier = entry.getValue();

                            // Safely get data from supplier
                            byte[] data = null;
                            try {
                                data = supplier.get();
                            } catch (Exception e) {
                                Log.e(TAG, "Error getting data from supplier for type " + type + ": " + e.getMessage(), e);
                                continue;
                            }

                            // Check data is valid and changed
                            byte[] currentLastSent = lastSentData.get(type);
                            if (data != null && data.length > 0 && !Arrays.equals(data, currentLastSent)) {
                                if (socket.isClosed()) {
                                    Log.w(TAG, "Socket closed, recreating");
                                    socket = new DatagramSocket();
                                    socket.setSendBufferSize(PACKET_SIZE * 10);
                                }

                                try {
                                    sendPackets(socket, data, receiverIp, type);
                                    lastSentData.put(type, Arrays.copyOf(data, data.length));
                                    dataSentThisIteration = true;
                                } catch (Exception e) {
                                    Log.e(TAG, "Error sending data: " + e.getMessage(), e);
                                }
                            }
                        }

                        // Sleep to prevent CPU hogging
                        if (!dataSentThisIteration) {
                            Thread.sleep(5);
                        } else {
                            Thread.sleep(1);
                        }
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Send thread interrupted. Exiting.");
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        if (_isRunning.get()) {
                            Log.e(TAG, "Send thread error: " + e.getMessage(), e);
                            Thread.sleep(RETRY_DELAY_MS);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Send thread fatal error: " + e.getMessage(), e);
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                    Log.d(TAG, "Send socket closed.");
                }
                Log.d(TAG, "Send thread terminated.");
            }
        });
    }


    private void sendPackets(DatagramSocket socket, byte[] data, String receiverIp, PacketType type) throws Exception {
        // Fail fast if conditions aren't right
        if (socket == null || socket.isClosed()) {
            throw new SocketException("Socket is closed or null, cannot send packets.");
        }

        if (data == null || data.length == 0) {
            Log.w(TAG, "Attempted to send empty data for type: " + type);
            return;
        }

        if (!_isRunning.get()) {
            Log.d(TAG, "PeerConnectionManager is shutting down, canceling packet send");
            return;
        }

        try {
            // Get username safely
            User user = User.getConnectedUser();
            if (user == null) {
                Log.e(TAG, "No connected user, cannot send packets");
                return;
            }

            byte[] usernameBytes = Arrays.copyOf(user.getUsername().getBytes(), 8);
            long timestamp = System.currentTimeMillis();
            byte[] timestampBytes = ByteBuffer.allocate(Long.BYTES).putLong(timestamp).array();
            byte packetTypeByte = type.toByte();

            int headerSize = 8 + Long.BYTES + Integer.BYTES * 2 + Byte.BYTES;
            int payloadSize = PACKET_SIZE - headerSize;
            int totalPackets = (int) Math.ceil((double) data.length / payloadSize);

            // Sanity check for total packets
            if (totalPackets <= 0) totalPackets = 1;
            if (totalPackets > 10000) {
                Log.e(TAG, "Unreasonably large packet count: " + totalPackets + ". Data size: " + data.length);
                return;
            }

            for (int i = 0; i < totalPackets; i++) {
                if (!_isRunning.get() || Thread.currentThread().isInterrupted()) {
                    Log.d(TAG, "Sending interrupted, sent " + i + "/" + totalPackets + " packets");
                    return;
                }

                int start = i * payloadSize;
                int end = Math.min(start + payloadSize, data.length);
                byte[] payload = Arrays.copyOfRange(data, start, end);

                ByteBuffer packetBuffer = ByteBuffer.allocate(headerSize + payload.length);
                packetBuffer.put(usernameBytes);
                packetBuffer.put(timestampBytes);
                packetBuffer.putInt(i);
                packetBuffer.putInt(totalPackets);
                packetBuffer.put(packetTypeByte);
                packetBuffer.put(payload);

                sendAndRetry(socket, packetBuffer.array(), receiverIp);
                _packetsSent.incrementAndGet();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending packets: " + e.getMessage(), e);
            throw e;
        }
    }

    private void sendAndRetry(DatagramSocket socket, byte[] packetData, String receiverIp) throws Exception {
        Exception lastException = null;
        boolean sent = false;

        for (int attempt = 0; attempt < MAX_RETRIES && !sent; attempt++) {
            try {
                // Check if we should still be sending
                if (!_isRunning.get() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Sending interrupted");
                }

                // Check if socket is still valid
                if (socket.isClosed()) {
                    throw new SocketException("Socket closed");
                }

                InetAddress address = InetAddress.getByName(receiverIp);
                DatagramPacket packet = new DatagramPacket(packetData, packetData.length, address, PORT);
                socket.send(packet);
                sent = true;
                return;
            } catch (PortUnreachableException pue) {
                Log.w(TAG, "Port unreachable for " + receiverIp + ":" + PORT + " (Attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")");
                lastException = pue;
            } catch (SocketException se) {
                Log.e(TAG, "Socket exception: " + se.getMessage());
                lastException = se;

                if (!socket.isClosed()) {
                    Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                } else {
                    throw se; // Unrecoverable
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie; // Propagate interruption
            } catch (Exception e) {
                Log.e(TAG, "Error sending to " + receiverIp + ":" + PORT + " (Attempt " + (attempt + 1) + "/" + MAX_RETRIES + "): " + e.getMessage());
                lastException = e;
            }

            if (!sent && attempt < MAX_RETRIES - 1) {
                Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
            }
        }

        if (!sent && lastException != null) {
            throw lastException;
        }
    }
}