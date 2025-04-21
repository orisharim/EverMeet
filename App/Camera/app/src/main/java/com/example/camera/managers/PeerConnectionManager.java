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

                // store packets by timestamps
                HashMap<Long, List<DataPacket>> timestampMap = new HashMap<>();
                boolean receiving = true;

                while (receiving) {
                    DatagramPacket datagramPacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(datagramPacket);

                    byte[] packetData = datagramPacket.getData();

                    byte[] usernameBytes = new byte[8];
                    System.arraycopy(packetData, 0, usernameBytes, 0, usernameBytes.length);
                    String username = new String(usernameBytes).trim();

                    byte[] timestampBytes = new byte[8];
                    System.arraycopy(packetData, usernameBytes.length, timestampBytes, 0, timestampBytes.length);
                    long timestamp = ByteBuffer.wrap(timestampBytes).getLong();

                    byte[] sequenceNumberBytes = new byte[4];
                    System.arraycopy(packetData, usernameBytes.length + timestampBytes.length, sequenceNumberBytes, 0, sequenceNumberBytes.length);
                    int sequenceNumber = ByteBuffer.wrap(sequenceNumberBytes).getInt();

                    int payloadStart = usernameBytes.length + timestampBytes.length + sequenceNumberBytes.length;
                    int payloadLength = datagramPacket.getLength() - payloadStart;

                    byte[] payload = new byte[payloadLength];
                    System.arraycopy(packetData, payloadStart, payload, 0, payloadLength);

                    DataPacket packet = new DataPacket(username, timestamp, sequenceNumber, payload);

                    timestampMap.putIfAbsent(timestamp, new ArrayList<>());
                    timestampMap.get(timestamp).add(packet);


                }

                socket.close();

                // reassemble data for each timestamp
                for (long timestamp : timestampMap.keySet()) {

                    List<DataPacket> packets = timestampMap.get(timestamp);

                    packets.sort((p1, p2) -> Integer.compare(p1.getSequenceNumber(), p2.getSequenceNumber()));

                    ByteBuffer dataBuffer = ByteBuffer.allocate(1024 * 1024);
                    for (DataPacket packet : packets) {
                        dataBuffer.put(packet.getPayload());
                    }

                    byte[] completeData = new byte[dataBuffer.position()];
                    dataBuffer.flip();
                    dataBuffer.get(completeData);

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

                   byte[] usernameBytes = user.getUsername().getBytes();
                   if (usernameBytes.length > 8) {
                       throw new IllegalArgumentException("Username must not exceed 8 bytes.");
                   }
                   byte[] paddedUsername = new byte[8];
                   System.arraycopy(usernameBytes, 0, paddedUsername, 0, usernameBytes.length);

                   long timestamp = System.currentTimeMillis();
                   byte[] timestampBytes = ByteBuffer.allocate(Long.BYTES).putLong(timestamp).array();

                   // adjust the payload size to account for header (username + timestamp + sequence number)
                   int headerSize = paddedUsername.length + timestampBytes.length + Integer.BYTES;
                   int payloadSize = PACKET_SIZE - headerSize;

                   int totalPackets = (int) Math.ceil((double) data.length / payloadSize);

                   for (int i = 0; i < totalPackets; i++) {
                       int start = i * payloadSize;
                       int end = Math.min(start + payloadSize, data.length);

                       byte[] payload = new byte[end - start];
                       System.arraycopy(data, start, payload, 0, end - start);

                       ByteBuffer packetBuffer = ByteBuffer.allocate(headerSize + payload.length);
                       packetBuffer.put(paddedUsername);
                       packetBuffer.put(timestampBytes);
                       packetBuffer.putInt(i);
                       packetBuffer.put(payload);

                       byte[] packetData = packetBuffer.array();

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
