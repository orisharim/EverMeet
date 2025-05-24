package com.example.camera.classes.Networking.RTP;

public class CompleteData {
    private String _username;
    private long _timestamp;
    private PacketType _packetType; // New field for packet type
    private byte[] _data;

    public CompleteData(String username, long timestamp, PacketType packetType, byte[] data) {
        _username = username;
        _timestamp = timestamp;
        _packetType = packetType;
        _data = data;
    }

    public String getUsername() {
        return _username;
    }

    public long getTimestamp() {
        return _timestamp;
    }

    public PacketType getPacketType() {
        return _packetType;
    }

    public byte[] getData() {
        return _data;
    }
}