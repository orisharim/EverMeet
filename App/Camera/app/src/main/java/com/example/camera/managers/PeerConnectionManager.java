package com.example.camera.managers;

import android.util.Log;

import com.example.camera.utils.Connection;
import com.example.camera.utils.DataPacket;
import com.example.camera.utils.Room;
import com.example.camera.utils.User;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
public class PeerConnectionManager {
    private static final int PACKET_SIZE = 2000;
    private static final int PORT = 12345;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 100;

    private static final PeerConnectionManager INSTANCE = new PeerConnectionManager();

    private ArrayList<Connection> connections;
    private Supplier<byte[]> dataSupplier;
    private Consumer<byte[]> onCompleteDataReceived;
    private long latestTimestamp;

    // Map to track incomplete frame data by FrameKey (timestamp + username)
    private HashMap<FrameKey, List<DataPacket>> incompleteFrames;

    // Single receive socket and thread for all connections
    private DatagramSocket receiveSocket;
    private Thread receiveThread;
    private Thread cleanupThread;
    private boolean isRunning;

    private PeerConnectionManager() {
        connections = new ArrayList<>();
        latestTimestamp = 0;
        dataSupplier = () -> new byte[0];
        onCompleteDataReceived = bytes -> {};
        incompleteFrames = new HashMap<>();
        isRunning = false;
    }

    // Simple class to create a composite key from timestamp and username
    private static class FrameKey {
        private final long timestamp;
        private final String username;

        public FrameKey(long timestamp, String username) {
            this.timestamp = timestamp;
            this.username = username;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FrameKey frameKey = (FrameKey) o;
            return timestamp == frameKey.timestamp &&
                    Objects.equals(username, frameKey.username);
        }

        @Override
        public int hashCode() {
            return Objects.hash(timestamp, username);
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getUsername() {
            return username;
        }
    }

    public static PeerConnectionManager getInstance() {
        return INSTANCE;
    }

    public void setDataSupplier(Supplier<byte[]> dataSupplier) {
        this.dataSupplier = dataSupplier;
    }

    public void setOnCompleteDataReceived(Consumer<byte[]> onCompleteDataReceived) {
        this.onCompleteDataReceived = onCompleteDataReceived;
    }

    public void connectToParticipants() {
        // Stop existing connections and threads
        shutdown();

        // Start the single receive thread if not already running
        startReceiveThread();

        // Start the cleanup thread if not already running
        startCleanupThread();

        connections = new ArrayList<>();

        // Create new send connections for all participants except self
        for (User user : Room.getConnectedRoom().getParticipants()) {
            if (user.getUsername().equals(User.getConnectedUser().getUsername())) {
                continue;
            }
            connections.add(createConnection(user));
        }

        isRunning = true;
    }

    private Connection createConnection(User user) {
        Thread sendThread = createSendThread(user);
        sendThread.start();
        return new Connection(user, sendThread);
    }

