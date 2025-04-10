package com.example.camera.utils;

import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

public class Room {
    private String id;
    private String name;
    private String creator;
    private Map<String, Boolean> participants = new HashMap<>();

    // Required empty constructor for Firebase
    public Room() {}

    public Room(String id, String name, String creator) {
        this.id = id;
        this.name = name;
        this.creator = creator;
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

    public Map<String, Boolean> getParticipants() {
        return participants;
    }

    public void setParticipants(Map<String, Boolean> participants) {
        this.participants = participants;
    }




}