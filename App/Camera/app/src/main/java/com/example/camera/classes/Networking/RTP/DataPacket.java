package com.example.camera.classes.Networking.RTP;

public class DataPacket {
    private String _username;
    private long _timestamp;
    private int _sequenceNumber;
    private int _totalPackets;
    private PacketType _packetType; // New field for packet type
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
}