    private void startReceiveThread() {
        if (receiveThread != null && receiveThread.isAlive()) {
            return; // Thread is already running
        }

        receiveThread = new Thread(() -> {
            try {
                // Create a socket for receiving data
                receiveSocket = new DatagramSocket(PORT);

                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        byte[] buffer = new byte[PACKET_SIZE];
                        DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
                        receiveSocket.receive(datagramPacket);

                        DataPacket packet = processPacket(datagramPacket);
                        Log.d(PeerConnectionManager.class.getName(), "Received packet from " + packet.getUsername() +
                                " seq:" + packet.getSequenceNumber() + "/" + packet.getTotalPackets());

                        // Process the received packet
                        processReceivedPacket(packet);
                    } catch (Exception e) {
                        if (!Thread.currentThread().isInterrupted()) {
                            Log.w(PeerConnectionManager.class.getName(), "Error receiving packet", e);
                            // pause before retry
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(PeerConnectionManager.class.getName(), "Fatal error in receive thread", e);
            } finally {
                if (receiveSocket != null && !receiveSocket.isClosed()) {
                    receiveSocket.close();
                    receiveSocket = null;
                }
            }
        });

        receiveThread.start();
    }

    private void processReceivedPacket(DataPacket packet) {
        String username = packet.getUsername();
        long timestamp = packet.getTimestamp();

        // Check if this is a newer frame than what we're currently processing
        if (timestamp > latestTimestamp) {
            // Update latest timestamp and clean up old frames
            latestTimestamp = timestamp;
            // Clear old frames to save memory - replace with new HashMap
            // instead of modifying the existing one to avoid thread issues
            incompleteFrames = new HashMap<>();
        }

        // Create a key for this frame
        FrameKey frameKey = new FrameKey(timestamp, username);

        // Get or create the packet list for this frame
        List<DataPacket> packets = incompleteFrames.get(frameKey);
        if (packets == null) {
            packets = new ArrayList<>();
            incompleteFrames.put(frameKey, packets);
        }

        // Check if this packet is already in our list (by sequence number)
        boolean isDuplicate = false;
        for (DataPacket existingPacket : packets) {
            if (existingPacket.getSequenceNumber() == packet.getSequenceNumber()) {
                isDuplicate = true;
                break;
            }
        }

        if (!isDuplicate) {
            packets.add(packet);
        }

        // Sort packets by sequence number
        packets.sort((p1, p2) -> Integer.compare(p1.getSequenceNumber(), p2.getSequenceNumber()));

        // Check if we have all packets for this frame
        if (!packets.isEmpty() && packets.get(0).getTotalPackets() == packets.size()) {
            byte[] completeData = assemblePackets(packets);

            // Notify listeners only if we have valid data
            if (completeData.length > 0) {
                try {
                    onCompleteDataReceived.accept(completeData);
                } catch (Exception e) {
                    Log.e(PeerConnectionManager.class.getName(), "Error processing complete frame", e);
                }
            }

            // Remove the completed frame
            incompleteFrames.remove(frameKey);
        }
    }

    private void startCleanupThread() {
        if (cleanupThread != null && cleanupThread.isAlive()) {
            return; // Thread is already running
        }

        cleanupThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5000); // Clean up every 5 seconds

                    long currentTime = System.currentTimeMillis();
                    List<FrameKey> keysToRemove = new ArrayList<>();

                    for (FrameKey key : incompleteFrames.keySet()) {
                        if (currentTime - key.getTimestamp() > 5000) { // 5 second timeout
                            keysToRemove.add(key);
                        }
                    }

                    for (FrameKey key : keysToRemove) {
                        incompleteFrames.remove(key);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    private DataPacket processPacket(DatagramPacket packet) {
        byte[] packetData = packet.getData();

        // Extract username (8 bytes)
        byte[] usernameBytes = new byte[8];
        System.arraycopy(packetData, 0, usernameBytes, 0, usernameBytes.length);
        String username = new String(usernameBytes).trim();

        // Extract timestamp (8 bytes)
        byte[] timestampBytes = new byte[8];
        System.arraycopy(packetData, usernameBytes.length, timestampBytes, 0, timestampBytes.length);
        long timestamp = ByteBuffer.wrap(timestampBytes).getLong();

        // Extract sequence number (4 bytes)
        byte[] sequenceNumberBytes = new byte[4];
        System.arraycopy(packetData, usernameBytes.length + timestampBytes.length,
                sequenceNumberBytes, 0, sequenceNumberBytes.length);
        int sequenceNumber = ByteBuffer.wrap(sequenceNumberBytes).getInt();

        // Extract total packets (4 bytes)
        byte[] totalPacketsBytes = new byte[4];
        System.arraycopy(packetData, usernameBytes.length + timestampBytes.length + sequenceNumberBytes.length,
                totalPacketsBytes, 0, totalPacketsBytes.length);
        int totalPackets = ByteBuffer.wrap(totalPacketsBytes).getInt();

        // Extract payload
        int payloadStart = usernameBytes.length + timestampBytes.length +
                sequenceNumberBytes.length + totalPacketsBytes.length;
        int payloadLength = packet.getLength() - payloadStart;
        byte[] payload = new byte[payloadLength];
        System.arraycopy(packetData, payloadStart, payload, 0, payloadLength);

        return new DataPacket(username, timestamp, sequenceNumber, totalPackets, payload);
    }

    private byte[] assemblePackets(List<DataPacket> packets) {
        if (packets.isEmpty()) {
            return new byte[0];
        }

        // Calculate total payload size
        int totalSize = 0;
        for (DataPacket packet : packets) {
            totalSize += packet.getPayload().length;
        }

        ByteBuffer dataBuffer = ByteBuffer.allocate(totalSize);

        // Copy data from packets in sequence order
        for (DataPacket packet : packets) {
            dataBuffer.put(packet.getPayload());
        }

        byte[] completeData = new byte[dataBuffer.position()];
        dataBuffer.flip();
        dataBuffer.get(completeData);

        return completeData;
    }

    private Thread createSendThread(User user) {
        return new Thread(() -> {
            byte[] lastData = new byte[0];
            int lastDataHash = 0;

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // Get current frame data
                    byte[] data = dataSupplier.get();

                    // Skip if data is empty
                    if (data.length == 0) {
                        Thread.sleep(10);
                        continue;
                    }

                    // Calculate hash of data to avoid resending identical frames
                    int dataHash = Arrays.hashCode(data);

                    // Check if this is the same as last sent data
                    if (dataHash == lastDataHash) {
                        Thread.sleep(10);
                        continue;
                    }

                    // Create a new socket for each batch of packets
                    DatagramSocket socket = new DatagramSocket();

                    try {
                        // Prepare header components
                        byte[] usernameBytes = User.getConnectedUser().getUsername().getBytes();
                        byte[] username = new byte[8];
                        System.arraycopy(usernameBytes, 0, username, 0, Math.min(usernameBytes.length, 8));

                        long timestamp = System.currentTimeMillis();
                        byte[] timestampBytes = ByteBuffer.allocate(Long.BYTES).putLong(timestamp).array();

                        // Calculate packet sizes
                        int headerSize = username.length + timestampBytes.length + Integer.BYTES * 2;
                        int payloadSize = PACKET_SIZE - headerSize;
                        int totalPackets = (int) Math.ceil((double) data.length / payloadSize);

                        // Send packets
                        for (int i = 0; i < totalPackets; i++) {
                            int start = i * payloadSize;
                            int end = Math.min(start + payloadSize, data.length);
                            byte[] payload = new byte[end - start];
                            System.arraycopy(data, start, payload, 0, end - start);

                            // Prepare the packet header and payload
                            ByteBuffer packetBuffer = ByteBuffer.allocate(headerSize + payload.length);
                            packetBuffer.put(username);
                            packetBuffer.put(timestampBytes);
                            packetBuffer.putInt(i);  // Sequence number
                            packetBuffer.putInt(totalPackets);
                            packetBuffer.put(payload);

                            byte[] packetData = packetBuffer.array();

                            // Try to send with retries
                            boolean sent = false;
                            for (int attempt = 0; attempt < MAX_RETRIES && !sent; attempt++) {
                                try {
                                    DatagramPacket packet = new DatagramPacket(
                                            packetData,
                                            packetData.length,
                                            InetAddress.getByName(user.getIp()),
                                            PORT
                                    );
                                    socket.send(packet);
                                    sent = true;
                                } catch (Exception e) {
                                    if (attempt == MAX_RETRIES - 1) {
                                        Log.e(PeerConnectionManager.class.getName(), "Failed to send packet after " + MAX_RETRIES + " attempts", e);
                                    } else {
                                        Thread.sleep(RETRY_DELAY_MS);
                                    }
                                }
                            }

                            if (sent) {
                                Log.d(PeerConnectionManager.class.getName(), "Sent packet #" + i + " of " + totalPackets + " to " + user.getUsername());
                            }
                        }

                        // Update last sent data
                        lastData = data;
                        lastDataHash = dataHash;

                    } finally {
                        // Close the socket after sending all packets
                        socket.close();
                    }

                    // Small delay to avoid flooding the network
                    Thread.sleep(5);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(PeerConnectionManager.class.getName(), "Error in send thread", e);
                    try {
                        Thread.sleep(100);  // Brief delay before retry
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
    }

    public void shutdown() {
        isRunning = false;

        // Interrupt all send threads
        for (Connection connection : connections) {
            connection.getSendThread().interrupt();
        }

        connections = new ArrayList<>();

        // Interrupt and close the receive thread
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }

        // Interrupt the cleanup thread
        if (cleanupThread != null) {
            cleanupThread.interrupt();
            cleanupThread = null;
        }

        // Close the receive socket
        if (receiveSocket != null && !receiveSocket.isClosed()) {
            receiveSocket.close();
            receiveSocket = null;
        }

        // Clear data structures
        incompleteFrames = new HashMap<>();
    }
}