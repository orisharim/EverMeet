package com.example.camera.classes.Networking;

import android.util.Log;

import java.net.DatagramPacket;
import java.nio.ByteBuffer;

public class DataPacket {
    private String _username;
    private long _timestamp;
    private int _sequenceNumber;
    private int _totalPackets;
    private PacketType _packetType;
    private byte[] _payload;

    public DataPacket(String username, long timestamp, int sequenceNumber, int totalPackets, PacketType packetType, byte[] payload) {
        _username = username;
        _timestamp = timestamp;
        _sequenceNumber = sequenceNumber;
        _totalPackets = totalPackets;
        _packetType = packetType;
        _payload = payload;
    }

    public String getUsername() {
        return _username;
    }

    public long getTimestamp() {
        return _timestamp;
    }

    public int getSequenceNumber() {
        return _sequenceNumber;
    }

    public int getTotalPackets() { return _totalPackets; }

    public PacketType getPacketType() {
        return _packetType;
    }

    public byte[] getPayload() {
        return _payload;
    }

    public static DataPacket parsePacket(DatagramPacket datagram) {
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
            Log.e("DataPacket", "failed to process packet");
            return null;
        }
    }

}