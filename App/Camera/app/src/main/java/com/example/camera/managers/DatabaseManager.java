package com.example.camera.managers;

import android.util.Log;

import androidx.annotation.NonNull;

import com.example.camera.utils.Room;
import com.example.camera.utils.User;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.LinkedList;
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


    public void addUser(String username, Consumer<Boolean> onComplete) {
        _db.child("users").child(username).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    User existingUser = snapshot.getValue(User.class);
                    User.setConnectedUser(existingUser);
                    onComplete.accept(true);
                } else {
                    User newUser = new User(username, "", new LinkedList<>(), true);

                    _db.child("users").child(username).setValue(newUser)
                            .addOnCompleteListener(task -> {
                                if (task.isSuccessful()) {
                                    User.setConnectedUser(newUser);
                                } else {
                                    User.setConnectedUser(null);
                                }
                                onComplete.accept(task.isSuccessful());
                            });
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                onComplete.accept(false);
            }
        });
    }

    public Room createNewRoom(String roomName, Consumer<Boolean> onComplete) {
        String roomId = generateRoomId();
        if (roomId == null) {
            return null;
        }

        Room room = new Room(roomId, roomName, User.getConnectedUser().getUsername());
        room.getParticipants().add(User.getConnectedUser());

        _db.child("rooms").child(roomId).setValue(room).addOnCompleteListener(task -> {
            onComplete.accept(task.isSuccessful());
            if(task.isSuccessful()){
                Room.connectToRoom(room);
            }
        });

        return room;
    }

    public void addExistingRoom(Room room, Consumer<Boolean> onComplete){
        _db.child("rooms").child(room.getId()).setValue(room).addOnCompleteListener(task -> {
            onComplete.accept(task.isSuccessful());
        });
    }


    public void addUserToRoom(User user, Room room, Consumer<Boolean> onComplete){
        if (!room.getParticipants().contains(user)) {
            room.getParticipants().add(User.getConnectedUser());
            _db.child("rooms").child(room.getId()).setValue(room).addOnCompleteListener(task -> {
                onComplete.accept(task.isSuccessful());
            });;
        }
    }

    public void removeUserFromRoom(User user, Room room, Consumer<Boolean> onComplete){
        for(User participant : room.getParticipants()) {
            if (user.getUsername().equals(participant.getUsername())) {
                room.getParticipants().remove(participant);
                if (room.getParticipants().isEmpty()) {
                    _db.child("rooms").child(room.getId()).removeValue().addOnCompleteListener(task -> {
                        onComplete.accept(task.isSuccessful());
                    });
                } else {
                    _db.child("rooms").child(room.getId()).setValue(room).addOnCompleteListener(task -> {
                        onComplete.accept(task.isSuccessful());
                    });
                }
            }
        }
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
                            Room.connectToRoom(new Room(room));
                        }
                    }
                }

                onRoomsChange.accept(rooms);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    public void setOnRoomDataChangeReceive(Room room, Runnable onRoomsChange) {
        _db.child("rooms").child(room.getId()).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                onRoomsChange.run();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }





}
