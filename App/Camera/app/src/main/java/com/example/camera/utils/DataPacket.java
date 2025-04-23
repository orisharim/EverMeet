package com.example.camera.utils;

public class DataPacket {
    private String _username;
    private long _timestamp;
    private int _sequenceNumber;
    private int _totalPackets;
    private byte[] _payload;

    public DataPacket(String username, long timestamp, int sequenceNumber, int totalPackets, byte[] payload) {
        _username = username;
        _timestamp = timestamp;
        _sequenceNumber = sequenceNumber;
        _totalPackets = totalPackets;
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

    public byte[] getPayload() {
        return _payload;
    }


}
