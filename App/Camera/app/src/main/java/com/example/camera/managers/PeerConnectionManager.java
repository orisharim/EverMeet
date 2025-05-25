package com.example.camera.managers;

import android.util.Log;

import com.example.camera.classes.*;
import com.example.camera.classes.Networking.RTP.CompleteData;
import com.example.camera.classes.Networking.RTP.Connection;
import com.example.camera.classes.Networking.RTP.DataPacket;
import com.example.camera.classes.Networking.RTP.FrameIdentifier;
import com.example.camera.classes.Networking.RTP.PacketType;

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
    private static final int PACKET_SIZE = 40000;
    private static final int RTP_PORT = 12345; // Renamed for clarity
    private static final int RTCP_PORT = 12346; // Dedicated RTCP port
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2;
    private static final int CLEANUP_MS = 15000;
    private static final int MAX_QUEUE_SIZE = 1000;
    private static final int RECEIVE_SOCKET_TIMEOUT_MS = 500;
    private static final int RTCP_INTERVAL_MS = 1000; // Send RTCP reports every 1 second

    private static final PeerConnectionManager _instance = new PeerConnectionManager();

    private final ConcurrentHashMap<FrameIdentifier, List<DataPacket>> _incompleteFrames = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<DataPacket> _packetQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private final AtomicLong _latestTimestamp = new AtomicLong(0);

    private final AtomicInteger _completeFramesReceived = new AtomicInteger(0);
    private final AtomicInteger _packetsSent = new AtomicInteger(0);
    private final AtomicInteger _rtpPacketsReceived = new AtomicInteger(0); // Track received RTP packets for RTCP

    private final ConcurrentHashMap<PacketType, Supplier<byte[]>> _dataSuppliers = new ConcurrentHashMap<>();
    private Consumer<CompleteData> _onCompleteDataReceived = data -> {};

    private final List<Connection> _connections = Collections.synchronizedList(new ArrayList<>());

    private final AtomicReference<DatagramSocket> _rtpReceiveSocketRef = new AtomicReference<>();
    private final AtomicReference<DatagramSocket> _rtcpReceiveSocketRef = new AtomicReference<>();


    private Thread _rtpReceiveThread;
    private Thread _processThread;
    private Thread _cleanupThread;
    private Thread _frameCounterThread;
    private Thread _packetCounterThread;
    private Thread _rtcpSendReceiveThread; // Combined thread for RTCP

    private final AtomicBoolean _isRunning = new AtomicBoolean(false);

    private final ConcurrentHashMap<String, Long> _lastRtpTimestampReceived = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> _lastRtcpSentTimestamp = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> _packetsExpected = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> _packetsReceived = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> _packetsLost = new ConcurrentHashMap<>();


    private PeerConnectionManager() {}

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
            startRTPReceiveThread();
            startRTCPThreads();
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
                    // Initialize RTCP state for new peer
                    _packetsExpected.put(username, 0);
                    _packetsReceived.put(username, 0);
                    _packetsLost.put(username, 0);
                    _lastRtpTimestampReceived.put(username, 0L);
                    _lastRtcpSentTimestamp.put(username, 0L); // Initialize last RTCP sent timestamp
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

        DatagramSocket rtpReceiveSocket = _rtpReceiveSocketRef.getAndSet(null);
        if (rtpReceiveSocket != null && !rtpReceiveSocket.isClosed()) {
            rtpReceiveSocket.close();
            Log.d(TAG, "RTP Receive socket closed.");
        }

        DatagramSocket rtcpReceiveSocket = _rtcpReceiveSocketRef.getAndSet(null);
        if (rtcpReceiveSocket != null && !rtcpReceiveSocket.isClosed()) {
            rtcpReceiveSocket.close();
            Log.d(TAG, "RTCP Receive socket closed.");
        }

        safelyTerminateThread(_rtpReceiveThread, "RTP Receive");
        safelyTerminateThread(_processThread, "Process");
        safelyTerminateThread(_cleanupThread, "Cleanup");
        safelyTerminateThread(_frameCounterThread, "FrameCounter");
        safelyTerminateThread(_packetCounterThread, "PacketCounter");
        safelyTerminateThread(_rtcpSendReceiveThread, "RTCP Send/Receive");

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
        _rtpReceiveThread = null;
        _processThread = null;
        _cleanupThread = null;
        _frameCounterThread = null;
        _packetCounterThread = null;
        _rtcpSendReceiveThread = null;

        _incompleteFrames.clear();
        _packetQueue.clear();
        _lastRtpTimestampReceived.clear();
        _packetsExpected.clear();
        _packetsReceived.clear();
        _packetsLost.clear();
        _lastRtcpSentTimestamp.clear();
    }

    private Connection createConnection(String username, String ip) {
        Thread sendThread = createSendThread(ip);
        sendThread.setDaemon(true);
        sendThread.start();
        return new Connection(username, ip, sendThread);
    }

    private void startRTPReceiveThread() {
        if (_rtpReceiveThread != null && _rtpReceiveThread.isAlive()) {
            Log.w(TAG, "RTP Receive thread already running, skipping start.");
            return;
        }

        _rtpReceiveThread = new Thread(() -> {
            DatagramSocket receiveSocket = null;

            try {
                receiveSocket = new DatagramSocket(RTP_PORT);
                receiveSocket.setReceiveBufferSize(PACKET_SIZE * 10);
                receiveSocket.setSoTimeout(RECEIVE_SOCKET_TIMEOUT_MS);

                _rtpReceiveSocketRef.set(receiveSocket);

                byte[] buffer = new byte[PACKET_SIZE * 10];

                while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        receiveSocket.receive(packet);

                        DataPacket parsedPacket = parsePacket(packet);

                        if (!_packetQueue.offer(parsedPacket)) {
                            DataPacket dropped = _packetQueue.poll();
                            if (dropped != null) {
                                Log.v(TAG, "Packet queue full, dropped oldest packet");
                            }
                            _packetQueue.offer(parsedPacket);
                        }
                        _rtpPacketsReceived.incrementAndGet(); // Increment counter for RTCP
                    } catch (SocketTimeoutException ste) {
                        // Expected timeout
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
                Log.e(TAG, "Could not open RTP receive socket on port " + RTP_PORT + ": " + se.getMessage(), se);
            } catch (Exception e) {
                Log.e(TAG, "RTP Receive thread fatal error: " + e.getMessage(), e);
            } finally {
                DatagramSocket socketToClose = receiveSocket != null ? receiveSocket : _rtpReceiveSocketRef.getAndSet(null);
                if (socketToClose != null && !socketToClose.isClosed()) {
                    socketToClose.close();
                    Log.d(TAG, "RTP Receive socket explicitly closed in finally block.");
                }
                Log.d(TAG, "RTP Receive thread terminated.");
            }
        });

        _rtpReceiveThread.setName("PeerConnectionRTPReceiver");
        _rtpReceiveThread.setDaemon(true);
        _rtpReceiveThread.setPriority(Thread.MAX_PRIORITY);
        _rtpReceiveThread.start();
        Log.d(TAG, "RTP Receive thread started.");
    }

    private void startRTCPThreads() {
        if (_rtcpSendReceiveThread != null && _rtcpSendReceiveThread.isAlive()) {
            Log.w(TAG, "RTCP Send/Receive thread already running, skipping start.");
            return;
        }

        _rtcpSendReceiveThread = new Thread(() -> {
            DatagramSocket rtcpSocket = null;
            try {
                rtcpSocket = new DatagramSocket(RTCP_PORT);
                rtcpSocket.setReceiveBufferSize(PACKET_SIZE); // RTCP packets are small
                rtcpSocket.setSoTimeout(RTCP_INTERVAL_MS / 2); // Shorter timeout for polling

                _rtcpReceiveSocketRef.set(rtcpSocket);

                byte[] buffer = new byte[PACKET_SIZE];

                while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        // --- Send RTCP Reports ---
                        long currentTime = System.currentTimeMillis();
                        synchronized (_connections) {
                            for (Connection conn : _connections) {
                                if (currentTime - _lastRtcpSentTimestamp.getOrDefault(conn.getUsername(), 0L) >= RTCP_INTERVAL_MS) {
                                    sendRTCPReport(rtcpSocket, conn.getUserIp(), conn.getUsername());
                                    _lastRtcpSentTimestamp.put(conn.getUsername(), currentTime);
                                }
                            }
                        }

                        // --- Receive RTCP Reports ---
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        rtcpSocket.receive(packet); // This will block until timeout or packet

                        processRTCPPacket(packet);

                    } catch (SocketTimeoutException ste) {
                        // Expected timeout, just loop again to send and check for new packets
                    } catch (SocketException se) {
                        if (!_isRunning.get()) {
                            Log.d(TAG, "RTCP socket closed during shutdown. Exiting RTCP thread gracefully.");
                        } else {
                            Log.e(TAG, "Unexpected SocketException while running RTCP: " + se.getMessage(), se);
                        }
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "RTCP thread error: " + e.getMessage(), e);
                    }
                }
            } catch (SocketException se) {
                Log.e(TAG, "Could not open RTCP socket on port " + RTCP_PORT + ": " + se.getMessage(), se);
            } catch (Exception e) {
                Log.e(TAG, "RTCP thread fatal error: " + e.getMessage(), e);
            } finally {
                DatagramSocket socketToClose = rtcpSocket != null ? rtcpSocket : _rtcpReceiveSocketRef.getAndSet(null);
                if (socketToClose != null && !socketToClose.isClosed()) {
                    socketToClose.close();
                    Log.d(TAG, "RTCP socket explicitly closed in finally block.");
                }
                Log.d(TAG, "RTCP Send/Receive thread terminated.");
            }
        });
        _rtcpSendReceiveThread.setName("PeerConnectionRTCPSendReceive");
        _rtcpSendReceiveThread.setDaemon(true);
        _rtcpSendReceiveThread.setPriority(Thread.NORM_PRIORITY);
        _rtcpSendReceiveThread.start();
        Log.d(TAG, "RTCP Send/Receive thread started.");
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
                    int count = _completeFramesReceived.getAndSet(0);
                    int rtpCount = _rtpPacketsReceived.getAndSet(0); // Reset RTP packet counter
                    Log.i(TAG, "Complete frames received in last second: " + count + ", RTP packets received: " + rtpCount);
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
                    Log.i(TAG, "RTP Packets sent in last second: " + count);
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

            // Check if this is an RTCP packet based on port
            if (datagram.getPort() == RTCP_PORT) {
                // RTCP packets are processed directly, not queued as DataPackets
                // This scenario shouldn't happen if parsing is correctly separated,
                // but as a safeguard.
                Log.w(TAG, "RTCP packet received on RTP port, or parsePacket called incorrectly for RTCP.");
                return null;
            }

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
            Log.e(TAG, "Error parsing RTP packet: " + e.getMessage(), e);
            return null;
        }
    }

    private void processReceivedPacket(DataPacket packet) {
        if (packet == null || packet.getPayload() == null || packet.getUsername() == null) {
            Log.w(TAG, "Received invalid RTP packet, skipping");
            return;
        }

        // Update RTCP metrics for this sender
        String senderUsername = packet.getUsername();
        _packetsReceived.compute(senderUsername, (k, v) -> v == null ? 1 : v + 1);
        _lastRtpTimestampReceived.put(senderUsername, System.currentTimeMillis());

        if (packet.getTimestamp() < _latestTimestamp.get() - CLEANUP_MS) {
            return;
        }

        if (packet.getTimestamp() > _latestTimestamp.get()) {
            _latestTimestamp.set(packet.getTimestamp());
        }

        FrameIdentifier key = new FrameIdentifier(packet.getTimestamp(), packet.getUsername(), packet.getPacketType());

        List<DataPacket> packets = _incompleteFrames.computeIfAbsent(key, k ->
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
                }

                if (hasGaps) {
                    // Update expected packets for RTCP feedback
                    Integer currentExpected = _packetsExpected.getOrDefault(senderUsername, 0);
                    _packetsExpected.put(senderUsername, Math.max(currentExpected, packet.getTotalPackets()));
                    return;
                }
            }

            // Update expected packets for RTCP feedback
            Integer currentExpected = _packetsExpected.getOrDefault(senderUsername, 0);
            _packetsExpected.put(senderUsername, Math.max(currentExpected, packet.getTotalPackets()));


            if (packets.size() == packet.getTotalPackets()) {
                try {
                    if (packets.get(0).getSequenceNumber() != 0 ||
                            packets.get(packets.size() - 1).getSequenceNumber() != packet.getTotalPackets() - 1) {
                        Log.w(TAG, "Frame has missing packets. Expected 0-" + (packet.getTotalPackets() - 1) +
                                ", got " + packets.get(0).getSequenceNumber() + "-" +
                                packets.get(packets.size() - 1).getSequenceNumber());

                        // Increment lost packets count for RTCP
                        _packetsLost.compute(senderUsername, (k, v) -> v == null ? 1 : v + 1);
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
                            _completeFramesReceived.incrementAndGet();
                        } catch (Exception e) {
                            Log.e(TAG, "Error in complete data callback: " + e.getMessage(), e);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error assembling packets for " + key + ": " + e.getMessage(), e);
                } finally {
                    _incompleteFrames.remove(key);
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

                sendAndRetry(socket, packetBuffer.array(), receiverIp, RTP_PORT); // Send RTP
                _packetsSent.incrementAndGet();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending RTP packets: " + e.getMessage(), e);
            throw e;
        }
    }

    private void sendAndRetry(DatagramSocket socket, byte[] packetData, String receiverIp, int port) throws Exception {
        Exception lastException = null;
        boolean sent = false;

        for (int attempt = 0; attempt < MAX_RETRIES && !sent; attempt++) {
            try {
                if (!_isRunning.get() || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Sending interrupted");
                }

                if (socket.isClosed()) {
                    throw new SocketException("Socket closed");
                }

                InetAddress address = InetAddress.getByName(receiverIp);
                DatagramPacket packet = new DatagramPacket(packetData, packetData.length, address, port);
                socket.send(packet);
                sent = true;
                return;
            } catch (PortUnreachableException pue) {
                Log.w(TAG, "Port unreachable for " + receiverIp + ":" + port + " (Attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")");
                lastException = pue;
            } catch (SocketException se) {
                Log.e(TAG, "Socket exception: " + se.getMessage());
                lastException = se;

                if (!socket.isClosed()) {
                    Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                } else {
                    throw se;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception e) {
                Log.e(TAG, "Error sending to " + receiverIp + ":" + port + " (Attempt " + (attempt + 1) + "/" + MAX_RETRIES + "): " + e.getMessage());
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

    // --- RTCP Implementation ---

    private void sendRTCPReport(DatagramSocket socket, String receiverIp, String targetUsername) {
        try {
            User user = User.getConnectedUser();
            if (user == null) {
                Log.e(TAG, "No connected user, cannot send RTCP report.");
                return;
            }
            String senderUsername = user.getUsername();

            // For simplicity, we'll send a Receiver Report (RR) for the targetUsername
            // A full RTCP implementation would also send Sender Reports (SR) if this peer sends data.
            // For now, we focus on feedback from receiver to sender.

            int packetsExpected = _packetsExpected.getOrDefault(targetUsername, 0);
            int packetsReceived = _packetsReceived.getOrDefault(targetUsername, 0);
            int packetsLost = _packetsLost.getOrDefault(targetUsername, 0);

            // Calculate fraction lost (simple approximation)
            float fractionLost = 0.0f;
            if (packetsExpected > 0) {
                fractionLost = (float) packetsLost / packetsExpected;
            }

            // In a real scenario, you'd calculate jitter and RTT.
            // For stability, we'll just focus on loss for now.
            long lastRtpTimestamp = _lastRtpTimestampReceived.getOrDefault(targetUsername, 0L);
            long currentTime = System.currentTimeMillis();
            long delaySinceLastRTP = currentTime - lastRtpTimestamp;


            // Construct Receiver Report (RR) Packet
            // Version (2 bits) = 2
            // Padding (1 bit) = 0
            // Reception Report Count (5 bits) = 1 (for one sender)
            // Packet Type (8 bits) = 201 (RTCP_RR)
            // Length (16 bits) = 7 (words - header + 1 RR block) -> (4 bytes header + 24 bytes RR block) / 4 = 7
            // SSRC of sender of this RR (32 bits)
            // SSRC of source (32 bits) (SSRC of the RTP sender this report is about)
            // Fraction Lost (8 bits)
            // Cumulative Number of Packets Lost (24 bits)
            // Extended Highest Sequence Number Received (32 bits)
            // Interarrival Jitter (32 bits) - set to 0 for simplicity
            // Last SR Timestamp (32 bits) - set to 0 for simplicity
            // Delay Since Last SR (32 bits) - set to 0 for simplicity

            byte[] rtcpPacket = createReceiverReport(
                    senderUsername,  // SSRC of the sender of this RR
                    targetUsername,  // SSRC of the RTP source this RR is for
                    (int) (fractionLost * 255), // Convert fraction to 8-bit
                    packetsLost,
                    _lastRtpTimestampReceived.getOrDefault(targetUsername, 0L).intValue(), // Using timestamp as high sequence for simplicity
                    0, // Jitter
                    0, // Last SR Timestamp
                    0  // Delay Since Last SR
            );

            sendAndRetry(socket, rtcpPacket, receiverIp, RTCP_PORT);
            Log.d(TAG, "Sent RTCP RR to " + receiverIp + " for " + targetUsername +
                    ". Lost: " + packetsLost + ", Expected: " + packetsExpected + ", Fraction Lost: " + String.format("%.2f", fractionLost));

            // Reset counts after sending report
            _packetsExpected.put(targetUsername, 0);
            _packetsReceived.put(targetUsername, 0);
            _packetsLost.put(targetUsername, 0);

        } catch (Exception e) {
            Log.e(TAG, "Error sending RTCP report to " + receiverIp + ": " + e.getMessage(), e);
        }
    }

    private byte[] createReceiverReport(
            String ssrcSender,
            String ssrcSource,
            int fractionLost,
            int cumulativePacketsLost,
            int extendedHighestSeqNum,
            int interarrivalJitter,
            int lastSrTimestamp,
            int delaySinceLastSr) {

        ByteBuffer buffer = ByteBuffer.allocate(28); // 7 words * 4 bytes/word

        // RTCP Header (201 for Receiver Report, RC=1 for one report block)
        // V=2, P=0, RC=1, PT=201, Length=7 (words)
        byte headerByte1 = (byte) ((2 << 6) | (0 << 5) | 1); // Version 2, P=0, RC=1
        byte headerByte2 = (byte) 201; // PT = 201 (Receiver Report)
        short length = 7; // Length in 32-bit words, excluding header

        buffer.put(headerByte1);
        buffer.put(headerByte2);
        buffer.putShort(length);

        // SSRC of packet sender (this peer)
        buffer.putInt(ssrcSender.hashCode()); // Simple SSRC from hashcode

        // Reception Report Block (one block)
        buffer.putInt(ssrcSource.hashCode()); // SSRC of the source (the RTP sender)
        buffer.put((byte) fractionLost); // Fraction Lost (8 bits)
        // Cumulative Number of Packets Lost (24 bits) - pad with 0 for byte alignment
        buffer.put((byte) ((cumulativePacketsLost >> 16) & 0xFF));
        buffer.putShort((short) (cumulativePacketsLost & 0xFFFF));
        buffer.putInt(extendedHighestSeqNum); // Extended Highest Sequence Number Received
        buffer.putInt(interarrivalJitter); // Interarrival Jitter
        buffer.putInt(lastSrTimestamp); // Last SR Timestamp (LSR)
        buffer.putInt(delaySinceLastSr); // Delay Since Last SR (DLSR)

        return buffer.array();
    }

    private void processRTCPPacket(DatagramPacket datagram) {
        try {
            byte[] data = datagram.getData();
            ByteBuffer buffer = ByteBuffer.wrap(data, 0, datagram.getLength());

            byte headerByte1 = buffer.get();
            byte headerByte2 = buffer.get();
            short length = buffer.getShort(); // Length in 32-bit words

            int version = (headerByte1 >> 6) & 0x03;
            int padding = (headerByte1 >> 5) & 0x01;
            int reportCount = headerByte1 & 0x1F; // RC for RR, SC for SR
            int packetType = headerByte2 & 0xFF;

            if (version != 2) {
                Log.w(TAG, "Received RTCP packet with unsupported version: " + version);
                return;
            }

            if (packetType == 200) { // Sender Report (SR)
                Log.d(TAG, "Received RTCP Sender Report (SR)");
                // SSRC of sender (32 bits)
                // NTP timestamp (64 bits)
                // RTP timestamp (32 bits)
                // Sender's packet count (32 bits)
                // Sender's octet count (32 bits)
                int ssrc = buffer.getInt();
                long ntpTimestamp = buffer.getLong();
                long rtpTimestamp = buffer.getInt() & 0xFFFFFFFFL; // Convert to unsigned long
                long senderPacketCount = buffer.getInt() & 0xFFFFFFFFL;
                long senderOctetCount = buffer.getInt() & 0xFFFFFFFFL;

                String peerUsername = findUsernameBySSRC(ssrc);
                if (peerUsername != null) {
                    Log.i(TAG, "SR from " + peerUsername + ": packets=" + senderPacketCount + ", octets=" + senderOctetCount);
                    // You could use this to track remote sender's stats and calculate RTT
                } else {
                    Log.w(TAG, "SR from unknown SSRC: " + ssrc);
                }

                // Process Reception Report Blocks if present
                for (int i = 0; i < reportCount; i++) {
                    if (buffer.remaining() < 24) { // Each RR block is 24 bytes
                        Log.w(TAG, "Malformed SR: not enough bytes for RR block " + i);
                        break;
                    }
                    processReceptionReportBlock(buffer);
                }

            } else if (packetType == 201) { // Receiver Report (RR)
                Log.d(TAG, "Received RTCP Receiver Report (RR)");
                // SSRC of receiver (32 bits)
                int ssrcOfReceiver = buffer.getInt();

                String peerUsername = findUsernameBySSRC(ssrcOfReceiver);
                if (peerUsername != null) {
                    Log.i(TAG, "RR received from " + peerUsername);
                } else {
                    Log.w(TAG, "RR from unknown SSRC: " + ssrcOfReceiver);
                }

                for (int i = 0; i < reportCount; i++) {
                    if (buffer.remaining() < 24) { // Each RR block is 24 bytes
                        Log.w(TAG, "Malformed RR: not enough bytes for RR block " + i);
                        break;
                    }
                    processReceptionReportBlock(buffer);
                }
            } else {
                Log.d(TAG, "Received unknown RTCP packet type: " + packetType);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing RTCP packet: " + e.getMessage(), e);
        }
    }

    private void processReceptionReportBlock(ByteBuffer buffer) {
        int ssrcOfSource = buffer.getInt(); // SSRC of the RTP sender this report is about
        int fractionLost = buffer.get() & 0xFF;
        int cumulativePacketsLost = buffer.getInt() & 0xFFFFFF; // 24 bits
        int extendedHighestSeqNum = buffer.getInt();
        int interarrivalJitter = buffer.getInt();
        int lastSrTimestamp = buffer.getInt();
        int delaySinceLastSr = buffer.getInt();

        String reportedSourceUsername = findUsernameBySSRC(ssrcOfSource);
        if (reportedSourceUsername != null) {
            Log.i(TAG, "RTCP RR Block for " + reportedSourceUsername +
                    ": Fraction Lost=" + (fractionLost / 255.0f) +
                    ", Cumulative Lost=" + cumulativePacketsLost +
                    ", Ext. Highest Seq=" + extendedHighestSeqNum +
                    ", Jitter=" + interarrivalJitter);
            // You can use this information for congestion control or adaptive bitrate.
            // For stability, simply logging is a good start.
        } else {
            Log.w(TAG, "RTCP RR Block for unknown SSRC source: " + ssrcOfSource);
        }
    }

    private String findUsernameBySSRC(int ssrc) {
        // This is a simplified way to map SSRC back to username.
        // In a real system, you'd manage SSRC mappings more robustly.
        // For example, by including SSRC in RTP header and maintaining a map.
        User user = User.getConnectedUser();
        if (user != null && user.getUsername().hashCode() == ssrc) {
            return user.getUsername();
        }
        for (Connection conn : _connections) {
            if (conn.getUsername().hashCode() == ssrc) {
                return conn.getUsername();
            }
        }
        return null;
    }
}