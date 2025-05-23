package com.example.camera.managers;

import android.util.Log;

import com.example.camera.classes.Networking.CompleteData;
import com.example.camera.classes.Networking.DataPacket;
import com.example.camera.classes.Networking.FrameIdentifier;
import com.example.camera.classes.Networking.PacketType;
import com.example.camera.classes.Room;
import com.example.camera.classes.User;

import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

// Assuming these are your existing classes
// import com.example.app.room.Room;
// import com.example.app.user.User;
// import com.example.app.utility.Log;

public class PeerConnectionManager {
    private static final String TAG = "PeerConnectionManager";
    private static final int PACKET_SIZE = 2000; // Max size for RTP data packets
    private static final int RTP_PORT = 12345;
    private static final int RTCP_PORT = 12346; // RTCP typically uses RTP_PORT + 1

    private static final int MAX_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 2;
    private static final int CLEANUP_MS = 15000;
    private static final int MAX_QUEUE_SIZE = 1000;
    private static final int RECEIVE_SOCKET_TIMEOUT_MS = 500;

    // RTCP specific constants
    private static final long RTCP_REPORT_INTERVAL_MS = 5000; // Send RTCP reports every 5 seconds
    private static final long RTCP_BYE_TIMEOUT_MS = 30000; // Timeout for receiving BYE from a peer

    private static final PeerConnectionManager INSTANCE = new PeerConnectionManager();

