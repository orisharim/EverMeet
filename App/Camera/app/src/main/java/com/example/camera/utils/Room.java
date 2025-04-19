package com.example.camera.utils;

import android.widget.Toast;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class Room {
    private static Room _connectedRoom = null;
    private String id;
    private String name;
    private String creator;
    private List<User> participants;

    // Required empty constructor for Firebase
    public Room() {
        participants = new ArrayList<>();
    }

    public Room(String id, String name, String creator) {
        this.id = id;
        this.name = name;
        this.creator = creator;
        participants = new ArrayList<>();
    }

    public Room(String id, String name, String creator, List<User> participants) {
        this.id = id;
        this.name = name;
        this.creator = creator;
        this.participants = participants;
    }

    public Room(Room room){
        this(room.id, room.name, room.creator, room.getParticipants());
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public List<User> getParticipants() {
        return participants;
    }

    public void setParticipants(List<User> participants) {
        this.participants = participants;
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
        return "id: "+ getId() + " participants: " + participants.toString();
    }
}