package com.example.camera.utils;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public class Room {
    private static Room _connectedRoom = null;
    private String _id;
    private String _name;
    private String _creator;
    private List<User> _participants;

    // Required empty constructor for Firebase
    public Room() {
        _participants = new ArrayList<>();
    }

    public Room(String id, String name, String creator) {
        this._id = id;
        this._name = name;
        this._creator = creator;
        _participants = new ArrayList<>();
    }

    public Room(String id, String name, String creator, List<User> participants) {
        this._id = id;
        this._name = name;
        this._creator = creator;
        this._participants = participants;
    }

    public Room(Room room){
        this(room._id, room._name, room._creator, room.getParticipants());
    }

    public String getId() {
        return _id;
    }

    public void setId(String id) {
        this._id = id;
    }

    public String getName() {
        return _name;
    }

    public void setName(String name) {
        this._name = name;
    }

    public String getCreator() {
        return _creator;
    }

    public void setCreator(String creator) {
        this._creator = creator;
    }

    public List<User> getParticipants() {
        return _participants;
    }

    public void setParticipants(List<User> participants) {
        this._participants = participants;
    }

    public static Room getConnectedRoom() {
        return _connectedRoom;
    }

    public static void setConnectedRoom(Room room) {
        _connectedRoom = room;
    }

    @NonNull
    @Override
    public String toString() {
        return "id: "+ getId() + " participants: " + _participants.toString();
    }
}