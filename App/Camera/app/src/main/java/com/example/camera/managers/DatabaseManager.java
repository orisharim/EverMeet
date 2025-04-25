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

    public void setOnRoomsDataChange(Consumer<List<Room>> onRoomsChange){
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

    public void setOnRoomDataChange(Room room, Runnable onRoomsChange) {
        _db.child("rooms").child(room.getId()).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                onRoomsChange.run();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    public void setOnFriendRequestsReceived(Consumer<List<String>> onFriendRequestsReceived){
        _db.child("friend_requests").child(User.getConnectedUser().getUsername())
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        List<String> requests = new ArrayList<>();

                        for (DataSnapshot child : snapshot.getChildren()) {
                            String username = child.getValue(String.class);
                            if (username != null){
                                requests.add(username);
                            }
                        }

                        onFriendRequestsReceived.accept(requests);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    public void removeFriendRequest(String currentUsername, String fromUsername) {
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("friend_requests").child(currentUsername);
        ref.orderByValue().equalTo(fromUsername).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot child : snapshot.getChildren()) {
                    child.getRef().removeValue();
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    public void acceptFriendRequest(String currentUsername, String fromUsername, Consumer<Boolean> onComplete) {
        DatabaseReference usersRef = _db.child("users");
        DatabaseReference requestsRef = _db.child("friend_requests");

        usersRef.child(currentUsername).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot1) {
                User currentUser = snapshot1.getValue(User.class);
                if (currentUser == null) {
                    currentUser = new User(currentUsername, "", new ArrayList<>(), true);
                } else if (currentUser.getFriends() == null) {
                    currentUser.setFriends(new ArrayList<>());
                }

                if (!currentUser.getFriends().contains(fromUsername)) {
                    currentUser.getFriends().add(fromUsername);
                }

                User finalCurrentUser = currentUser;
                usersRef.child(fromUsername).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot2) {
                        User fromUser = snapshot2.getValue(User.class);
                        if (fromUser == null) {
                            fromUser = new User(fromUsername, "", new ArrayList<>(), true);
                        } else if (fromUser.getFriends() == null) {
                            fromUser.setFriends(new ArrayList<>());
                        }

                        if (!fromUser.getFriends().contains(currentUsername)) {
                            fromUser.getFriends().add(currentUsername);
                        }

                        // save updated users
                        User finalFromUser = fromUser;
                        usersRef.child(currentUsername).setValue(finalCurrentUser).addOnCompleteListener(task1 -> {
                            if (task1.isSuccessful()) {
                                usersRef.child(fromUsername).setValue(finalFromUser).addOnCompleteListener(task2 -> {
                                    if (task2.isSuccessful()) {
                                        // remove the friend request from currentUser's requests list
                                        requestsRef.child(currentUsername)
                                                .orderByValue()
                                                .equalTo(fromUsername)
                                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                                    @Override
                                                    public void onDataChange(@NonNull DataSnapshot requestSnapshot) {
                                                        for (DataSnapshot child : requestSnapshot.getChildren()) {
                                                            child.getRef().removeValue();
                                                        }
                                                        onComplete.accept(true);
                                                    }

                                                    @Override
                                                    public void onCancelled(@NonNull DatabaseError error) {
                                                        onComplete.accept(false);
                                                    }
                                                });
                                    } else {
                                        onComplete.accept(false);
                                    }
                                });
                            } else {
                                onComplete.accept(false);
                            }
                        });
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        onComplete.accept(false);
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                onComplete.accept(false);
            }
        });
    }



}
