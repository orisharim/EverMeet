package com.example.camera.utils;

import android.content.Intent;
import android.widget.Toast;

import com.example.camera.activities.CallActivity;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.function.Consumer;

public class DatabaseManager {


    private static DatabaseManager _instance = new DatabaseManager();
    private DatabaseReference _db;


    private DatabaseManager(){
        _instance = new DatabaseManager();
        _db = FirebaseDatabase.getInstance().getReference();
    }

    public static DatabaseManager getInstance(){
        return _instance;
    }


    public void addUser(User user, Consumer<Boolean> onComplete){
        _db.child("users").child(user.getUsername()).setValue(true).addOnCompleteListener(task -> {
            onComplete.accept(task.isSuccessful());
        });

    }

    public void addRoom(String creatorName, String roomName, Consumer<Boolean> onComplete) {
        String roomId = _db.child("rooms").push().getKey();
        if (roomId == null) {
            return;
        }

        Room room = new Room(roomId, roomName, creatorName);
        room.getParticipants().put(creatorName, true);

        _db.child("rooms").child(roomId).setValue(room).addOnCompleteListener(task -> {
            onComplete.accept(task.isSuccessful());
        });
    }

    public void addRoom(Room room, Consumer<Boolean> onComplete){
        _db.child("rooms").child(room.getId()).setValue(room).addOnCompleteListener(task -> {
            onComplete.accept(task.isSuccessful());
        });
    }





}
