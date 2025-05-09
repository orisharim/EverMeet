package com.example.camera.classes;

import androidx.annotation.NonNull;

import com.example.camera.managers.PeerConnectionManager;

import java.util.HashMap;
import java.util.Map;

public class Room {
    private static Room _connectedRoom = null;
    private String _id;
    private String _name;
    private String _creator;
    private Map<String, String> _participants;

    // Required empty constructor for Firebase
    public Room() {
        _participants = new HashMap<>();
    }

    public Room(String id, String name, String creator) {
        this._id = id;
        this._name = name;
        this._creator = creator;
        _participants = new HashMap<>();
    }

    public Room(String id, String name, String creator, Map<String, String> participants) {
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

    public Map<String, String> getParticipants() {
        return _participants;
    }

    public void setParticipants(Map<String, String> participants) {
        this._participants = participants;
    }

    public static Room getConnectedRoom() {
        return _connectedRoom;
    }

    public static void connectToRoom(Room room) {
        _connectedRoom = room;
        if(room != null)
            PeerConnectionManager.getInstance().connectToParticipants();
        else
            PeerConnectionManager.getInstance().shutdown();
    }

    @NonNull
    @Override
    public String toString() {
        return "id: "+ getId() + " participants: " + _participants.toString();
    }
}
