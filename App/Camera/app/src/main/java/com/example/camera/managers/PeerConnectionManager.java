package com.example.camera.managers;

import android.util.Log;
import android.util.Pair;

import com.example.camera.classes.*;

import com.example.camera.utils.NetworkingUtils;

import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PeerConnectionManager {
    private static final int PACKET_SIZE = 40000;
    private static final int PORT = 12345;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2;
    private static final int CLEANUP_MS = 5000;

    private static final PeerConnectionManager INSTANCE = new PeerConnectionManager();


    private Map<Pair<Long, String>, List<DataPacket>> _incompleteFrames = new HashMap<>();
    private long _latestTimestamp;
    private Supplier<byte[]> _dataSupplier = () -> new byte[0];
    private Consumer<CompleteData> _onCompleteDataReceived = data -> {};

    private List<Connection> _connections = new ArrayList<>();
    private DatagramSocket _receiveSocket;

    private Thread _receiveThread;
    private Thread _cleanupThread;

    private PeerConnectionManager() {}
    public static PeerConnectionManager getInstance() {
        return INSTANCE;
    }

    public void setDataSupplier(Supplier<byte[]> supplier) {
        this._dataSupplier = supplier;
    }

    public void setOnCompleteDataReceived(Consumer<CompleteData> callback) {
        this._onCompleteDataReceived = callback;
    }

    public void connectToParticipants() {
        shutdown();
        startReceiveThread();
        startCleanupThread();
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
        _connections.forEach(conn -> conn.getSendThread().interrupt());
        _connections.clear();

        if (_receiveThread != null) _receiveThread.interrupt();
        if (_cleanupThread != null) _cleanupThread.interrupt();

        if (_receiveSocket != null && !_receiveSocket.isClosed()) {
            _receiveSocket.close();
        }

        _receiveThread = null;
        _cleanupThread = null;
        _receiveSocket = null;
        _incompleteFrames.clear();
    }

    private Connection createConnection(String username, String ip) {
        Thread sendThread = createSendThread(ip);
        sendThread.setDaemon(true);
        sendThread.start();
        return new Connection(username, ip, sendThread);
    }

    private void startReceiveThread() {
        if (_receiveThread != null && _receiveThread.isAlive()) return;

        _receiveThread = new Thread(() -> {
            try {
                _receiveSocket = new DatagramSocket(PORT);

                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        byte[] buffer = new byte[PACKET_SIZE * 2];
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        _receiveSocket.receive(packet);

                        DataPacket parsedPacket = parsePacket(packet);
                        processReceivedPacket(parsedPacket);

                    } catch (Exception e) {
                        handleReceiveError(e);
                    }
                }
            } catch (Exception e) {
                Log.e(getClass().getName(), "Receive thread error", e);
            } finally {
                closeSocket();
            }
        });

        _receiveThread.setDaemon(true);
        _receiveThread.setPriority(Thread.MAX_PRIORITY);
        _receiveThread.start();
    }

    private void handleReceiveError(Exception e) {
        if (!Thread.currentThread().isInterrupted()) {
            Log.w(getClass().getName(), "Receive error", e);
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeSocket() {
        if (_receiveSocket != null && !_receiveSocket.isClosed()) {
            _receiveSocket.close();
        }
        _receiveSocket = null;
    }

    private void startCleanupThread() {
        if (_cleanupThread != null && _cleanupThread.isAlive()) return;

        _cleanupThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CLEANUP_MS);
                    cleanupOldFrames();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        _cleanupThread.setDaemon(true);
        _cleanupThread.start();
    }

    private void cleanupOldFrames() {
        long currentTime = System.currentTimeMillis();
        _incompleteFrames.entrySet().removeIf(entry -> currentTime - entry.getKey().first > CLEANUP_MS);
    }


    private DataPacket parsePacket(DatagramPacket datagram) {
        byte[] data = datagram.getData();
        ByteBuffer buffer = ByteBuffer.wrap(data);

        byte[] usernameBytes = new byte[8];
        buffer.get(usernameBytes);
        String username = new String(usernameBytes).trim();

        long timestamp = buffer.getLong();
        int sequence = buffer.getInt();
        int total = buffer.getInt();

        byte[] payload = new byte[datagram.getLength() - buffer.position()];
        buffer.get(payload);

        return new DataPacket(username, timestamp, sequence, total, payload);
    }

    private void processReceivedPacket(DataPacket packet) {
        Pair<Long, String> key = new Pair<>(packet.getTimestamp(), packet.getUsername());

        if (packet.getTimestamp() > _latestTimestamp) {
            _latestTimestamp = packet.getTimestamp();
            _incompleteFrames.clear();
        }

        _incompleteFrames.computeIfAbsent(key, k -> new ArrayList<>());

        List<DataPacket> packets = _incompleteFrames.get(key);
        if (packets.stream().noneMatch(p -> p.getSequenceNumber() == packet.getSequenceNumber())) {
            packets.add(packet);
            packets.sort(Comparator.comparingInt(DataPacket::getSequenceNumber));

            if (packets.size() == packet.getTotalPackets()) {
                byte[] complete = assemblePackets(packets);
                if (complete.length > 0) {
                    _onCompleteDataReceived.accept(new CompleteData(packet.getUsername(), packet.getTimestamp(), complete));
                }
                _incompleteFrames.remove(key);
            }
        }
    }

    private byte[] assemblePackets(List<DataPacket> packets) {
        ByteBuffer buffer = ByteBuffer.allocate(
                packets.stream().mapToInt(p -> p.getPayload().length).sum()
        );
        packets.forEach(p -> buffer.put(p.getPayload()));
        return buffer.array();
    }

    private Thread createSendThread(String receiverIp) {
        return new Thread(() -> {
            byte[] lastSent = new byte[0];

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    byte[] data = _dataSupplier.get();
                    if (data.length == 0 || Arrays.equals(data, lastSent)) {
                        Thread.sleep(RETRY_DELAY_MS);
                        continue;
                    }

                    sendPackets(data, receiverIp);
                    lastSent = Arrays.copyOf(data, data.length);
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Log.e(getClass().getName(), "Send thread error", e);
                }
            }
        });
    }

    private void sendPackets(byte[] data, String receiverIp) throws Exception {
        DatagramSocket socket = new DatagramSocket();
        try {
            byte[] usernameBytes = Arrays.copyOf(User.getConnectedUser().getUsername().getBytes(), 8);
            long timestamp = System.currentTimeMillis();
            byte[] timestampBytes = ByteBuffer.allocate(Long.BYTES).putLong(timestamp).array();

            int headerSize = 8 + Long.BYTES + Integer.BYTES * 2;
            int payloadSize = PACKET_SIZE - headerSize;
            int totalPackets = (int) Math.ceil((double) data.length / payloadSize);

            for (int i = 0; i < totalPackets; i++) {
                int start = i * payloadSize;
                int end = Math.min(start + payloadSize, data.length);
                byte[] payload = Arrays.copyOfRange(data, start, end);

                ByteBuffer packetBuffer = ByteBuffer.allocate(headerSize + payload.length);
                packetBuffer.put(usernameBytes);
                packetBuffer.put(timestampBytes);
                packetBuffer.putInt(i);
                packetBuffer.putInt(totalPackets);
                packetBuffer.put(payload);

                sendWithRetries(socket, packetBuffer.array(), receiverIp);
            }
        } finally {
            socket.close();
        }
    }

    private void sendWithRetries(DatagramSocket socket, byte[] packetData, String receiverIp) throws Exception {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                DatagramPacket packet = new DatagramPacket(
                        packetData, packetData.length,
                        InetAddress.getByName(receiverIp), PORT
                );
                socket.send(packet);
                break;
            } catch (Exception e) {
                if (attempt == MAX_RETRIES - 1) throw e;
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
    }
}
