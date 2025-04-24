package com.example.camera.utils;

public class CompleteData {
    private String _username;
    private long _timestamp;
    private byte[] _payload;

    public CompleteData(String username, long timestamp, byte[] payload) {
        _username = username;
        _timestamp = timestamp;
        _payload = payload;
    }

    public String getUsername() {
        return _username;
    }

    public long getTimestamp() {
        return _timestamp;
    }

    public byte[] getPayload() {
        return _payload;
    }
}
