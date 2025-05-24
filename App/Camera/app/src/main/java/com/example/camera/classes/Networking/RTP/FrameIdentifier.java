package com.example.camera.classes.Networking.RTP;

import java.util.Objects;

public class FrameIdentifier {
    private final long timestamp;
    private final String username;
    private final PacketType packetType;

    public FrameIdentifier(long timestamp, String username, PacketType packetType) {
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
        FrameIdentifier that = (FrameIdentifier) o;
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