package com.example.camera.managers;

import com.example.camera.utils.Connection;
import com.example.camera.utils.DataPacket;
import com.example.camera.utils.Room;
import com.example.camera.utils.User;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

public class PeerConnectionManager {

    private final int PACKET_SIZE = 1400;
    private final int PORT = 12345;

    private static PeerConnectionManager _instance = new PeerConnectionManager();

    private ArrayList<Connection> _connections;
    private Supplier<byte[]> _dataSupplier;

    private PeerConnectionManager(){
        _connections = new ArrayList<>();
    }

    private void createConnections(){
        for (User user : Room.getConnectedRoom().getParticipants()) {
            // skip this user
            if(user.getUsername().equals(User.getConnectedUser().getUsername()))
                continue;

            _connections.add(createConnection(user));

        }
    }

    private Connection createConnection(User user){
        Thread receiveThread = createReceiveThread(user);
        Thread sendThread = createSendThread(user);
        return new Connection(user, sendThread, receiveThread);
    }

    private Thread createReceiveThread(User user){
        return new Thread(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(PORT);
                byte[] buffer = new byte[PACKET_SIZE];

                // Map to store packets grouped by timestamp
                HashMap<Long, List<DataPacket>> timestampMap = new HashMap<>();
                boolean receiving = true;

                while (receiving) {
                    DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(datagramPacket);

                    byte[] packetData = datagramPacket.getData();

                    // Extract username (first 8 bytes)
                    byte[] usernameBytes = new byte[8];
                    System.arraycopy(packetData, 0, usernameBytes, 0, usernameBytes.length);
                    String username = new String(usernameBytes).trim();

                    // Extract timestamp (next 8 bytes)
                    byte[] timestampBytes = new byte[8];
                    System.arraycopy(packetData, usernameBytes.length, timestampBytes, 0, timestampBytes.length);
                    long timestamp = ByteBuffer.wrap(timestampBytes).getLong();

                    // Extract sequence number (next 4 bytes)
                    byte[] sequenceNumberBytes = new byte[4];
                    System.arraycopy(packetData, usernameBytes.length + timestampBytes.length, sequenceNumberBytes, 0, sequenceNumberBytes.length);
                    int sequenceNumber = ByteBuffer.wrap(sequenceNumberBytes).getInt();

                    // Extract payload (remaining bytes)
                    int payloadStart = usernameBytes.length + timestampBytes.length + sequenceNumberBytes.length;
                    int payloadLength = datagramPacket.getLength() - payloadStart;

                    byte[] payload = new byte[payloadLength];
                    System.arraycopy(packetData, payloadStart, payload, 0, payloadLength);

                    // Create a Packet object
                    DataPacket packet = new DataPacket(username, timestamp, sequenceNumber, payload);

                    // Group packets by timestamp
                    timestampMap.putIfAbsent(timestamp, new ArrayList<>());
                    timestampMap.get(timestamp).add(packet);

                    System.out.println("Received packet #" + sequenceNumber + " from user: " + username + " at timestamp: " + timestamp);

                    // Optional: Add a condition to stop receiving, such as receiving all expected packets
                }

                socket.close();

                // Reassemble data for each timestamp
                for (long timestamp : timestampMap.keySet()) {
                    System.out.println("Reassembling packets for timestamp: " + timestamp);

                    List<DataPacket> packets = timestampMap.get(timestamp);

                    // Sort packets by sequence number
                    packets.sort((p1, p2) -> Integer.compare(p1.getSequenceNumber(), p2.getSequenceNumber()));

                    ByteBuffer dataBuffer = ByteBuffer.allocate(1024 * 1024); // Adjust for expected total data size
                    for (DataPacket packet : packets) {
                        dataBuffer.put(packet.getPayload());
                    }

                    byte[] completeData = new byte[dataBuffer.position()];
                    dataBuffer.flip();
                    dataBuffer.get(completeData);

                    System.out.println("Reassembled data for timestamp " + timestamp + ": " + new String(completeData));
                }

            } catch (Exception e) {
                throw new RuntimeException(e);
            }


        });
    }

    private Thread createSendThread(User user){
        return new Thread(() -> {
           while(true) {
               byte[] data = _dataSupplier.get();
               DatagramSocket socket = null;
               try {
                   socket = new DatagramSocket();
                   InetAddress address = InetAddress.getByName(user.getIp());

                   // Convert username to bytes and ensure it is 8 bytes long
                   byte[] usernameBytes = user.getUsername().getBytes();
                   if (usernameBytes.length > 8) {
                       throw new IllegalArgumentException("Username must not exceed 8 bytes.");
                   }
                   byte[] paddedUsername = new byte[8];
                   System.arraycopy(usernameBytes, 0, paddedUsername, 0, usernameBytes.length);

                   // Get the current timestamp
                   long timestamp = System.currentTimeMillis();
                   byte[] timestampBytes = ByteBuffer.allocate(Long.BYTES).putLong(timestamp).array();

                   // Adjust the payload size to account for header (username + timestamp + sequence number)
                   int headerSize = paddedUsername.length + timestampBytes.length + Integer.BYTES;
                   int payloadSize = PACKET_SIZE - headerSize;

                   int totalPackets = (int) Math.ceil((double) data.length / payloadSize);

                   for (int i = 0; i < totalPackets; i++) {
                       int start = i * payloadSize;
                       int end = Math.min(start + payloadSize, data.length);

                       // Extract the payload chunk for this packet
                       byte[] payload = new byte[end - start];
                       System.arraycopy(data, start, payload, 0, end - start);

                       // Prepare the packet header
                       ByteBuffer packetBuffer = ByteBuffer.allocate(headerSize + payload.length);
                       packetBuffer.put(paddedUsername); // Add username
                       packetBuffer.put(timestampBytes); // Add timestamp
                       packetBuffer.putInt(i);           // Add sequence number
                       packetBuffer.put(payload);        // Add payload

                       byte[] packetData = packetBuffer.array();

                       // Create and send the packet
                       DatagramPacket packet = new DatagramPacket(packetData, packetData.length, address, PORT);
                       socket.send(packet);

                   }
               } catch (Exception e) {
                   throw new RuntimeException(e);
               } finally {
                   if (socket != null && !socket.isClosed()) {
                       socket.close();
                   }
               }

           }
        });
    }


}
