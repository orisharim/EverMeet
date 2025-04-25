package com.example.camera.managers;

import androidx.annotation.NonNull;

import com.example.camera.classes.Room;
import com.example.camera.classes.User;
import com.example.camera.utils.NetworkingUtils;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
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


    public interface OnUserAdded{
        void onSuccess(User user);
        void onFail();
    }

    public void addUser(String username, OnUserAdded onUserAdded) {
        _db.child("users").child(username).addListenerForSingleValueEvent(new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    User existingUser = snapshot.getValue(User.class);
                    onUserAdded.onSuccess(existingUser);
                } else {
                    User newUser = new User(username, NetworkingUtils.getLocalIpAddress(), new LinkedList<>());

                    _db.child("users").child(username).setValue(newUser)
                            .addOnCompleteListener(task -> {
                                if(task.isSuccessful()){
                                    onUserAdded.onSuccess(newUser);
                                } else{
                                    onUserAdded.onFail();
                                }
                            });
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                onUserAdded.onFail();
            }
        });
    }

    public interface OnRoomAdded{
        void onSuccess(Room room);
        void onFail();
    }

    public void addRoom(String roomName, String creator, OnRoomAdded onRoomAdded) {
        String roomId = generateRoomId();
        if (roomId == null) {
            onRoomAdded.onFail();
            return;
        }

        _db.child("rooms").child(roomId).addListenerForSingleValueEvent(new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    Room existingRoom = snapshot.getValue(Room.class);
                    onRoomAdded.onSuccess(existingRoom);
                } else {
                    Room newRoom = new Room(roomId, roomName, creator);

                    _db.child("rooms").child(roomId).setValue(newRoom)
                            .addOnCompleteListener(task -> {
                                if(task.isSuccessful()){
                                    onRoomAdded.onSuccess(newRoom);
                                } else{
                                    onRoomAdded.onFail();
                                }
                            });
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                onRoomAdded.onFail();
            }
        });
    }

    public void addUserToRoom(User user, Room room, Consumer<Boolean> onComplete){
        if (!room.getParticipants().contains(user)) {
            room.getParticipants().add(user);
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

                onRoomsChange.accept(rooms);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    public void setOnRoomDataChange(String roomId, Consumer<Room> onRoomChange) {
        _db.child("rooms").child(roomId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Room newRoom = snapshot.getValue(Room.class);

                onRoomChange.accept(newRoom);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {}
        });
    }

    public void setOnFriendRequestsReceived(String username, Consumer<List<String>> onFriendRequestsReceived){
        _db.child("friend_requests").child(username)
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

    public void setOnFriendsDataReceived(String username, Consumer<List<String>> onFriendsDataReceived){
        _db.child("users").child(username).child("friends")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        List<String> friends = new ArrayList<>();

                        for (DataSnapshot child : snapshot.getChildren()) {
                            String friend = child.getValue(String.class);
                            if (friend != null){
                                friends.add(friend);
                            }
                        }

                        onFriendsDataReceived.accept(friends);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {}
                });
    }

    public void sendFriendRequest(String fromUsername, String toUsername, Consumer<Boolean> onComplete) {
        _db.child("friend_requests").child(toUsername).push().setValue(fromUsername).addOnCompleteListener(task -> {
            onComplete.accept(task.isSuccessful());
        });
    }

    public void removeFriendRequest(String currentUsername, String fromUsername, Consumer<Boolean> onComplete) {
        FirebaseDatabase.getInstance().getReference("friend_requests").child(currentUsername)
                .orderByValue().equalTo(fromUsername).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        for (DataSnapshot child : snapshot.getChildren()) {
                            child.getRef().removeValue();
                        }
                        onComplete.accept(true);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        onComplete.accept(false);
                    }
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
                    currentUser = new User(currentUsername, "", new ArrayList<>());
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
                            fromUser = new User(fromUsername, "", new ArrayList<>());
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
