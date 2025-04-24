package com.example.camera.managers;

import android.util.Log;
import android.util.Pair;

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
    private static final int RETRY_DELAY_MS = 10;
    private static final int CLEANUP_MS = 5000;

    private static final PeerConnectionManager INSTANCE = new PeerConnectionManager();
    
    private Supplier<byte[]> dataSupplier;
    private Consumer<byte[]> onCompleteDataReceived;
    private long latestTimestamp;

    // Map to track incomplete frame data by frame key (timestamp + username)
    private HashMap<Pair<Long, String>, List<DataPacket>> incompleteFrames;

    // Single receive socket and thread for all connections
    private ArrayList<Connection> connections;
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
        shutdown();
        startReceiveThread();
        startCleanupThread();

        connections = new ArrayList<>();

        // create new send connections for all participants except self
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
            return;
        }

        receiveThread = new Thread(() -> {
            try {
                receiveSocket = new DatagramSocket(PORT);

                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        byte[] buffer = new byte[PACKET_SIZE];
                        DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
                        receiveSocket.receive(datagramPacket);

                        DataPacket packet = processPacket(datagramPacket);
                        Log.d(PeerConnectionManager.class.getName(), "Received packet from " + packet.getUsername() +
                                " seq:" + packet.getSequenceNumber() + "/" + packet.getTotalPackets());

                        processReceivedPacket(packet);
                    } catch (Exception e) {
                        if (!Thread.currentThread().isInterrupted()) {
                            Log.w(PeerConnectionManager.class.getName(), "Error receiving packet", e);
                            try {
                                Thread.sleep(RETRY_DELAY_MS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(PeerConnectionManager.class.getName(), "error in receive thread", e);
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

        if (timestamp > latestTimestamp) {
            // clean up old frames
            latestTimestamp = timestamp;
            incompleteFrames = new HashMap<>();
        }

        Pair<Long, String> frameKey = new Pair<Long, String>(timestamp, username);

        List<DataPacket> packets = incompleteFrames.get(frameKey);
        if (packets == null) {
            packets = new ArrayList<>();
            incompleteFrames.put(frameKey, packets);
        }

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

        // sort packets by sequence number
        packets.sort((p1, p2) -> Integer.compare(p1.getSequenceNumber(), p2.getSequenceNumber()));


        if (!packets.isEmpty() && packets.get(0).getTotalPackets() == packets.size()) {
            byte[] completeData = assemblePackets(packets);
            if (completeData.length > 0) {
                onCompleteDataReceived.accept(completeData);
            }
            incompleteFrames.remove(frameKey);
        }
    }

    private void startCleanupThread() {
        if (cleanupThread != null && cleanupThread.isAlive()) {
            return;
        }

        cleanupThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CLEANUP_MS);

                    long currentTime = System.currentTimeMillis();
                    List<Pair<Long, String>> keysToRemove = new ArrayList<>();

                    for (Pair<Long, String> key : incompleteFrames.keySet()) {
                        if (currentTime - key.first > CLEANUP_MS) {
                            keysToRemove.add(key);
                        }
                    }

                    for (Pair<Long, String> key : keysToRemove) {
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

        // extract username (8 bytes)
        byte[] usernameBytes = new byte[8];
        System.arraycopy(packetData, 0, usernameBytes, 0, usernameBytes.length);
        String username = new String(usernameBytes).trim();

        // extract timestamp (8 bytes)
        byte[] timestampBytes = new byte[8];
        System.arraycopy(packetData, usernameBytes.length, timestampBytes, 0, timestampBytes.length);
        long timestamp = ByteBuffer.wrap(timestampBytes).getLong();

        // extract sequence number (4 bytes)
        byte[] sequenceNumberBytes = new byte[4];
        System.arraycopy(packetData, usernameBytes.length + timestampBytes.length,
                sequenceNumberBytes, 0, sequenceNumberBytes.length);
        int sequenceNumber = ByteBuffer.wrap(sequenceNumberBytes).getInt();

        // extract total packets (4 bytes)
        byte[] totalPacketsBytes = new byte[4];
        System.arraycopy(packetData, usernameBytes.length + timestampBytes.length + sequenceNumberBytes.length,
                totalPacketsBytes, 0, totalPacketsBytes.length);
        int totalPackets = ByteBuffer.wrap(totalPacketsBytes).getInt();

        // extract payload
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

        int totalSize = 0;
        for (DataPacket packet : packets) {
            totalSize += packet.getPayload().length;
        }

        ByteBuffer dataBuffer = ByteBuffer.allocate(totalSize);

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

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    byte[] data = dataSupplier.get();

                    if (data.length == 0 || Arrays.equals(data, lastData)) {
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }

                    DatagramSocket socket = new DatagramSocket();

                    try {
                        // prepare header values
                        byte[] usernameBytes = User.getConnectedUser().getUsername().getBytes();
                        byte[] username = new byte[8];
                        System.arraycopy(usernameBytes, 0, username, 0, Math.min(usernameBytes.length, 8));

                        long timestamp = System.currentTimeMillis();
                        byte[] timestampBytes = ByteBuffer.allocate(Long.BYTES).putLong(timestamp).array();

                        // calculate packet sizes
                        int headerSize = username.length + timestampBytes.length + Integer.BYTES * 2;
                        int payloadSize = PACKET_SIZE - headerSize;
                        int totalPackets = (int) Math.ceil((double) data.length / payloadSize);

                        // send packets
                        for (int i = 0; i < totalPackets; i++) {
                            int start = i * payloadSize;
                            int end = Math.min(start + payloadSize, data.length);
                            byte[] payload = new byte[end - start];
                            System.arraycopy(data, start, payload, 0, end - start);

                            // prepare the packet header and payload
                            ByteBuffer packetBuffer = ByteBuffer.allocate(headerSize + payload.length);
                            packetBuffer.put(username);
                            packetBuffer.put(timestampBytes);
                            packetBuffer.putInt(i);  // its the sequence number
                            packetBuffer.putInt(totalPackets);
                            packetBuffer.put(payload);

                            byte[] packetData = packetBuffer.array();

                            // send with retries
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
                        lastData = Arrays.copyOf(data, data.length);


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