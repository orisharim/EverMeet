package com.example.camera.managers;

import android.util.Log;
import android.util.Pair;

import com.example.camera.classes.*;
import com.example.camera.utils.NetworkingUtils;

import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PeerConnectionManager {
    private static final String TAG = "PeerConnectionManager";
    private static final int PACKET_SIZE = 40000;
    private static final int PORT = 12345;
    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2;
    private static final int CLEANUP_MS = 15000;
    private static final int MAX_QUEUE_SIZE = 100;

    private static final PeerConnectionManager INSTANCE = new PeerConnectionManager();

    private final ConcurrentHashMap<Pair<Long, String>, List<DataPacket>> _incompleteFrames = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<DataPacket> _packetQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private final AtomicLong _latestTimestamp = new AtomicLong(0);

    private Supplier<byte[]> _dataSupplier = () -> new byte[0];
    private Consumer<CompleteData> _onCompleteDataReceived = data -> {};

    private final List<Connection> _connections = Collections.synchronizedList(new ArrayList<>());
    private DatagramSocket _receiveSocket;

    private Thread _receiveThread;
    private Thread _processThread;
    private Thread _cleanupThread;

    private volatile boolean _isRunning = false;

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
        _isRunning = true;
        startReceiveThread();
        startProcessThread();
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
        _isRunning = false;

        synchronized (_connections) {
            _connections.forEach(conn -> {
                if (conn.getSendThread() != null) {
                    conn.getSendThread().interrupt();
                }
            });
            _connections.clear();
        }

        if (_receiveThread != null) _receiveThread.interrupt();
        if (_processThread != null) _processThread.interrupt();
        if (_cleanupThread != null) _cleanupThread.interrupt();

        if (_receiveSocket != null && !_receiveSocket.isClosed()) {
            _receiveSocket.close();
        }

        _receiveThread = null;
        _processThread = null;
        _cleanupThread = null;
        _receiveSocket = null;
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
        if (_receiveThread != null && _receiveThread.isAlive()) return;

        _receiveThread = new Thread(() -> {
            try {
                _receiveSocket = new DatagramSocket(PORT);
                _receiveSocket.setReceiveBufferSize(PACKET_SIZE * 10);

                byte[] buffer = new byte[PACKET_SIZE * 2];

                while (_isRunning && !Thread.currentThread().isInterrupted()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        _receiveSocket.receive(packet);

                        DataPacket parsedPacket = parsePacket(packet);

                        boolean added = _packetQueue.offer(parsedPacket);
                        if (!added) {
                            _packetQueue.poll();
                            _packetQueue.offer(parsedPacket);
                        }
                    } catch (SocketTimeoutException ste) {
                        continue;
                    } catch (Exception e) {
                        handleReceiveError(e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Receive thread error", e);
            } finally {
                closeSocket();
            }
        });

        _receiveThread.setName("PeerConnectionReceiver");
        _receiveThread.setDaemon(true);
        _receiveThread.setPriority(Thread.MAX_PRIORITY);
        _receiveThread.start();
    }

    private void startProcessThread() {
        if (_processThread != null && _processThread.isAlive()) return;

        _processThread = new Thread(() -> {
            List<DataPacket> batchPackets = new ArrayList<>();

            while (_isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    batchPackets.clear();

                    DataPacket head = _packetQueue.peek();
                    if (head == null) {
                        Thread.sleep(1);
                        continue;
                    }
                    _packetQueue.drainTo(batchPackets, 20);

                    for (DataPacket packet : batchPackets) {
                        processReceivedPacket(packet);
                    }

                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Log.e(TAG, "Process thread error", e);
                }
            }
        });

        _processThread.setName("PeerConnectionProcessor");
        _processThread.setDaemon(true);
        _processThread.setPriority(Thread.NORM_PRIORITY + 2);
        _processThread.start();
    }

    private void handleReceiveError(Exception e) {
        if (_isRunning && !Thread.currentThread().isInterrupted()) {
            Log.w(TAG, "Receive error", e);
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
            while (_isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(CLEANUP_MS);
                    cleanupOldFrames();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        _cleanupThread.setName("PeerConnectionCleaner");
        _cleanupThread.setDaemon(true);
        _cleanupThread.start();
    }

    private void cleanupOldFrames() {
        long currentTime = System.currentTimeMillis();
        long cutoffTime = currentTime - CLEANUP_MS;

        // i use iterator for concurrent modification safety
        for (Iterator<Map.Entry<Pair<Long, String>, List<DataPacket>>> it = _incompleteFrames.entrySet().iterator(); it.hasNext();) {
            Map.Entry<Pair<Long, String>, List<DataPacket>> entry = it.next();
            if (entry.getKey().first < cutoffTime) {
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

        int payloadLength = datagram.getLength() - buffer.position();
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);

        return new DataPacket(username, timestamp, sequence, total, payload);
    }

    private void processReceivedPacket(DataPacket packet) {
        if (packet.getTimestamp() < _latestTimestamp.get() - CLEANUP_MS) {
            return;
        }

        if (packet.getTimestamp() > _latestTimestamp.get()) {
            _latestTimestamp.set(packet.getTimestamp());
        }

        Pair<Long, String> key = new Pair<>(packet.getTimestamp(), packet.getUsername());

        List<DataPacket> packets = _incompleteFrames.computeIfAbsent(key, k ->
                Collections.synchronizedList(new ArrayList<>(packet.getTotalPackets())));

        synchronized (packets) {
            if (packets.stream().noneMatch(p -> p.getSequenceNumber() == packet.getSequenceNumber())) {
                packets.add(packet);

                // sort by sequence number
                if (packets.size() > 1) {
                    packets.sort(Comparator.comparingInt(DataPacket::getSequenceNumber));
                }

                if (packets.size() == packet.getTotalPackets()) {
                    try {
                        byte[] complete = assemblePackets(packets);
                        if (complete.length > 0) {
                            CompleteData completedData = new CompleteData(
                                    packet.getUsername(),
                                    packet.getTimestamp(),
                                    complete
                            );
                            _onCompleteDataReceived.accept(completedData);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error assembling packets", e);
                    } finally {
                        _incompleteFrames.remove(key);
                    }
                }
            }
        }
    }

    private byte[] assemblePackets(List<DataPacket> packets) {
        int totalSize = 0;
        for (DataPacket packet : packets) {
            totalSize += packet.getPayload().length;
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalSize);
        for (DataPacket packet : packets) {
            buffer.put(packet.getPayload());
        }

        return buffer.array();
    }

    private Thread createSendThread(String receiverIp) {
        return new Thread(() -> {
            byte[] lastSent = new byte[0];
            DatagramSocket socket = null;

            try {
                socket = new DatagramSocket();
                socket.setSendBufferSize(PACKET_SIZE * 10);

                while (_isRunning && !Thread.currentThread().isInterrupted()) {
                    try {
                        byte[] data = _dataSupplier.get();
                        if (data == null || data.length == 0) {
                            Thread.sleep(5);
                            continue;
                        }

                        if (!Arrays.equals(data, lastSent)) {
                            sendPackets(socket, data, receiverIp);
                            lastSent = Arrays.copyOf(data, data.length);
                        }

                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        if (_isRunning) {
                            Log.e(TAG, "Send thread error", e);
                            Thread.sleep(RETRY_DELAY_MS);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Send thread fatal error", e);
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        });
    }

    private void sendPackets(DatagramSocket socket, byte[] data, String receiverIp) throws Exception {
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
        } catch (Exception e) {
            Log.e(TAG, "Error sending packets", e);
            throw e;
        }
    }

    private void sendWithRetries(DatagramSocket socket, byte[] packetData, String receiverIp) throws Exception {
        Exception lastException = null;

        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                InetAddress address = InetAddress.getByName(receiverIp);
                DatagramPacket packet = new DatagramPacket(
                        packetData, packetData.length, address, PORT
                );
                socket.send(packet);
                return;
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES - 1) {
                    Thread.sleep(RETRY_DELAY_MS * (attempt + 1));  // Exponential backoff
                }
            }
        }

        if (lastException != null) {
            throw lastException;
        }
    }
}