package com.example.camera.utils;

public class DataPacket {
    private String username;
    private long timestamp;
    private int sequenceNumber;
    private byte[] payload;

    public DataPacket(String username, long timestamp, int sequenceNumber, byte[] payload) {
        this.username = username;
        this.timestamp = timestamp;
        this.sequenceNumber = sequenceNumber;
        this.payload = payload;
    }

    public String getUsername() {
        return username;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public byte[] getPayload() {
        return payload;
    }
}
