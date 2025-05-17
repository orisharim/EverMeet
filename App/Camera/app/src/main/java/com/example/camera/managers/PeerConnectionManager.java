package com.example.camera.managers;

import android.util.Log;
import android.util.Pair; // android.util.Pair is fine for general use, but not for the map key here

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import android.util.Log;
//import android.util.Pair; // Not used as map key anymore

import com.example.camera.classes.*;
import com.example.camera.utils.NetworkingUtils;

import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final int RECEIVE_SOCKET_TIMEOUT_MS = 500; // New: Timeout for receive socket

    private static final PeerConnectionManager INSTANCE = new PeerConnectionManager();

    // Key now uses the custom FrameIdentifier class
    private final ConcurrentHashMap<FrameIdentifier, List<DataPacket>> _incompleteFrames = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<DataPacket> _packetQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private final AtomicLong _latestTimestamp = new AtomicLong(0); // Consider per-stream latest timestamp if needed

    private final AtomicInteger _completeFramesReceived = new AtomicInteger(0);
    private final AtomicInteger _packetsSent = new AtomicInteger(0);

    // Map to hold suppliers for different data types (e.g., video, audio)
    private final ConcurrentHashMap<PacketType, Supplier<byte[]>> _dataSuppliers = new ConcurrentHashMap<>();
    private Consumer<CompleteData> _onCompleteDataReceived = data -> {}; // Renamed from CompleteData

    private final List<Connection> _connections = Collections.synchronizedList(new ArrayList<>());
    private DatagramSocket _receiveSocket;

    private Thread _receiveThread;
    private Thread _processThread;
    private Thread _cleanupThread;
    private Thread _frameCounterThread;
    private Thread _packetCounterThread;

    private volatile boolean _isRunning = false;

    private PeerConnectionManager() {}

    public static PeerConnectionManager getInstance() {
        return INSTANCE;
    }

    // Set data supplier for a specific packet type
    public void setDataSupplier(PacketType type, Supplier<byte[]> supplier) {
        this._dataSuppliers.put(type, supplier);
    }

    public void setOnCompleteDataReceived(Consumer<CompleteData> callback) {
        this._onCompleteDataReceived = callback;
    }

    public void connectToParticipants() {
        shutdown(); // Ensure previous state is cleaned up
        _isRunning = true;
        startReceiveThread();
        startProcessThread();
        startCleanupThread();
        startFrameCounterThread();
        startPacketCounterThread();
        _connections.clear();

        Room room = Room.getConnectedRoom();
        String self = User.getConnectedUser().getUsername();

        room.getParticipants().forEach((username, ip) -> {
            if (!username.equals(self)) {
                _connections.add(createConnection(username, ip));
            }
        });
    }

    public void shutdown() {
        Log.d(TAG, "Shutting down PeerConnectionManager...");
        _isRunning = false; // Signal threads to stop

        // Interrupt all send threads first
        synchronized (_connections) {
            _connections.forEach(conn -> {
                if (conn.getSendThread() != null) {
                    conn.getSendThread().interrupt();
                }
            });
            _connections.clear();
        }

        // Close the receive socket
        if (_receiveSocket != null && !_receiveSocket.isClosed()) {
            _receiveSocket.close(); // This will unblock the receive() call and cause the SocketException
            Log.d(TAG, "Receive socket closed.");
        }

        // Interrupt all other threads and set them to null
        if (_receiveThread != null) {
            _receiveThread.interrupt();
            try { _receiveThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (_processThread != null) {
            _processThread.interrupt();
            try { _processThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (_cleanupThread != null) {
            _cleanupThread.interrupt();
            try { _cleanupThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (_frameCounterThread != null) {
            _frameCounterThread.interrupt();
            try { _frameCounterThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (_packetCounterThread != null) {
            _packetCounterThread.interrupt();
            try { _packetCounterThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }


        _receiveThread = null;
        _processThread = null;
        _cleanupThread = null;
        _frameCounterThread = null;
        _packetCounterThread = null;
        _receiveSocket = null; // Clear the reference after closing

        _incompleteFrames.clear();
        _packetQueue.clear();
        Log.d(TAG, "PeerConnectionManager shutdown complete.");
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
            try {
                _receiveSocket = new DatagramSocket(PORT);
                _receiveSocket.setReceiveBufferSize(PACKET_SIZE * 10);
                _receiveSocket.setSoTimeout(RECEIVE_SOCKET_TIMEOUT_MS); // Set the timeout here!

                byte[] buffer = new byte[PACKET_SIZE * 2];

                while (_isRunning && !Thread.currentThread().isInterrupted()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        _receiveSocket.receive(packet); // This will now throw SocketTimeoutException

                        // If a packet is received, parse and enqueue
                        DataPacket parsedPacket = parsePacket(packet);

                        boolean added = _packetQueue.offer(parsedPacket);
                        if (!added) {
                            // If queue is full, remove the oldest and add the new one
                            _packetQueue.poll();
                            _packetQueue.offer(parsedPacket);
                        }
                    } catch (SocketTimeoutException ste) {
                        // This is expected. Just continue the loop to check _isRunning flag.
                        // Log.v(TAG, "Receive socket timed out, checking shutdown status."); // For debugging
                    } catch (SocketException se) {
                        // This likely means the socket was closed by another thread (e.g., shutdown)
                        if (!_isRunning) {
                            Log.d(TAG, "Receive socket closed during shutdown. Exiting receive thread gracefully.");
                        } else {
                            // Unexpected SocketException while _isRunning is true
                            Log.e(TAG, "Receive thread SocketException (unexpected): " + se.getMessage(), se);
                        }
                        break; // Exit the loop on socket closure
                    } catch (Exception e) {
                        Log.e(TAG, "Receive thread error in loop: " + e.getMessage(), e);
                    }
                }
            } catch (SocketException se) {
                // This catch block handles the initial socket creation error
                Log.e(TAG, "Could not open receive socket on port " + PORT + ": " + se.getMessage(), se);
            } catch (Exception e) {
                Log.e(TAG, "Receive thread fatal error outside loop: " + e.getMessage(), e);
            } finally {
                if (_receiveSocket != null && !_receiveSocket.isClosed()) {
                    _receiveSocket.close();
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

            while (_isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    batchPackets.clear();

                    // Use poll with a timeout to prevent indefinite blocking if queue is empty
                    // and allow thread to check _isRunning
                    DataPacket head = _packetQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (head == null) {
                        continue; // No packet, check _isRunning again
                    }
                    batchPackets.add(head); // Add the polled head
                    _packetQueue.drainTo(batchPackets, 19); // Try to drain up to 19 more (total 20)

                    for (DataPacket packet : batchPackets) {
                        processReceivedPacket(packet);
                    }

                } catch (InterruptedException e) {
                    Log.d(TAG, "Process thread interrupted. Exiting.");
                    Thread.currentThread().interrupt(); // Restore interrupt status
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
            while (_isRunning && !Thread.currentThread().isInterrupted()) {
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
            while (_isRunning && !Thread.currentThread().isInterrupted()) {
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
            while (_isRunning && !Thread.currentThread().isInterrupted()) {
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

        for (Iterator<Map.Entry<FrameIdentifier, List<DataPacket>>> it = _incompleteFrames.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<FrameIdentifier, List<DataPacket>> entry = it.next();
            if (entry.getKey().getTimestamp() < cutoffTime) { // Access timestamp via FrameIdentifier's getter
                it.remove();
            }
        }
    }

    private DataPacket parsePacket(DatagramPacket datagram) {
        byte[] data = datagram.getData();
        ByteBuffer buffer = ByteBuffer.wrap(data, 0, datagram.getLength());

        byte[] usernameBytes = new byte[8];
        buffer.get(usernameBytes);
        String username = new String(usernameBytes).trim();

        long timestamp = buffer.getLong();
        int sequence = buffer.getInt();
        int total = buffer.getInt();
        byte packetTypeByte = buffer.get(); // Read the packet type byte
        PacketType packetType = PacketType.fromByte(packetTypeByte); // Convert byte to PacketType

        int payloadLength = datagram.getLength() - buffer.position();
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);

        return new DataPacket(username, timestamp, sequence, total, packetType, payload);
    }

    private void processReceivedPacket(DataPacket packet) {
        // You might want a more sophisticated way to manage _latestTimestamp,
        // potentially per-stream or per-user, to avoid old packets from one
        // stream affecting the overall cleanup. For now, this is kept for simplicity.
        if (packet.getTimestamp() < _latestTimestamp.get() - CLEANUP_MS) {
            return;
        }

        if (packet.getTimestamp() > _latestTimestamp.get()) {
            _latestTimestamp.set(packet.getTimestamp());
        }

        // Key now uses FrameIdentifier
        FrameIdentifier key = new FrameIdentifier(packet.getTimestamp(), packet.getUsername(), packet.getPacketType());

        List<DataPacket> packets = _incompleteFrames.computeIfAbsent(key, k ->
                Collections.synchronizedList(new ArrayList<>(packet.getTotalPackets())));

        synchronized (packets) {
            // Check if packet with this sequence number already exists to prevent duplicates
            if (packets.stream().noneMatch(p -> p.getSequenceNumber() == packet.getSequenceNumber())) {
                packets.add(packet);

                // Sort only if more than one packet is present, for efficiency
                if (packets.size() > 1) {
                    packets.sort(Comparator.comparingInt(DataPacket::getSequenceNumber));
                }

                if (packets.size() == packet.getTotalPackets()) {
                    try {
                        byte[] complete = assemblePackets(packets);
                        if (complete.length > 0) {
                            CompleteData completedData = new CompleteData( // Renamed
                                    packet.getUsername(),
                                    packet.getTimestamp(),
                                    packet.getPacketType(),
                                    complete
                            );
                            _onCompleteDataReceived.accept(completedData);

                            _completeFramesReceived.incrementAndGet();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error assembling packets for key " + key + ": " + e.getMessage(), e);
                    } finally {
                        _incompleteFrames.remove(key); // Always remove after processing or error
                    }
                }
            } else {
                // Log if a duplicate packet was received (optional, for debugging)
                // Log.d(TAG, "Duplicate packet received for key " + key + ", sequence " + packet.getSequenceNumber());
            }
        }
    }

    private byte[] assemblePackets(List<DataPacket> packets) {
        // Basic check for empty list
        if (packets.isEmpty()) {
            return new byte[0];
        }

        int totalSize = 0;
        for (DataPacket packet : packets) {
            totalSize += packet.getPayload().length;
        }

        // Allocate buffer and put payloads
        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        for (DataPacket packet : packets) {
            buffer.put(packet.getPayload());
        }

        return buffer.array();
    }

    private Thread createSendThread(String receiverIp) {
        return new Thread(() -> {
            DatagramSocket socket = null;

            try {
                socket = new DatagramSocket();
                socket.setSendBufferSize(PACKET_SIZE * 10);

                // Keep track of the last sent data for each type
                Map<PacketType, byte[]> lastSentData = new ConcurrentHashMap<>();

                while (_isRunning && !Thread.currentThread().isInterrupted()) {
                    try {
                        boolean dataSentThisIteration = false;
                        for (Map.Entry<PacketType, Supplier<byte[]>> entry : _dataSuppliers.entrySet()) {
                            PacketType type = entry.getKey();
                            Supplier<byte[]> supplier = entry.getValue();
                            byte[] data = supplier.get();

                            // Only send if data is available and has changed
                            // Also ensure data is not null for comparison
                            byte[] currentLastSent = lastSentData.get(type);
                            if (data != null && data.length > 0 && !Arrays.equals(data, currentLastSent)) {
                                sendPackets(socket, data, receiverIp, type);
                                lastSentData.put(type, Arrays.copyOf(data, data.length));
                                dataSentThisIteration = true;
                            }
                        }

                        if (!dataSentThisIteration) {
                            Thread.sleep(5); // Prevent busy-waiting if no data is sent
                        } else {
                            Thread.sleep(1); // Small delay to yield CPU after sending
                        }
                    } catch (InterruptedException e) {
                        Log.d(TAG, "Send thread interrupted. Exiting.");
                        Thread.currentThread().interrupt(); // Restore interrupt status
                    } catch (Exception e) {
                        if (_isRunning) { // Only log if still running, not during clean shutdown
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
        // Defensive check: if socket is closed or not connected, throw immediately
        if (socket == null || socket.isClosed()) {
            throw new SocketException("Socket is closed or null, cannot send packets.");
        }

        try {
            byte[] usernameBytes = Arrays.copyOf(User.getConnectedUser().getUsername().getBytes(), 8);
            long timestamp = System.currentTimeMillis();
            byte[] timestampBytes = ByteBuffer.allocate(Long.BYTES).putLong(timestamp).array();
            byte packetTypeByte = type.toByte(); // Convert PacketType to byte

            // Header size now includes the packet type byte
            int headerSize = 8 + Long.BYTES + Integer.BYTES * 2 + Byte.BYTES;
            int payloadSize = PACKET_SIZE - headerSize;
            int totalPackets = (int) Math.ceil((double) data.length / payloadSize);

            // Handle empty data scenario
            if (data.length == 0) {
                Log.w(TAG, "Attempted to send empty data for type: " + type);
                return;
            }
            if (totalPackets == 0) totalPackets = 1; // Ensure at least one packet even for very small data that fits into 0-length payload calculation

            for (int i = 0; i < totalPackets; i++) {
                int start = i * payloadSize;
                int end = Math.min(start + payloadSize, data.length);
                byte[] payload = Arrays.copyOfRange(data, start, end);

                ByteBuffer packetBuffer = ByteBuffer.allocate(headerSize + payload.length);
                packetBuffer.put(usernameBytes);
                packetBuffer.put(timestampBytes);
                packetBuffer.putInt(i);
                packetBuffer.putInt(totalPackets);
                packetBuffer.put(packetTypeByte); // Put the packet type byte
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

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                InetAddress address = InetAddress.getByName(receiverIp);
                DatagramPacket packet = new DatagramPacket(packetData, packetData.length, address, PORT);
                socket.send(packet);
                return; // Successfully sent, no need to retry
            } catch (PortUnreachableException pue) {
                // This typically means no one is listening on the other end,
                // or a firewall is blocking. Retrying might not help much.
                Log.w(TAG, "PortUnreachableException for " + receiverIp + ":" + PORT + " (Attempt " + (attempt + 1) + "/" + MAX_RETRIES + "): " + pue.getMessage());
                lastException = pue; // Still keep track for final throw if all fail
            } catch (SocketException se) {
                // Socket closed or other socket-related issue.
                Log.e(TAG, "SocketException during send to " + receiverIp + ":" + PORT + " (Attempt " + (attempt + 1) + "/" + MAX_RETRIES + "): " + se.getMessage());
                lastException = se;
                if (!socket.isClosed()) { // If socket is not closed, might be recoverable
                    Thread.sleep(RETRY_DELAY_MS * (attempt + 1));
                } else { // If socket is closed, no point in retrying
                    throw se; // Re-throw immediately as unrecoverable
                }
            } catch (Exception e) {
                Log.e(TAG, "Error sending packet to " + receiverIp + ":" + PORT + " (Attempt " + (attempt + 1) + "/" + MAX_RETRIES + "): " + e.getMessage(), e);
                lastException = e;
            }

            if (attempt < MAX_RETRIES - 1) {
                Thread.sleep(RETRY_DELAY_MS * (attempt + 1)); // Exponential backoff for retries
            }
        }

        if (lastException != null) {
            throw lastException; // Re-throw the last exception if all retries failed
        }
    }
}