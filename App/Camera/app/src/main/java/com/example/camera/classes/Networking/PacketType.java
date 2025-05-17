package com.example.camera.classes.Networking;

public enum PacketType {
    VIDEO((byte) 0),
    AUDIO((byte) 1),
    UNKNOWN((byte) -1);

    private final byte _value;

    PacketType(byte value) {
        this._value = value;
    }

    public byte toByte() {
        return _value;
    }

    public static PacketType fromByte(byte value) {
        for (PacketType type : PacketType.values()) {
            if (type.toByte() == value) {
                return type;
            }
        }
        return UNKNOWN;
    }
}