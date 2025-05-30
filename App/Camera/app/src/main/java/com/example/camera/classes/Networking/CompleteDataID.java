package com.example.camera.classes.Networking;

import java.util.Objects;

public class CompleteDataID {
    private final long timestamp;
    private final String username;
    private final PacketType packetType;

    public CompleteDataID(long timestamp, String username, PacketType packetType) {
        this.timestamp = timestamp;
        this.username = username;
        this.packetType = packetType;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getUsername() {
        return username;
    }

    public PacketType getPacketType() {
        return packetType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CompleteDataID that = (CompleteDataID) o;
        return timestamp == that.timestamp &&
                Objects.equals(username, that.username) &&
                packetType == that.packetType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(timestamp, username, packetType);
    }

    @Override
    public String toString() {
        return "FrameIdentifier{" +
                "timestamp=" + timestamp +
                ", username='" + username + '\'' +
                ", packetType=" + packetType +
                '}';
    }
}