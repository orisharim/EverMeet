package com.example.camera.utils;

import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.example.camera.activities.CallActivity;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class DatabaseManager {


    private static DatabaseManager _instance = new DatabaseManager();
    private DatabaseReference _db;

    private DatabaseManager(){
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

    public void addUser(User user){
        addUser(user, a -> {});
    }

    public void addRoom(String roomName, Consumer<Boolean> onComplete) {
        String roomId = generateRoomId();
        if (roomId == null) {
            return;
        }

        Room room = new Room(roomId, roomName, User.getConnectedUser().getUsername());
        room.getParticipants().add(User.getConnectedUser());

        _db.child("rooms").child(roomId).setValue(room).addOnCompleteListener(task -> {
            onComplete.accept(task.isSuccessful());
            Room.setConnectedRoom(room);
        });
    }

    public void addRoom(String roomName){
        addRoom(roomName, a -> {});
    }

    public void addRoom(Room room, Consumer<Boolean> onComplete){
        _db.child("rooms").child(room.getId()).setValue(room).addOnCompleteListener(task -> {
            onComplete.accept(task.isSuccessful());
        });
    }

    public void addRoom(Room room){
        addRoom(room, a -> {});
    }

    public void addUserToRoom(User user, Room room, Consumer<Boolean> onComplete){
        if (!room.getParticipants().contains(user)) {
            room.getParticipants().add(User.getConnectedUser());
            _db.child("rooms").child(room.getId()).setValue(room).addOnCompleteListener(task -> {
                onComplete.accept(task.isSuccessful());
            });;
        }
    }

    public void addUserToRoom(User user, Room room){
        addUserToRoom(user, room, a -> {});
    }

    public void removeUserFromRoom(User user, Room room, Consumer<Boolean> onComplete){
        if (room.getParticipants().contains(user)) {
            room.getParticipants().remove(User.getConnectedUser());
            if (room.getParticipants().isEmpty()) {
                _db.child("rooms").child(room.getId()).removeValue().addOnCompleteListener(task -> {
                    onComplete.accept(task.isSuccessful());
                });
            }
            else {
                _db.child("rooms").child(room.getId()).setValue(room).addOnCompleteListener(task -> {
                    onComplete.accept(task.isSuccessful());
                });
            }
        }
    }

    public void removeUserFromRoom(User user, Room room){
        removeUserFromRoom(user, room, a -> {});
    }

    private String generateRoomId(){
        return _db.child("rooms").push().getKey();
    }

    public void setOnRoomsDataChangeReceive(Consumer<List<Room>> onRoomsChange){
        _db.child("rooms").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<Room> rooms = new ArrayList<>();
                for (DataSnapshot roomSnapshot : snapshot.getChildren()) {
                    Room room = roomSnapshot.getValue(Room.class);
                    if (room != null) {
                        room.setId(roomSnapshot.getKey());
                        rooms.add(room);
                    }
                }

                if(Room.getConnectedRoom() != null){
                    for (Room room: rooms) {
                        if(room.getId().equals(Room.getConnectedRoom().getId())){
                            Room.setConnectedRoom(new Room(room));
                        }
                    }
                }

                onRoomsChange.accept(rooms);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });



    }






}
