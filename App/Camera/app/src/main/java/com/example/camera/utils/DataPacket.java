package com.example.camera.utils;

public class DataPacket {
    private String _username;
    private long _timestamp;
    private int _sequenceNumber;
    private byte[] _payload;

    public DataPacket(String username, long timestamp, int sequenceNumber, byte[] payload) {
        this._username = username;
        this._timestamp = timestamp;
        this._sequenceNumber = sequenceNumber;
        this._payload = payload;
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

    public byte[] getPayload() {
        return _payload;
    }
}
