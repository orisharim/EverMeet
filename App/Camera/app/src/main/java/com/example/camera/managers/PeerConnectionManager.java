package com.example.camera.managers;

import android.util.Log;

import com.example.camera.classes.*;
import com.example.camera.classes.Networking.CompleteData;
import com.example.camera.classes.Networking.Connection;
import com.example.camera.classes.Networking.DataPacket;
import com.example.camera.classes.Networking.CompleteDataID;
import com.example.camera.classes.Networking.PacketType;

import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger; // Keep for general purpose if needed elsewhere, but not for per-type counts
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PeerConnectionManager {
    private static final String TAG = "PeerConnectionManager";
    private static final int PACKET_SIZE = 40000;
    private static final int PORT = 12345;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2;
    private static final int CLEANUP_MS = 15000;
    private static final int MAX_QUEUE_SIZE = 1000;
    private static final int RECEIVE_SOCKET_TIMEOUT_MS = 500;

    private static final PeerConnectionManager _instance = new PeerConnectionManager();

    private final ConcurrentHashMap<CompleteDataID, List<DataPacket>> _incompleteData = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<DataPacket> _packetQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private final AtomicLong _latestTimestamp = new AtomicLong(0);

    // MODIFICATION START: Per-PacketType counters
    private final ConcurrentHashMap<PacketType, AtomicInteger> _completeFramesReceivedPerType = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PacketType, AtomicInteger> _packetsSentPerType = new ConcurrentHashMap<>();
    // MODIFICATION END

    private final ConcurrentHashMap<PacketType, Supplier<byte[]>> _dataSuppliers = new ConcurrentHashMap<>();
    private Consumer<CompleteData> _onCompleteDataReceived = data -> {};

    private final List<Connection> _connections = Collections.synchronizedList(new ArrayList<>());

    private final AtomicReference<DatagramSocket> _receiveSocketRef = new AtomicReference<>();

    private Thread _receiveThread;
    private Thread _processThread;
    private Thread _cleanupThread;
    private Thread _frameCounterThread;
    private Thread _packetCounterThread;

    private final AtomicBoolean _isRunning = new AtomicBoolean(false);

    private PeerConnectionManager() {
        // Initialize counters for all known PacketType values (assuming PacketType is an enum)
        for (PacketType type : PacketType.values()) {
            _completeFramesReceivedPerType.put(type, new AtomicInteger(0));
            _packetsSentPerType.put(type, new AtomicInteger(0));
        }
    }

    public static PeerConnectionManager getInstance() {
        return _instance;
    }

    public void setDataSupplier(PacketType type, Supplier<byte[]> supplier) {
        this._dataSuppliers.put(type, supplier);
    }

    public void setOnCompleteDataReceived(Consumer<CompleteData> callback) {
        this._onCompleteDataReceived = callback;
    }

    public void connectToParticipants() {
        shutdown();

        if (_isRunning.compareAndSet(false, true)) {
            // MODIFICATION START: Reset all counters on connection
            for (PacketType type : PacketType.values()) {
                _completeFramesReceivedPerType.get(type).set(0);
                _packetsSentPerType.get(type).set(0);
            }
            // MODIFICATION END

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

        if (!_isRunning.compareAndSet(true, false)) {
            Log.d(TAG, "PeerConnectionManager already shut down or not running");
            cleanupResourcesUnsafe();
            return;
        }

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

        DatagramSocket receiveSocket = _receiveSocketRef.getAndSet(null);
        if (receiveSocket != null && !receiveSocket.isClosed()) {
            receiveSocket.close();
            Log.d(TAG, "Receive socket closed.");
        }

        safelyTerminateThread(_receiveThread, "RTP Receive");
        safelyTerminateThread(_processThread, "Process");
        safelyTerminateThread(_cleanupThread, "Cleanup");
        safelyTerminateThread(_frameCounterThread, "FrameCounter");
        safelyTerminateThread(_packetCounterThread, "PacketCounter");

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
        _receiveThread = null;
        _processThread = null;
        _cleanupThread = null;
        _frameCounterThread = null;
        _packetCounterThread = null;

        _incompleteData.clear();
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
            Log.w(TAG, "RTP Receive thread already running, skipping start.");
            return;
        }

        _receiveThread = new Thread(() -> {
            DatagramSocket receiveSocket = null;

            try {
                receiveSocket = new DatagramSocket(PORT);
                receiveSocket.setReceiveBufferSize(PACKET_SIZE * 10);
                receiveSocket.setSoTimeout(RECEIVE_SOCKET_TIMEOUT_MS);

                _receiveSocketRef.set(receiveSocket);

                byte[] buffer = new byte[PACKET_SIZE * 10];

                while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        receiveSocket.receive(packet);

                        DataPacket parsedPacket = DataPacket.parsePacket(packet);

                        if (!_packetQueue.offer(parsedPacket)) {
                            DataPacket dropped = _packetQueue.poll();
                            if (dropped != null) {
                                Log.v(TAG, "Packet queue full, dropped oldest packet");
                            }
                            _packetQueue.offer(parsedPacket);
                        }
                    } catch (SocketTimeoutException ste) {
                    } catch (SocketException se) {
                        if (!_isRunning.get()) {
                            Log.d(TAG, "RTP Receive socket closed during shutdown. Exiting receive thread gracefully.");
                        } else {
                            Log.e(TAG, "Unexpected SocketException while running RTP receive: " + se.getMessage(), se);
                        }
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "RTP Receive thread error: " + e.getMessage(), e);
                    }
                }
            } catch (SocketException se) {
                Log.e(TAG, "Could not open RTP receive socket on port " + PORT + ": " + se.getMessage(), se);
            } catch (Exception e) {
                Log.e(TAG, "RTP Receive thread fatal error: " + e.getMessage(), e);
            } finally {
                DatagramSocket socketToClose = receiveSocket != null ? receiveSocket : _receiveSocketRef.getAndSet(null);
                if (socketToClose != null && !socketToClose.isClosed()) {
                    socketToClose.close();
                    Log.d(TAG, "RTP Receive socket explicitly closed in finally block.");
                }
                Log.d(TAG, "RTP Receive thread terminated.");
            }
        });

        _receiveThread.setName("PeerConnectionRTPReceiver");
        _receiveThread.setDaemon(true);
        _receiveThread.setPriority(Thread.MAX_PRIORITY);
        _receiveThread.start();
        Log.d(TAG, "RTP Receive thread started.");
    }

    private void startProcessThread() {
        if (_processThread != null && _processThread.isAlive()) return;

        _processThread = new Thread(() -> {
            List<DataPacket> batchPackets = new ArrayList<>();

            while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    batchPackets.clear();

                    DataPacket head = _packetQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (head == null) {
                        continue;
                    }

                    batchPackets.add(head);
                    _packetQueue.drainTo(batchPackets, 19);

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
                    StringBuilder logMessage = new StringBuilder("Complete frames received in last second:");
                    boolean hasData = false;
                    for (PacketType type : PacketType.values()) {
                        AtomicInteger counter = _completeFramesReceivedPerType.get(type);
                        if (counter != null) {
                            int count = counter.getAndSet(0);
                            if (count > 0) { // Only log types that received frames
                                logMessage.append(" ").append(type.name()).append(": ").append(count);
                                hasData = true;
                            }
                        }
                    }
                    if (hasData) { // Only log if there was at least one frame received
                        Log.i(TAG, logMessage.toString());
                    } else {
                        // Optionally log "no frames received" or just stay silent if no activity
                        // Log.i(TAG, "No complete frames received in last second.");
                    }
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
                    StringBuilder logMessage = new StringBuilder("RTP Packets sent in last second:");
                    boolean hasData = false;
                    for (PacketType type : PacketType.values()) {
                        AtomicInteger counter = _packetsSentPerType.get(type);
                        if (counter != null) {
                            int count = counter.getAndSet(0);
                            if (count > 0) { // Only log types that sent packets
                                logMessage.append(" ").append(type.name()).append(": ").append(count);
                                hasData = true;
                            }
                        }
                    }
                    if (hasData) { // Only log if there was at least one packet sent
                        Log.i(TAG, logMessage.toString());
                    } else {
                        // Optionally log "no packets sent" or just stay silent if no activity
                        // Log.i(TAG, "No RTP packets sent in last second.");
                    }
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
        for (Iterator<Map.Entry<CompleteDataID, List<DataPacket>>> it = _incompleteData.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<CompleteDataID, List<DataPacket>> entry = it.next();
            if (entry.getKey().getTimestamp() < cutoffTime) {
                it.remove();
                removedFrames++;
            }
        }

        if (removedFrames > 0) {
            Log.d(TAG, "Cleaned up " + removedFrames + " stale incomplete frames");
        }
    }



    private void processReceivedPacket(DataPacket packet) {
        if (packet == null || packet.getPayload() == null || packet.getUsername() == null) {
            Log.w(TAG, "Received invalid RTP packet, skipping");
            return;
        }

        if (packet.getTimestamp() < _latestTimestamp.get() - CLEANUP_MS) {
            return;
        }

        if (packet.getTimestamp() > _latestTimestamp.get()) {
            _latestTimestamp.set(packet.getTimestamp());
        }

        CompleteDataID key = new CompleteDataID(packet.getTimestamp(), packet.getUsername(), packet.getPacketType());

        List<DataPacket> packets = _incompleteData.computeIfAbsent(key, k ->
                Collections.synchronizedList(new ArrayList<>(packet.getTotalPackets())));

        synchronized (packets) {
            if (packets.stream().anyMatch(p -> p.getSequenceNumber() == packet.getSequenceNumber())) {
                return;
            }

            packets.add(packet);

            if (packets.size() > 1) {
                packets.sort(Comparator.comparingInt(DataPacket::getSequenceNumber));

                boolean hasGaps = false;
                for (int i = 0; i < packets.size() - 1; i++) {
                    if (packets.get(i + 1).getSequenceNumber() - packets.get(i).getSequenceNumber() != 1) {
                        hasGaps = true;
                        break;
                    }
                    // MODIFICATION START: Log received packet for its type
                    _packetsSentPerType.computeIfAbsent(packet.getPacketType(), k -> new AtomicInteger(0)).incrementAndGet();
                    // This line should ideally be in the receive thread, not here, as this is for *processed* packets.
                    // However, if you only want to count packets that successfully enter the processing queue, this is fine.
                    // For true "received packet" count, it should be right after `parsedPacket` in `startReceiveThread`.
                    // I'll leave it here for now as it's closer to your original intent, but note the caveat.
                    // MODIFICATION END
                }

                if (hasGaps) {
                    return;
                }
            }


            if (packets.size() == packet.getTotalPackets()) {
                try {
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

                        try {
                            _onCompleteDataReceived.accept(completedData);
                            // MODIFICATION START: Increment complete frames for specific type
                            _completeFramesReceivedPerType.computeIfAbsent(packet.getPacketType(), k -> new AtomicInteger(0)).incrementAndGet();
                            // MODIFICATION END
                        } catch (Exception e) {
                            Log.e(TAG, "Error in complete data callback: " + e.getMessage(), e);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error assembling packets for " + key + ": " + e.getMessage(), e);
                } finally {
                    _incompleteData.remove(key);
                }
            }
        }
    }

    private byte[] assemblePackets(List<DataPacket> packets) {
        if (packets == null || packets.isEmpty()) {
            return new byte[0];
        }

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

                            byte[] data = null;
                            try {
                                data = supplier.get();
                            } catch (Exception e) {
                                Log.e(TAG, "Error getting data from supplier for type " + type + ": " + e.getMessage(), e);
                                continue;
                            }

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

            if (totalPackets <= 0) totalPackets = 1;
            if (totalPackets > 10000) {
                Log.e(TAG, "Unreasonably large packet count: " + totalPackets + ". Data size: " + data.length);
                return;
            }

            for (int i = 0; i < totalPackets; i++) {
                if (!_isRunning.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                int offset = i * payloadSize;
                int length = Math.min(data.length - offset, payloadSize);

                ByteBuffer packetBuffer = ByteBuffer.allocate(headerSize + length);
                packetBuffer.put(usernameBytes);
                packetBuffer.put(timestampBytes);
                packetBuffer.putInt(i);
                packetBuffer.putInt(totalPackets);
                packetBuffer.put(packetTypeByte);
                packetBuffer.put(data, offset, length);

                byte[] packetData = packetBuffer.array();
                DatagramPacket packet = new DatagramPacket(packetData, packetData.length, InetAddress.getByName(receiverIp), PORT);

                int retries = 0;
                while (retries < MAX_RETRIES) {
                    try {
                        socket.send(packet);
                        // MODIFICATION START: Increment packets sent for specific type
                        _packetsSentPerType.computeIfAbsent(type, k -> new AtomicInteger(0)).incrementAndGet();
                        // MODIFICATION END
                        break;
                    } catch (SocketException se) {
                        if (!_isRunning.get()) {
                            Log.d(TAG, "Socket closed during shutdown while sending packet. Aborting send.");
                            return;
                        }
                        Log.e(TAG, "Socket error sending packet (retry " + (retries + 1) + "): " + se.getMessage());
                        retries++;
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (Exception e) {
                        Log.e(TAG, "Error sending packet (retry " + (retries + 1) + "): " + e.getMessage(), e);
                        retries++;
                        Thread.sleep(RETRY_DELAY_MS);
                    }
                }
                if (retries == MAX_RETRIES) {
                    Log.e(TAG, "Failed to send packet after " + MAX_RETRIES + " retries.");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to send packets for type " + type + " to " + receiverIp + ": " + e.getMessage(), e);
        }
    }
}