    private final ConcurrentHashMap<FrameIdentifier, List<DataPacket>> _incompleteFrames = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<DataPacket> _rtpPacketQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
    private final LinkedBlockingQueue<RtcpPacket> _rtcpPacketQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE); // New queue for RTCP packets
    private final AtomicLong _latestTimestamp = new AtomicLong(0);

    private final AtomicInteger _completeFramesReceived = new AtomicInteger(0);
    private final AtomicInteger _rtpPacketsSent = new AtomicInteger(0);
    private final AtomicInteger _rtcpPacketsSent = new AtomicInteger(0); // New counter for RTCP packets

    private final ConcurrentHashMap<PacketType, Supplier<byte[]>> _dataSuppliers = new ConcurrentHashMap<>();
    private Consumer<CompleteData> _onCompleteDataReceived = data -> {};
    private Consumer<RtcpPacket> _onRtcpPacketReceived = rtcpPacket -> {}; // New callback for RTCP packets

    private final ConcurrentHashMap<String, Connection> _connections = new ConcurrentHashMap<>(); // Changed to ConcurrentHashMap for easier management by username

    private final AtomicReference<DatagramSocket> _rtpReceiveSocketRef = new AtomicReference<>();
    private final AtomicReference<DatagramSocket> _rtcpReceiveSocketRef = new AtomicReference<>(); // New socket ref for RTCP

    private Thread _rtpReceiveThread;
    private Thread _rtcpReceiveThread; // New thread for RTCP reception
    private Thread _processThread;
    private Thread _cleanupThread;
    private Thread _frameCounterThread;
    private Thread _packetCounterThread;
    private Thread _rtcpSenderThread; // New thread for sending RTCP reports

    private final AtomicBoolean _isRunning = new AtomicBoolean(false);

    // New: RTCP state per peer
    private final ConcurrentHashMap<String, RtcpPeerState> _rtcpPeerStates = new ConcurrentHashMap<>();

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

    public void setOnRtcpPacketReceived(Consumer<RtcpPacket> callback) {
        this._onRtcpPacketReceived = callback;
    }

    public void connectToParticipants() {
        shutdown();

        if (_isRunning.compareAndSet(false, true)) {
            startRtpReceiveThread();
            startRtcpReceiveThread(); // Start RTCP receive thread
            startProcessThread();
            startCleanupThread();
//            startFrameCounterThread();
//            startPacketCounterThread();
            startRtcpSenderThread(); // Start RTCP sender thread

            _connections.clear(); // Clear existing connections
            _rtcpPeerStates.clear(); // Clear existing RTCP peer states

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
                    Connection newConnection = createConnection(username, ip);
                    _connections.put(username, newConnection);
                    _rtcpPeerStates.put(username, new RtcpPeerState(username)); // Initialize RTCP state for new peer
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

        // Send BYE packets to all connected participants before shutting down
        User connectedUser = User.getConnectedUser();
        if (connectedUser != null) {
            String selfCname = connectedUser.getUsername();
            _connections.forEach((username, connection) -> {
                try {
                    sendRtcpPacket(connection.getSendSocket(), new ByePacket(selfCname), connection.getIp());
                } catch (Exception e) {
                    Log.e(TAG, "Error sending BYE to " + username + ": " + e.getMessage());
                }
            });
        }

        // Stop all send threads first
        _connections.forEach((username, conn) -> {
            Thread sendThread = conn.getSendThread();
            if (sendThread != null && sendThread.isAlive()) {
                sendThread.interrupt();
                try {
                    sendThread.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Interrupted while waiting for RTP send thread to terminate for " + username);
                }
            }
            // Close send socket
            if (conn.getSendSocket() != null && !conn.getSendSocket().isClosed()) {
                conn.getSendSocket().close();
                Log.d(TAG, "Send socket closed for " + username);
            }
        });
        _connections.clear();

        // Close receive sockets - do this safely
        DatagramSocket rtpReceiveSocket = _rtpReceiveSocketRef.getAndSet(null);
        if (rtpReceiveSocket != null && !rtpReceiveSocket.isClosed()) {
            rtpReceiveSocket.close();
            Log.d(TAG, "RTP receive socket closed.");
        }
        DatagramSocket rtcpReceiveSocket = _rtcpReceiveSocketRef.getAndSet(null);
        if (rtcpReceiveSocket != null && !rtcpReceiveSocket.isClosed()) {
            rtcpReceiveSocket.close();
            Log.d(TAG, "RTCP receive socket closed.");
        }

        // Safely terminate all threads
        stopThread(_rtpReceiveThread, "RTP Receive");
        stopThread(_rtcpReceiveThread, "RTCP Receive"); // Terminate RTCP receive thread
        stopThread(_processThread, "Process");
        stopThread(_cleanupThread, "Cleanup");
        stopThread(_frameCounterThread, "FrameCounter");
        stopThread(_packetCounterThread, "PacketCounter");
        stopThread(_rtcpSenderThread, "RTCP Sender"); // Terminate RTCP sender thread

        // Clear references and collections
        cleanupResourcesUnsafe();

        Log.d(TAG, "PeerConnectionManager shutdown complete.");
    }

    private void stopThread(Thread thread, String threadName) {
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
            try {
                thread.join(1000);
                if (thread.isAlive()) {
                    Log.w(TAG, threadName + " thread didnt stop after timeout");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Interrupted while waiting for " + threadName + " thread to terminate");
            }
        }
    }

    private void cleanupResourcesUnsafe() {
        _rtpReceiveThread = null;
        _rtcpReceiveThread = null;
        _processThread = null;
        _cleanupThread = null;
        _frameCounterThread = null;
        _packetCounterThread = null;
        _rtcpSenderThread = null;

        _incompleteFrames.clear();
        _rtpPacketQueue.clear();
        _rtcpPacketQueue.clear();
        _rtcpPeerStates.clear();
    }

    private Connection createConnection(String username, String ip) {
        // Now each connection will also manage its own DatagramSocket for sending
        // This is important because you might send RTP and RTCP to the same destination
        // but need separate sockets to track different metrics or if different local ports are needed.
        // For simplicity here, we'll use one socket per connection for both RTP/RTCP sending.
        DatagramSocket sendSocket = null;
        try {
            sendSocket = new DatagramSocket();
            sendSocket.setSendBufferSize(PACKET_SIZE * 10);
        } catch (SocketException e) {
            Log.e(TAG, "Error creating send socket for " + username + ": " + e.getMessage());
            return null;
        }

        Thread sendThread = createSendThread(sendSocket, username, ip);
        sendThread.setDaemon(true);
        sendThread.start();
        return new Connection(username, ip, sendThread, sendSocket);
    }

    private void startRtpReceiveThread() {
        if (_rtpReceiveThread != null && _rtpReceiveThread.isAlive()) {
            Log.w(TAG, "RTP Receive thread already running, skipping start.");
            return;
        }

        _rtpReceiveThread = new Thread(() -> {
            DatagramSocket rtpReceiveSocket = null;
            try {
                rtpReceiveSocket = new DatagramSocket(RTP_PORT);
                rtpReceiveSocket.setReceiveBufferSize(PACKET_SIZE * 10);
                rtpReceiveSocket.setSoTimeout(RECEIVE_SOCKET_TIMEOUT_MS);
                _rtpReceiveSocketRef.set(rtpReceiveSocket);
                byte[] buffer = new byte[PACKET_SIZE * 10];

                while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        rtpReceiveSocket.receive(packet);
                        DataPacket parsedPacket = parsePacket(packet);
                        if (!_rtpPacketQueue.offer(parsedPacket)) {
                            _rtpPacketQueue.poll(); // Drop oldest
                            _rtpPacketQueue.offer(parsedPacket);
                            Log.v(TAG, "RTP Packet queue full, dropped oldest packet");
                        }
                    } catch (SocketTimeoutException ste) {
                        // Expected timeout, just continue
                    } catch (SocketException se) {
                        if (!_isRunning.get()) {
                            Log.d(TAG, "RTP receive socket closed during shutdown. Exiting receive thread gracefully.");
                        } else {
                            Log.e(TAG, "Unexpected SocketException in RTP receive thread: " + se.getMessage(), se);
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
                DatagramSocket socketToClose = rtpReceiveSocket != null ? rtpReceiveSocket : _rtpReceiveSocketRef.getAndSet(null);
                if (socketToClose != null && !socketToClose.isClosed()) {
                    socketToClose.close();
                    Log.d(TAG, "RTP receive socket explicitly closed in finally block.");
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

    private void startRtcpReceiveThread() {
        if (_rtcpReceiveThread != null && _rtcpReceiveThread.isAlive()) {
            Log.w(TAG, "RTCP Receive thread already running, skipping start.");
            return;
        }

        _rtcpReceiveThread = new Thread(() -> {
            DatagramSocket rtcpReceiveSocket = null;
            try {
                rtcpReceiveSocket = new DatagramSocket(RTCP_PORT);
                rtcpReceiveSocket.setReceiveBufferSize(PACKET_SIZE * 10);
                rtcpReceiveSocket.setSoTimeout(RECEIVE_SOCKET_TIMEOUT_MS);
                _rtcpReceiveSocketRef.set(rtcpReceiveSocket);
                byte[] buffer = new byte[PACKET_SIZE * 10]; // RTCP packets are usually smaller, but keep buffer size consistent

                while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        rtcpReceiveSocket.receive(packet);
                        RtcpPacket parsedRtcpPacket = parseRtcpPacket(packet); // New parsing method for RTCP
                        if (parsedRtcpPacket != null) {
                            if (!_rtcpPacketQueue.offer(parsedRtcpPacket)) {
                                _rtcpPacketQueue.poll(); // Drop oldest
                                _rtcpPacketQueue.offer(parsedRtcpPacket);
                                Log.v(TAG, "RTCP Packet queue full, dropped oldest RTCP packet");
                            }
                        }
                    } catch (SocketTimeoutException ste) {
                        // Expected timeout
                    } catch (SocketException se) {
                        if (!_isRunning.get()) {
                            Log.d(TAG, "RTCP receive socket closed during shutdown. Exiting RTCP receive thread gracefully.");
                        } else {
                            Log.e(TAG, "Unexpected SocketException in RTCP receive thread: " + se.getMessage(), se);
                        }
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "RTCP Receive thread error: " + e.getMessage(), e);
                    }
                }
            } catch (SocketException se) {
                Log.e(TAG, "Could not open RTCP receive socket on port " + RTCP_PORT + ": " + se.getMessage(), se);
            } catch (Exception e) {
                Log.e(TAG, "RTCP Receive thread fatal error: " + e.getMessage(), e);
            } finally {
                DatagramSocket socketToClose = rtcpReceiveSocket != null ? rtcpReceiveSocket : _rtcpReceiveSocketRef.getAndSet(null);
                if (socketToClose != null && !socketToClose.isClosed()) {
                    socketToClose.close();
                    Log.d(TAG, "RTCP receive socket explicitly closed in finally block.");
                }
                Log.d(TAG, "RTCP Receive thread terminated.");
            }
        });
        _rtcpReceiveThread.setName("PeerConnectionRTCPReceiver");
        _rtcpReceiveThread.setDaemon(true);
        _rtcpReceiveThread.setPriority(Thread.NORM_PRIORITY + 1); // Slightly lower than RTP
        _rtcpReceiveThread.start();
        Log.d(TAG, "RTCP Receive thread started.");
    }

    private void startProcessThread() {
        if (_processThread != null && _processThread.isAlive()) return;

        _processThread = new Thread(() -> {
            List<DataPacket> rtpBatchPackets = new ArrayList<>();
            List<RtcpPacket> rtcpBatchPackets = new ArrayList<>();

            while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    rtpBatchPackets.clear();
                    rtcpBatchPackets.clear();

                    // Process RTP packets
                    DataPacket rtpHead = _rtpPacketQueue.poll(50, TimeUnit.MILLISECONDS);
                    if (rtpHead != null) {
                        rtpBatchPackets.add(rtpHead);
                        _rtpPacketQueue.drainTo(rtpBatchPackets, 19);
                        for (DataPacket packet : rtpBatchPackets) {
                            processReceivedRtpPacket(packet);
                        }
                    }

                    // Process RTCP packets
                    RtcpPacket rtcpHead = _rtcpPacketQueue.poll(50, TimeUnit.MILLISECONDS);
                    if (rtcpHead != null) {
                        rtcpBatchPackets.add(rtcpHead);
                        _rtcpPacketQueue.drainTo(rtcpBatchPackets, 19);
                        for (RtcpPacket packet : rtcpBatchPackets) {
                            processReceivedRtcpPacket(packet); // New processing method for RTCP
                        }
                    }

                    if (rtpHead == null && rtcpHead == null) {
                        Thread.sleep(5); // Small sleep if no packets
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
                    cleanupOldRtcpStates(); // New: clean up old RTCP states
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

    // New RTCP Sender Thread
    private void startRtcpSenderThread() {
        if (_rtcpSenderThread != null && _rtcpSenderThread.isAlive()) return;

        _rtcpSenderThread = new Thread(() -> {
            User connectedUser = User.getConnectedUser();
            if (connectedUser == null) {
                Log.e(TAG, "No connected user for RTCP sender, cannot start.");
                return;
            }
            String selfCname = connectedUser.getUsername();
            long lastReportTime = System.currentTimeMillis();

            while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastReportTime >= RTCP_REPORT_INTERVAL_MS) {
                        // Send SR if we are an active sender (e.g., have data suppliers)
                        // For simplicity, we'll send RR if no data suppliers, or SR if there are.
                        // A more robust implementation would track if RTP packets were actually sent recently.
                        boolean isSender = !_dataSuppliers.isEmpty();

                        _connections.forEach((username, connection) -> {
                            if (!_isRunning.get() || Thread.currentThread().isInterrupted()) return;
                            try {
                                RtcpPacket rtcpPacket;
                                if (isSender) {
                                    // Construct Sender Report
                                    // You'd need to track your own RTP packet/octet counts here
                                    // For simplicity, we'll use a dummy SSRC and 0 counts for now.
                                    long ssrc = generateSsrc(selfCname); // SSRC for yourself
                                    long ntpTimestamp = getNtpTimestamp();
                                    long rtpTimestamp = getRtpTimestamp(currentTime);
                                    long packetCount = _rtpPacketsSent.get(); // Global count, ideally per stream/SSRC
                                    long octetCount = 0; // Needs to be tracked for sent data

                                    // Include Receiver Report Blocks for what we've received from THIS peer
                                    RtcpPeerState peerState = _rtcpPeerStates.get(connection.getUsername());
                                    List<ReceiverReportBlock> rrBlocks = new ArrayList<>();
                                    if (peerState != null && peerState.hasReceivedRtp()) {
                                        // The SSRC here is the SSRC of the source being reported on
                                        // (i.e., the peer's SSRC). This might not be known yet from RTP.
                                        // For now, let's assume a default or use the peer's username.
                                        // In a real implementation, you'd get the SSRC from the RTP header of incoming packets.
                                        long ssrcOfReportedSource = generateSsrc(connection.getUsername()); // Example: use hash of username
                                        rrBlocks.add(createReceiverReportBlock(peerState, ssrcOfReportedSource));
                                    }

                                    rtcpPacket = new SenderReport(ssrc, ntpTimestamp, rtpTimestamp, packetCount, octetCount, rrBlocks);
                                } else {
                                    // Construct Receiver Report
                                    // You'd need to track received packets and their stats here for each source
                                    long ssrc = generateSsrc(selfCname); // SSRC for yourself
                                    List<ReceiverReportBlock> rrBlocks = new ArrayList<>();
                                    _rtcpPeerStates.forEach((peerUsername, peerState) -> {
                                        if (peerState.hasReceivedRtp()) {
                                            long ssrcOfReportedSource = generateSsrc(peerUsername);
                                            rrBlocks.add(createReceiverReportBlock(peerState, ssrcOfReportedSource));
                                        }
                                    });
                                    rtcpPacket = new ReceiverReport(ssrc, rrBlocks);
                                }
                                sendRtcpPacket(connection.getSendSocket(), rtcpPacket, connection.getIp());
                                _rtcpPacketsSent.incrementAndGet();

                                // Send SDES periodically (less frequently than SR/RR)
                                if (currentTime % (RTCP_REPORT_INTERVAL_MS * 2) < RTCP_REPORT_INTERVAL_MS / 2) { // Send SDES half as often
                                    SdesPacket sdes = new SdesPacket(generateSsrc(selfCname), selfCname);
                                    sendRtcpPacket(connection.getSendSocket(), sdes, connection.getIp());
                                    _rtcpPacketsSent.incrementAndGet();
                                }

                            } catch (Exception e) {
                                Log.e(TAG, "Error sending RTCP to " + username + ": " + e.getMessage());
                            }
                        });
                        lastReportTime = currentTime;
                    }
                    Thread.sleep(1000); // Check every second if it's time to send reports
                } catch (InterruptedException e) {
                    Log.d(TAG, "RTCP Sender thread interrupted. Exiting.");
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    Log.e(TAG, "RTCP Sender thread error: " + e.getMessage(), e);
                }
            }
            Log.d(TAG, "RTCP Sender thread terminated.");
        });

        _rtcpSenderThread.setName("PeerConnectionRTCPSender");
        _rtcpSenderThread.setDaemon(true);
        _rtcpSenderThread.start();
        Log.d(TAG, "RTCP Sender thread started.");
    }

    private ReceiverReportBlock createReceiverReportBlock(RtcpPeerState peerState, long ssrcOfReportedSource) {
        // Implement logic to calculate these values based on peerState
        // This is where the core of RTCP reception statistics comes in.
        return new ReceiverReportBlock(
                ssrcOfReportedSource,
                peerState.getFractionLost(),
                peerState.getCumulativePacketsLost(),
                peerState.getExtendedHighestSequenceNumber(),
                peerState.getInterarrivalJitter(),
                peerState.getLastSrTimestamp(),
                peerState.getDelaySinceLastSr()
        );
    }

    // Dummy SSRC generation (replace with a proper random SSRC per RFC 3550)
    private long generateSsrc(String seed) {
        return seed.hashCode() & 0xFFFFFFFFL; // Simple hash to long
    }

    // Dummy NTP timestamp (replace with actual NTP time or system time in NTP format)
    private long getNtpTimestamp() {
        long currentTimeMillis = System.currentTimeMillis();
        // NTP timestamp is 64-bit: 32 bits for seconds, 32 bits for fraction of a second.
        // Epoch for NTP is Jan 1, 1900. Java epoch is Jan 1, 1970.
        // Difference is 2208988800 seconds.
        long ntpEpochOffset = 2208988800L;
        long ntpSeconds = currentTimeMillis / 1000 + ntpEpochOffset;
        long ntpFraction = (long) ((currentTimeMillis % 1000) / 1000.0 * (1L << 32)); // fractional seconds
        return (ntpSeconds << 32) | ntpFraction;
    }

    // Dummy RTP timestamp (needs to be based on the RTP clock rate, e.g., 90kHz for audio)
    private long getRtpTimestamp(long currentTimeMillis) {
        // For video, typical clock rates are 90kHz. For audio, often 8kHz, 16kHz, etc.
        // Assuming a dummy 90kHz clock for now for video.
        long clockRate = 90000;
        return (currentTimeMillis * clockRate) / 1000; // milliseconds to RTP timestamp
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
            Log.d(TAG, "Cleaned up " + removedFrames + " stale incomplete RTP frames");
        }
    }

    // New: Cleanup old RTCP peer states (e.g., if a peer has been inactive for a long time)
    private void cleanupOldRtcpStates() {
        long currentTime = System.currentTimeMillis();
        _rtcpPeerStates.entrySet().removeIf(entry -> {
            RtcpPeerState state = entry.getValue();
            // Remove if no RTP or RTCP has been received for a long time (e.g., 2 * CLEANUP_MS)
            return (currentTime - state.getLastRtpPacketTime() > CLEANUP_MS * 2 &&
                    currentTime - state.getLastRtcpPacketTime() > CLEANUP_MS * 2);
        });
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
            Log.e(TAG, "Error parsing RTP packet: " + e.getMessage(), e);
            return new DataPacket("error", System.currentTimeMillis(), 0, 1, PacketType.fromByte((byte)0), new byte[0]);
        }
    }

    // New: Parse RTCP packets
    private RtcpPacket parseRtcpPacket(DatagramPacket datagram) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(datagram.getData(), 0, datagram.getLength());
            // RTCP header: V=2, P=0, RC=0 (for SR/RR), PT=200 (SR) or 201 (RR), Length
            byte firstByte = buffer.get();
            int version = (firstByte >>> 6) & 0x03; // 2 bits
            boolean padding = ((firstByte >>> 5) & 0x01) == 1; // 1 bit
            int rc = firstByte & 0x1F; // 5 bits (reception report count or source count)

            byte packetTypeByte = buffer.get(); // Payload Type (PT)
            RtcpPacketType rtcpPacketType = RtcpPacketType.fromByte(packetTypeByte);

            int length = Short.toUnsignedInt(buffer.getShort()); // Length in 32-bit words - 1

            if (version != 2) {
                Log.w(TAG, "Received RTCP packet with unsupported version: " + version);
                return null;
            }

            // Common header parsed, now parse specific RTCP types
            switch (rtcpPacketType) {
                case SENDER_REPORT:
                    long ssrcSR = buffer.getInt() & 0xFFFFFFFFL; // SSRC of sender
                    long ntpTimestampSR = buffer.getLong();
                    long rtpTimestampSR = buffer.getInt() & 0xFFFFFFFFL;
                    long senderPacketCount = buffer.getInt() & 0xFFFFFFFFL;
                    long senderOctetCount = buffer.getInt() & 0xFFFFFFFFL;

                    List<ReceiverReportBlock> srBlocks = new ArrayList<>();
                    for (int i = 0; i < rc; i++) {
                        srBlocks.add(parseReceiverReportBlock(buffer));
                    }
                    return new SenderReport(ssrcSR, ntpTimestampSR, rtpTimestampSR, senderPacketCount, senderOctetCount, srBlocks);

                case RECEIVER_REPORT:
                    long ssrcRR = buffer.getInt() & 0xFFFFFFFFL; // SSRC of receiver
                    List<ReceiverReportBlock> rrBlocks = new ArrayList<>();
                    for (int i = 0; i < rc; i++) {
                        rrBlocks.add(parseReceiverReportBlock(buffer));
                    }
                    return new ReceiverReport(ssrcRR, rrBlocks);

                case SOURCE_DESCRIPTION:
                    long ssrcSdes = buffer.getInt() & 0xFFFFFFFFL;
                    // For simplicity, just read the CNAME chunk if present
                    int sdesType = buffer.get();
                    if (sdesType == 1) { // CNAME type
                        int sdesLength = Byte.toUnsignedInt(buffer.get());
                        byte[] cnameBytes = new byte[sdesLength];
                        buffer.get(cnameBytes);
                        String cname = new String(cnameBytes);
                        return new SdesPacket(ssrcSdes, cname);
                    } else {
                        Log.w(TAG, "Unsupported SDES item type: " + sdesType);
                        // Skip remaining SDES items for now
                        buffer.position(buffer.position() + (length * 4) - 8); // Skip to end of RTCP packet
                        return null; // Or return a generic SDES packet
                    }
                case GOODBYE:
                    long ssrcBye = buffer.getInt() & 0xFFFFFFFFL;
                    String reason = "";
                    if (rc > 0) { // Reason for leaving
                        int reasonLength = Short.toUnsignedInt(buffer.getShort()); // Length in bytes
                        if (buffer.remaining() >= reasonLength) {
                            byte[] reasonBytes = new byte[reasonLength];
                            buffer.get(reasonBytes);
                            reason = new String(reasonBytes);
                        }
                    }
                    return new ByePacket(ssrcBye, reason);

                default:
                    Log.w(TAG, "Received unknown or unsupported RTCP packet type: " + rtcpPacketType);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing RTCP packet: " + e.getMessage(), e);
        }
        return null;
    }

    private ReceiverReportBlock parseReceiverReportBlock(ByteBuffer buffer) {
        long ssrc = buffer.getInt() & 0xFFFFFFFFL;
        int fractionLost = Byte.toUnsignedInt(buffer.get());
        long cumulativeLost = (buffer.getInt() & 0x00FFFFFF) | (fractionLost << 24); // Reconstruct 24-bit value with fraction lost
        long extendedHighestSeqNum = buffer.getInt() & 0xFFFFFFFFL;
        long interarrivalJitter = buffer.getInt() & 0xFFFFFFFFL;
        long lastSrTimestamp = buffer.getInt() & 0xFFFFFFFFL;
        long delaySinceLastSr = buffer.getInt() & 0xFFFFFFFFL;
        return new ReceiverReportBlock(ssrc, fractionLost, cumulativeLost, extendedHighestSeqNum,
                interarrivalJitter, lastSrTimestamp, delaySinceLastSr);
    }

    private void processReceivedRtpPacket(DataPacket packet) {
        if (packet == null || packet.getPayload() == null || packet.getUsername() == null) {
            Log.w(TAG, "Received invalid RTP packet, skipping");
            return;
        }

        // Update RTCP state for the sender of this RTP packet
        _rtcpPeerStates.computeIfAbsent(packet.getUsername(), RtcpPeerState::new)
                .updateRtpReceptionStats(packet);

        if (packet.getTimestamp() < _latestTimestamp.get() - CLEANUP_MS) {
            // Log.v(TAG, "Dropping outdated RTP packet from " + packet.getUsername() + " (timestamp: " + packet.getTimestamp() + ")");
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
                // Log.v(TAG, "Duplicate RTP packet received for frame " + key + ", sequence " + packet.getSequenceNumber());
                return;
            }

            packets.add(packet);

            if (packets.size() == packet.getTotalPackets()) {
                // Check if all expected sequence numbers are present (0 to total-1)
                boolean completeSequence = true;
                if (packets.get(0).getSequenceNumber() != 0 || packets.get(packets.size() - 1).getSequenceNumber() != packet.getTotalPackets() - 1) {
                    completeSequence = false;
                } else {
                    for (int i = 0; i < packets.size(); i++) {
                        if (packets.get(i).getSequenceNumber() != i) {
                            completeSequence = false;
                            break;
                        }
                    }
                }

                if (completeSequence) {
                    try {
                        byte[] complete = assemblePackets(packets);
                        if (complete.length > 0) {
                            CompleteData completedData = new CompleteData(
                                    packet.getUsername(),
                                    packet.getTimestamp(),
                                    packet.getPacketType(),
                                    complete
                            );
                            _onCompleteDataReceived.accept(completedData);
                            _completeFramesReceived.incrementAndGet();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error assembling packets for " + key + ": " + e.getMessage(), e);
                    } finally {
                        _incompleteFrames.remove(key);
                    }
                } else {
                    // Log.d(TAG, "Incomplete frame (gaps) from " + packet.getUsername() + " timestamp " + packet.getTimestamp() + ". Missing " + (packet.getTotalPackets() - packets.size()) + " packets.");
                }
            }
        }
    }

    // New: Process RTCP packets
    private void processReceivedRtcpPacket(RtcpPacket rtcpPacket) {
        if (rtcpPacket == null) {
            Log.w(TAG, "Received null RTCP packet, skipping");
            return;
        }

        // Update RTCP state for the sender of this RTCP packet
        _rtcpPeerStates.computeIfAbsent(rtcpPacket.getSenderCname(), RtcpPeerState::new) // Assuming getSenderCname exists
                .updateRtcpReceptionStats(rtcpPacket);

        _onRtcpPacketReceived.accept(rtcpPacket); // Pass to listener if registered

        // Specific handling for different RTCP types
        if (rtcpPacket instanceof SenderReport) {
            SenderReport sr = (SenderReport) rtcpPacket;
            Log.i(TAG, "Received SR from SSRC " + sr.getSsrc() + " (Packets: " + sr.getSenderPacketCount() + ", Octets: " + sr.getSenderOctetCount() + ")");
            sr.getReceptionReportBlocks().forEach(block -> {
                // Process reception reports about OUR stream from the remote peer
                Log.i(TAG, "  SR-RR: Source=" + block.getSsrcOfReportedSource() + ", Lost=" + block.getFractionLost() + ", Jitter=" + block.getInterarrivalJitter());
            });
        } else if (rtcpPacket instanceof ReceiverReport) {
            ReceiverReport rr = (ReceiverReport) rtcpPacket;
            Log.i(TAG, "Received RR from SSRC " + rr.getSsrc());
            rr.getReceptionReportBlocks().forEach(block -> {
                // Process reception reports about OUR stream from the remote peer
                Log.i(TAG, "  RR-RR: Source=" + block.getSsrcOfReportedSource() + ", Lost=" + block.getFractionLost() + ", Jitter=" + block.getInterarrivalJitter());
            });
        } else if (rtcpPacket instanceof SdesPacket) {
            SdesPacket sdes = (SdesPacket) rtcpPacket;
            // Update mapping of SSRC to CNAME (if not already done)
            // Or log the CNAME
            Log.i(TAG, "Received SDES from SSRC " + sdes.getSsrc() + ", CNAME: " + sdes.getCname());
            _rtcpPeerStates.computeIfAbsent(sdes.getCname(), RtcpPeerState::new).setSsrc(sdes.getSsrc());
        } else if (rtcpPacket instanceof ByePacket) {
            ByePacket bye = (ByePacket) rtcpPacket;
            String cname = _rtcpPeerStates.values().stream()
                    .filter(state -> state.getSsrc() == bye.getSsrc())
                    .map(RtcpPeerState::getUsername)
                    .findFirst().orElse("Unknown");
            Log.i(TAG, "Received BYE from SSRC " + bye.getSsrc() + " (" + cname + "). Reason: " + bye.getReason());
            _connections.remove(cname); // Remove connection for this peer
            _rtcpPeerStates.remove(cname); // Remove RTCP state
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

    // Modified createSendThread to use the provided socket
    private Thread createSendThread(DatagramSocket socket, String receiverUsername, String receiverIp) {
        return new Thread(() -> {
            try {
                Map<PacketType, byte[]> lastSentData = new ConcurrentHashMap<>();

                while (_isRunning.get() && !Thread.currentThread().isInterrupted()) {
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
                            try {
                                sendRtpPackets(socket, data, receiverIp, type); // Use sendRtpPackets
                                lastSentData.put(type, Arrays.copyOf(data, data.length));
                                dataSentThisIteration = true;
                            } catch (Exception e) {
                                Log.e(TAG, "Error sending RTP data to " + receiverUsername + ": " + e.getMessage(), e);
                            }
                        }
                    }

                    if (!dataSentThisIteration) {
                        Thread.sleep(5);
                    } else {
                        Thread.sleep(1);
                    }
                }
            } catch (InterruptedException e) {
                Log.d(TAG, "RTP Send thread for " + receiverUsername + " interrupted. Exiting.");
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (_isRunning.get()) {
                    Log.e(TAG, "RTP Send thread for " + receiverUsername + " error: " + e.getMessage(), e);
                }
            } finally {
                // Socket closure moved to shutdown() for individual connections
                Log.d(TAG, "RTP Send thread for " + receiverUsername + " terminated.");
            }
        });
    }

    // Renamed and slightly modified for clarity that it's for RTP data
    private void sendRtpPackets(DatagramSocket socket, byte[] data, String receiverIp, PacketType type) throws Exception {
        if (socket == null || socket.isClosed()) {
            throw new SocketException("Socket is closed or null, cannot send RTP packets.");
        }
        if (data == null || data.length == 0) {
            Log.w(TAG, "Attempted to send empty RTP data for type: " + type);
            return;
        }
        if (!_isRunning.get()) {
            Log.d(TAG, "PeerConnectionManager is shutting down, canceling RTP packet send");
            return;
        }

        User user = User.getConnectedUser();
        if (user == null) {
            Log.e(TAG, "No connected user, cannot send RTP packets");
            return;
        }

        byte[] usernameBytes = Arrays.copyOf(user.getUsername().getBytes(), 8);
        long timestamp = System.currentTimeMillis();
        byte packetTypeByte = type.toByte();

        int headerSize = 8 + Long.BYTES + Integer.BYTES * 2 + Byte.BYTES;
        int payloadSize = PACKET_SIZE - headerSize;
        int totalPackets = (int) Math.ceil((double) data.length / payloadSize);
        if (totalPackets <= 0) totalPackets = 1;

        for (int i = 0; i < totalPackets; i++) {
            if (!_isRunning.get() || Thread.currentThread().isInterrupted()) {
                Log.d(TAG, "RTP Sending interrupted, sent " + i + "/" + totalPackets + " packets");
                return;
            }

            int start = i * payloadSize;
            int end = Math.min(start + payloadSize, data.length);
            byte[] payload = Arrays.copyOfRange(data, start, end);

            ByteBuffer packetBuffer = ByteBuffer.allocate(headerSize + payload.length);
            packetBuffer.put(usernameBytes);
            packetBuffer.putLong(timestamp); // Directly put long
            packetBuffer.putInt(i);
            packetBuffer.putInt(totalPackets);
            packetBuffer.put(packetTypeByte);
            packetBuffer.put(payload);

            sendAndRetry(socket, packetBuffer.array(), receiverIp, RTP_PORT); // Send to RTP port
            _rtpPacketsSent.incrementAndGet();
        }
    }

    // New: Send RTCP packets
    private void sendRtcpPacket(DatagramSocket socket, RtcpPacket rtcpPacket, String receiverIp) throws Exception {
        if (socket == null || socket.isClosed()) {
            throw new SocketException("Socket is closed or null, cannot send RTCP packet.");
        }
        if (rtcpPacket == null) {
            Log.w(TAG, "Attempted to send null RTCP packet.");
            return;
        }
        if (!_isRunning.get()) {
            Log.d(TAG, "PeerConnectionManager is shutting down, canceling RTCP packet send");
            return;
        }

        byte[] rtcpData = rtcpPacket.toBytes(); // Serialize RTCP packet to bytes
        if (rtcpData.length == 0) {
            Log.w(TAG, "RTCP packet generated empty bytes: " + rtcpPacket.getClass().getSimpleName());
            return;
        }

        sendAndRetry(socket, rtcpData, receiverIp, RTCP_PORT); // Send to RTCP port
        _rtcpPacketsSent.incrementAndGet();
    }

    // Modified sendAndRetry to accept a target port
    private void sendAndRetry(DatagramSocket socket, byte[] packetData, String receiverIp, int targetPort) throws Exception {
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
                DatagramPacket packet = new DatagramPacket(packetData, packetData.length, address, targetPort);
                socket.send(packet);
                sent = true;
                return;
            } catch (PortUnreachableException pue) {
                Log.w(TAG, "Port unreachable for " + receiverIp + ":" + targetPort + " (Attempt " + (attempt + 1) + "/" + MAX_RETRIES + ")");
                lastException = pue;
            } catch (SocketException se) {
                Log.e(TAG, "Socket exception during send: " + se.getMessage());
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
                Log.e(TAG, "Error sending to " + receiverIp + ":" + targetPort + " (Attempt " + (attempt + 1) + "/" + MAX_RETRIES + "): " + e.getMessage());
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

    // --- Helper Classes (Need to be defined in your project) ---

    // Assuming existing: FrameIdentifier, DataPacket, PacketType, CompleteData, Room, User, Log

    // New: RtcpPacketType enum
    public enum RtcpPacketType {
        SENDER_REPORT((byte) 200),
        RECEIVER_REPORT((byte) 201),
        SOURCE_DESCRIPTION((byte) 202),
        GOODBYE((byte) 203),
        APPLICATION_DEFINED((byte) 204),
        UNKNOWN((byte) -1);

        private final byte type;

        RtcpPacketType(byte type) {
            this.type = type;
        }

        public byte toByte() {
            return type;
        }

        public static RtcpPacketType fromByte(byte b) {
            for (RtcpPacketType pt : RtcpPacketType.values()) {
                if (pt.type == b) {
                    return pt;
                }
            }
            return UNKNOWN;
        }
    }

    // New: Base RTCP Packet class
    public abstract class RtcpPacket {
        private final RtcpPacketType type;
        private final long ssrc; // SSRC of the RTCP sender
        private String senderCname; // CNAME is often used as a key for peers

        public RtcpPacket(RtcpPacketType type, long ssrc) {
            this.type = type;
            this.ssrc = ssrc;
            this.senderCname = String.valueOf(ssrc); // Default, will be updated by SDES
        }

        public RtcpPacketType getType() {
            return type;
        }

        public long getSsrc() {
            return ssrc;
        }

        public String getSenderCname() {
            return senderCname;
        }

        public void setSenderCname(String senderCname) {
            this.senderCname = senderCname;
        }

        // Abstract method to serialize the RTCP packet to bytes
        public abstract byte[] toBytes();
    }

    // New: Receiver Report Block
    public class ReceiverReportBlock {
        private final long ssrcOfReportedSource;
        private final int fractionLost; // 8 bits
        private final long cumulativePacketsLost; // 24 bits
        private final long extendedHighestSequenceNumber; // 32 bits
        private final long interarrivalJitter; // 32 bits
        private final long lastSrTimestamp; // 32 bits (middle 32 bits of NTP)
        private final long delaySinceLastSr; // 32 bits (delay in 1/65536 sec)

        public ReceiverReportBlock(long ssrcOfReportedSource, int fractionLost, long cumulativePacketsLost,
                                   long extendedHighestSequenceNumber, long interarrivalJitter,
                                   long lastSrTimestamp, long delaySinceLastSr) {
            this.ssrcOfReportedSource = ssrcOfReportedSource;
            this.fractionLost = fractionLost;
            this.cumulativePacketsLost = cumulativePacketsLost;
            this.extendedHighestSequenceNumber = extendedHighestSequenceNumber;
            this.interarrivalJitter = interarrivalJitter;
            this.lastSrTimestamp = lastSrTimestamp;
            this.delaySinceLastSr = delaySinceLastSr;
        }

        public long getSsrcOfReportedSource() { return ssrcOfReportedSource; }
        public int getFractionLost() { return fractionLost; }
        public long getCumulativePacketsLost() { return cumulativePacketsLost; }
        public long getExtendedHighestSequenceNumber() { return extendedHighestSequenceNumber; }
        public long getInterarrivalJitter() { return interarrivalJitter; }
        public long getLastSrTimestamp() { return lastSrTimestamp; }
        public long getDelaySinceLastSr() { return delaySinceLastSr; }

        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(24); // 6 * 4 bytes
            buffer.putInt((int) ssrcOfReportedSource);
            buffer.put((byte) fractionLost);
            buffer.put((byte) ((cumulativePacketsLost >>> 16) & 0xFF)); // High 8 bits
            buffer.putShort((short) (cumulativePacketsLost & 0xFFFF)); // Low 16 bits
            buffer.putInt((int) extendedHighestSequenceNumber);
            buffer.putInt((int) interarrivalJitter);
            buffer.putInt((int) lastSrTimestamp);
            buffer.putInt((int) delaySinceLastSr);
            return buffer.array();
        }
    }

    // New: Sender Report (SR) RTCP Packet
    public class SenderReport extends RtcpPacket {
        private final long ntpTimestamp;
        private final long rtpTimestamp;
        private final long senderPacketCount;
        private final long senderOctetCount;
        private final List<ReceiverReportBlock> receptionReportBlocks;

        public SenderReport(long ssrc, long ntpTimestamp, long rtpTimestamp, long senderPacketCount,
                            long senderOctetCount, List<ReceiverReportBlock> receptionReportBlocks) {
            super(RtcpPacketType.SENDER_REPORT, ssrc);
            this.ntpTimestamp = ntpTimestamp;
            this.rtpTimestamp = rtpTimestamp;
            this.senderPacketCount = senderPacketCount;
            this.senderOctetCount = senderOctetCount;
            this.receptionReportBlocks = receptionReportBlocks;
        }

        public long getNtpTimestamp() { return ntpTimestamp; }
        public long getRtpTimestamp() { return rtpTimestamp; }
        public long getSenderPacketCount() { return senderPacketCount; }
        public long getSenderOctetCount() { return senderOctetCount; }
        public List<ReceiverReportBlock> getReceptionReportBlocks() { return receptionReportBlocks; }

        @Override
        public byte[] toBytes() {
            int rrBlockSize = receptionReportBlocks.size() * 24; // Each RR block is 24 bytes
            ByteBuffer buffer = ByteBuffer.allocate(24 + rrBlockSize + 4); // Standard header + SR specific + RR Blocks
            // RTCP Header (V=2, P=0, RC, PT=200, Length)
            byte firstByte = (byte) (0x80 | receptionReportBlocks.size()); // V=2, P=0, RC
            buffer.put(firstByte);
            buffer.put(RtcpPacketType.SENDER_REPORT.toByte());
            buffer.putShort((short) ((24 + rrBlockSize) / 4 - 1)); // Length in 32-bit words, not including header

            buffer.putInt((int) getSsrc());
            buffer.putLong(ntpTimestamp);
            buffer.putInt((int) rtpTimestamp);
            buffer.putInt((int) senderPacketCount);
            buffer.putInt((int) senderOctetCount);

            for (ReceiverReportBlock block : receptionReportBlocks) {
                buffer.put(block.toBytes());
            }
            return buffer.array();
        }
    }

    // New: Receiver Report (RR) RTCP Packet
    public class ReceiverReport extends RtcpPacket {
        private final List<ReceiverReportBlock> receptionReportBlocks;

        public ReceiverReport(long ssrc, List<ReceiverReportBlock> receptionReportBlocks) {
            super(RtcpPacketType.RECEIVER_REPORT, ssrc);
            this.receptionReportBlocks = receptionReportBlocks;
        }

        public List<ReceiverReportBlock> getReceptionReportBlocks() { return receptionReportBlocks; }

        @Override
        public byte[] toBytes() {
            int rrBlockSize = receptionReportBlocks.size() * 24; // Each RR block is 24 bytes
            ByteBuffer buffer = ByteBuffer.allocate(8 + rrBlockSize + 4); // Standard header + RR specific + RR Blocks
            // RTCP Header (V=2, P=0, RC, PT=201, Length)
            byte firstByte = (byte) (0x80 | receptionReportBlocks.size()); // V=2, P=0, RC
            buffer.put(firstByte);
            buffer.put(RtcpPacketType.RECEIVER_REPORT.toByte());
            buffer.putShort((short) ((8 + rrBlockSize) / 4 - 1)); // Length in 32-bit words, not including header

            buffer.putInt((int) getSsrc());

            for (ReceiverReportBlock block : receptionReportBlocks) {
                buffer.put(block.toBytes());
            }
            return buffer.array();
        }
    }

    // New: Source Description (SDES) RTCP Packet
    public class SdesPacket extends RtcpPacket {
        private final String cname; // Canonical Name

        public SdesPacket(long ssrc, String cname) {
            super(RtcpPacketType.SOURCE_DESCRIPTION, ssrc);
            this.cname = cname;
            setSenderCname(cname); // Set the base class's sender CNAME
        }

        public String getCname() { return cname; }

        @Override
        public byte[] toBytes() {
            byte[] cnameBytes = cname.getBytes();
            int cnameLength = cnameBytes.length;

            // SDES item: Type (1 byte) + Length (1 byte) + Value (length bytes)
            int sdesItemLength = 1 + 1 + cnameLength;
            // Pad to a 32-bit boundary (SDES items are terminated by a 0, then padded)
            int padding = (4 - (sdesItemLength % 4)) % 4;
            int totalSdesChunkLength = sdesItemLength + padding + 1; // +1 for null terminator

            ByteBuffer buffer = ByteBuffer.allocate(8 + totalSdesChunkLength); // Standard header + SSRC + SDES Chunk
            // RTCP Header (V=2, P=0, SC, PT=202, Length)
            byte firstByte = (byte) (0x80 | 1); // V=2, P=0, SC=1 (one SSRC chunk)
            buffer.put(firstByte);
            buffer.put(RtcpPacketType.SOURCE_DESCRIPTION.toByte());
            buffer.putShort((short) ((8 + totalSdesChunkLength) / 4 - 1)); // Length in 32-bit words

            buffer.putInt((int) getSsrc());
            buffer.put((byte) 1); // SDES Type: CNAME
            buffer.put((byte) cnameLength);
            buffer.put(cnameBytes);
            for (int i = 0; i < padding; i++) {
                buffer.put((byte) 0); // Padding
            }
            buffer.put((byte) 0); // Null terminator for SDES chunk

            return buffer.array();
        }
    }

    // New: Goodbye (BYE) RTCP Packet
    public class ByePacket extends RtcpPacket {
        private final String reason;

        public ByePacket(long ssrc, String reason) {
            super(RtcpPacketType.GOODBYE, ssrc);
            this.reason = reason;
        }
        public ByePacket(String cname) {
            super(RtcpPacketType.GOODBYE, PeerConnectionManager.this.generateSsrc(cname)); // Use SSRC for self
            this.reason = "Leaving session";
            setSenderCname(cname);
        }

        public String getReason() { return reason; }

        @Override
        public byte[] toBytes() {
            byte[] reasonBytes = reason.getBytes();
            int reasonLength = reasonBytes.length;
            int padding = (4 - ((reasonLength + 2) % 4)) % 4; // +2 for length field
            int totalReasonLength = reasonLength + 2 + padding;

            ByteBuffer buffer = ByteBuffer.allocate(8 + totalReasonLength); // Standard header + SSRC + optional reason
            // RTCP Header (V=2, P=0, SC, PT=203, Length)
            byte firstByte = (byte) (0x80 | 1); // V=2, P=0, SC=1 (one SSRC being reported)
            buffer.put(firstByte);
            buffer.put(RtcpPacketType.GOODBYE.toByte());
            buffer.putShort((short) ((8 + totalReasonLength) / 4 - 1)); // Length in 32-bit words

            buffer.putInt((int) getSsrc());
            if (reasonLength > 0) {
                buffer.putShort((short) reasonLength);
                buffer.put(reasonBytes);
                for (int i = 0; i < padding; i++) {
                    buffer.put((byte) 0);
                }
            }
            return buffer.array();
        }
    }

    // New: RTCP Peer State to track reception stats for each peer
    public class RtcpPeerState {
        private final String username;
        private long ssrc; // SSRC of this peer
        private long lastRtpTimestamp = 0;
        private long lastRtpSequenceNumber = -1;
        private long lastRtpPacketTime = 0;
        private long lastRtcpPacketTime = 0;

        // Statistics for Receiver Report Block
        private int fractionLost = 0;
        private long cumulativePacketsLost = 0;
        private long extendedHighestSequenceNumber = -1; // -1 to indicate none received
        private long interarrivalJitter = 0;
        private long lastSrTimestamp = 0; // NTP timestamp (middle 32 bits) of last SR from this peer
        private long delaySinceLastSr = 0;

        public RtcpPeerState(String username) {
            this.username = username;
            this.ssrc = PeerConnectionManager.this.generateSsrc(username); // Initialize with a dummy SSRC
        }

        public String getUsername() { return username; }
        public long getSsrc() { return ssrc; }
        public void setSsrc(long ssrc) { this.ssrc = ssrc; } // Set actual SSRC from SDES

        public boolean hasReceivedRtp() {
            return lastRtpSequenceNumber != -1;
        }

        // Methods to retrieve current stats for generating RR blocks
        public int getFractionLost() { return fractionLost; }
        public long getCumulativePacketsLost() { return cumulativePacketsLost; }
        public long getExtendedHighestSequenceNumber() { return extendedHighestSequenceNumber; }
        public long getInterarrivalJitter() { return interarrivalJitter; }
        public long getLastSrTimestamp() { return lastSrTimestamp; }
        public long getDelaySinceLastSr() { return delaySinceLastSr; }
        public long getLastRtpPacketTime() { return lastRtpPacketTime; }
        public long getLastRtcpPacketTime() { return lastRtcpPacketTime; }

        public synchronized void updateRtpReceptionStats(DataPacket packet) {
            long currentTime = System.currentTimeMillis();
            lastRtpPacketTime = currentTime;

            // Update extended highest sequence number
            long currentSeq = packet.getSequenceNumber();
            if (currentSeq > extendedHighestSequenceNumber) {
                // Handle wrap-around (not implemented here, but important for real RTP)
                long packetsSkipped = currentSeq - extendedHighestSequenceNumber - 1;
                if (packetsSkipped > 0) {
                    cumulativePacketsLost += packetsSkipped;
                }
                extendedHighestSequenceNumber = currentSeq;
            } else if (currentSeq < extendedHighestSequenceNumber) {
                // This is a reordered or old packet, don't update highest sequence number.
                // Could increment duplicate count if we were tracking it.
            }

            // Calculate Jitter (simplified version)
            if (lastRtpTimestamp != 0) {
                long transitTimePrevious = currentTime - lastRtpTimestamp; // Time between receipt of consecutive packets
                long delta = transitTimePrevious - (packet.getTimestamp() - lastRtpTimestamp); // This is simplified, actual jitter calculation is more complex
                interarrivalJitter += (Math.abs(delta) - interarrivalJitter) / 16; // RFC 3550 recommends alpha=1/16
            }
            lastRtpTimestamp = packet.getTimestamp(); // Store RTP timestamp, not system time
        }

        public synchronized void updateRtcpReceptionStats(RtcpPacket rtcpPacket) {
            lastRtcpPacketTime = System.currentTimeMillis();
            if (rtcpPacket instanceof SenderReport) {
                SenderReport sr = (SenderReport) rtcpPacket;
                lastSrTimestamp = (sr.getNtpTimestamp() >>> 16) & 0xFFFFFFFFL; // Middle 32 bits
                // Calculate delaySinceLastSr if needed
                // delaySinceLastSr = (long)((System.currentTimeMillis() - sr.getNtpTimestamp()) / 1000.0 * 65536);
            }
            // Other RTCP packet types might update other states
        }
    }


    // Existing Connection class (modified to hold a DatagramSocket)
    public class Connection {
        private final String username;
        private final String ip;
        private final Thread sendThread;
        private final DatagramSocket sendSocket; // New field

        public Connection(String username, String ip, Thread sendThread, DatagramSocket sendSocket) {
            this.username = username;
            this.ip = ip;
            this.sendThread = sendThread;
            this.sendSocket = sendSocket;
        }

        public String getUsername() { return username; }
        public String getIp() { return ip; }
        public Thread getSendThread() { return sendThread; }
        public DatagramSocket getSendSocket() { return sendSocket; } // Getter for the socket
    }

}