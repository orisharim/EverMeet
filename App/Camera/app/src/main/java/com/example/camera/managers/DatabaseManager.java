package com.example.camera.managers;

import android.util.Log; // Make sure to import Android's Log class

import androidx.annotation.NonNull;

import com.example.camera.classes.Room;
import com.example.camera.classes.User;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

public class DatabaseManager {
    private static final String TAG = "DatabaseManager";
    private static DatabaseManager _instance = new DatabaseManager();

    private DatabaseReference _db;

    private DatabaseManager() {
        _db = FirebaseDatabase.getInstance().getReference();
    }

    public static DatabaseManager getInstance() {
        return _instance;
    }

    public void doesUsernameExist(String username, Consumer<Boolean> onResult) {
        _db.child("users").child(username).addListenerForSingleValueEvent(new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                onResult.accept(snapshot.exists());
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "doesUsernameExist failed: " + error.getMessage());
                onResult.accept(false);
            }
        });
    }

    public void checkPassword(String username, String password, Consumer<Boolean> onResult) {
        _db.child("users").child(username).addListenerForSingleValueEvent(new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                User existingUser = snapshot.getValue(User.class);
                if (existingUser != null)
                    onResult.accept(existingUser.getPassword().equals(password));
                else
                    onResult.accept(false);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "checkPassword failed: " + error.getMessage());
                onResult.accept(false);
            }
        });
    }


    public interface OnUserAdded {
        void onSuccess(User user);

        void onFail();
    }

    public void addNewUser(String username, String password, OnUserAdded onUserAdded) {
        doesUsernameExist(username, exists -> {
            if (!exists) {
                User newUser = new User(username, password, new LinkedList<>());
                _db.child("users").child(username).setValue(newUser)
                        .addOnCompleteListener(task -> {
                            if (task.isSuccessful()) {
                                onUserAdded.onSuccess(newUser);
                            } else {
                                Log.e(TAG, "addNewUser failed: " + task.getException().getMessage());
                                onUserAdded.onFail();
                            }
                        });
            } else {
                Log.w(TAG, "Attempted to add existing user: " + username);
                onUserAdded.onFail();
            }
        });
    }

    public interface OnUserLoaded {
        void onSuccess(User user);

        void onFail();
    }

    public void getUser(String username, OnUserLoaded onUserLoaded) {
        _db.child("users").child(username).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                User user = snapshot.getValue(User.class);
                if (user != null) {
                    onUserLoaded.onSuccess(user);
                } else {
                    Log.w(TAG, "getUser failed: User " + username + " not found.");
                    onUserLoaded.onFail();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "getUser failed: " + error.getMessage());
                onUserLoaded.onFail();
            }
        });
    }


    public void setOnUserDataReceived(String username, Consumer<User> onUserDataReceived) {
        _db.child("users").child(username).addListenerForSingleValueEvent(new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    User existingUser = snapshot.getValue(User.class);
                    onUserDataReceived.accept(existingUser);
                } else {
                    Log.w(TAG, "setOnUserDataReceived: User " + username + " does not exist.");
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "setOnUserDataReceived failed: " + error.getMessage());
            }
        });
    }


    public interface OnRoomAdded {
        void onSuccess(Room room);

        void onFail();
    }

    public void addRoom(String roomName, String creator, OnRoomAdded onRoomAdded) {
        String roomId = generateRoomId();
        if (roomId == null) {
            onRoomAdded.onFail();
            return;
        }


        _db.child("rooms").orderByChild("name").equalTo(roomName)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            for (DataSnapshot roomSnapshot : snapshot.getChildren()) {
                                Room existingRoom = roomSnapshot.getValue(Room.class);
                                if (existingRoom != null) {
                                    existingRoom.setId(roomSnapshot.getKey());
                                    onRoomAdded.onSuccess(existingRoom);
                                    Log.w(TAG, "Room with name '" + roomName + "' already exists. Returning existing room.");
                                    return;
                                }
                            }
                        }


                        Room newRoom = new Room(roomId, roomName, creator);
                        _db.child("rooms").child(roomId).setValue(newRoom)
                                .addOnCompleteListener(task -> {
                                    if (task.isSuccessful()) {
                                        onRoomAdded.onSuccess(newRoom);
                                    } else {
                                        Log.e(TAG, "addRoom failed: " + task.getException().getMessage());
                                        onRoomAdded.onFail();
                                    }
                                });
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "addRoom check for existing room failed: " + error.getMessage());
                        onRoomAdded.onFail();
                    }
                });
    }

    public void addUserToRoom(User user, String userIp, Room room, Consumer<Boolean> onComplete) {
        if (user == null || room == null || userIp == null || userIp.isEmpty()) {
            onComplete.accept(false);
            return;
        }
        if (!room.getParticipants().containsKey(user.getUsername())) {
            room.getParticipants().put(user.getUsername(), userIp);
            _db.child("rooms").child(room.getId()).setValue(room).addOnCompleteListener(task -> {
                if (!task.isSuccessful()) {
                    Log.e(TAG, "addUserToRoom failed: " + task.getException().getMessage());
                }
                onComplete.accept(task.isSuccessful());
            });
        } else {
            Log.d(TAG, "User " + user.getUsername() + " already in room " + room.getName());
            onComplete.accept(true);
        }
    }


    public void removeUserFromRoom(User user, Room room, Consumer<Boolean> onComplete) {
        if (user == null || room == null) {
            onComplete.accept(false);
            return;
        }

        if (room.getParticipants().containsKey(user.getUsername())) {
            room.getParticipants().remove(user.getUsername());
            if (room.getParticipants().isEmpty()) {
                _db.child("rooms").child(room.getId()).removeValue().addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.e(TAG, "removeUserFromRoom (delete room) failed: " + task.getException().getMessage());
                    }
                    onComplete.accept(task.isSuccessful());
                });
            } else {
                _db.child("rooms").child(room.getId()).setValue(room).addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.e(TAG, "removeUserFromRoom (update room) failed: " + task.getException().getMessage());
                    }
                    onComplete.accept(task.isSuccessful());
                });
            }
        } else {
            Log.w(TAG, "User " + user.getUsername() + " not found in room " + room.getName());
            onComplete.accept(true);
        }
    }


    private String generateRoomId() {

        return _db.child("rooms").push().getKey();
    }

    public void getRoomsData(Consumer<List<Room>> onRoomsReceived) {
        _db.child("rooms").addListenerForSingleValueEvent(new ValueEventListener() {
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<Room> rooms = new ArrayList<>();
                for (DataSnapshot roomSnapshot : snapshot.getChildren()) {
                    Room room = roomSnapshot.getValue(Room.class);
                    if (room != null) {
                        room.setId(roomSnapshot.getKey());
                        rooms.add(room);
                    }
                }
                onRoomsReceived.accept(rooms);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "getRoomsData failed: " + error.getMessage());
                onRoomsReceived.accept(null);
            }
        });
    }

    public void setOnRoomsDataReceived(Consumer<List<Room>> onRoomsChange) {
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
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "setOnRoomsDataReceived failed: " + error.getMessage());
            }
        });
    }

    public void setOnRoomDataReceived(String roomId, Consumer<Room> onRoomChange) {
        _db.child("rooms").child(roomId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Room newRoom = snapshot.getValue(Room.class);
                if (newRoom != null) {
                    newRoom.setId(snapshot.getKey());
                }
                onRoomChange.accept(newRoom);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "setOnRoomDataReceived failed: " + error.getMessage());
            }
        });
    }

    public void setOnFriendRequestsReceived(String username, Consumer<List<String>> onFriendRequestsReceived) {
        _db.child("friend_requests").child(username)
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        List<String> requests = new ArrayList<>();
                        for (DataSnapshot child : snapshot.getChildren()) {
                            String senderUsername = child.getValue(String.class);
                            if (senderUsername != null) {
                                requests.add(senderUsername);
                            }
                        }
                        onFriendRequestsReceived.accept(requests);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "setOnFriendRequestsReceived failed: " + error.getMessage());
                    }
                });
    }

    public void setOnFriendsDataReceived(String username, Consumer<List<String>> onFriendsDataReceived) {
        _db.child("users").child(username).child("friends")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        List<String> friends = new ArrayList<>();
                        for (DataSnapshot child : snapshot.getChildren()) {
                            String friend = child.getValue(String.class);
                            if (friend != null) {
                                friends.add(friend);
                            }
                        }
                        onFriendsDataReceived.accept(friends);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "setOnFriendsDataReceived failed: " + error.getMessage());
                    }
                });
    }

    public void sendFriendRequest(String fromUsername, String toUsername, Consumer<Boolean> onComplete) {
        if (fromUsername.equals(toUsername)) {
            Log.w(TAG, "Cannot send friend request to self: " + fromUsername);
            onComplete.accept(false);
            return;
        }

        doesUsernameExist(toUsername, toUserExists -> {
            if (!toUserExists) {
                Log.w(TAG, "sendFriendRequest failed: Recipient " + toUsername + " does not exist.");
                onComplete.accept(false);
                return;
            }

            getUser(fromUsername, new OnUserLoaded() {
                @Override
                public void onSuccess(User fromUser) {
                    if (fromUser.getFriends() != null && fromUser.getFriends().contains(toUsername)) {
                        Log.w(TAG, fromUsername + " and " + toUsername + " are already friends.");
                        onComplete.accept(false);
                        return;
                    }

                    _db.child("friend_requests").child(toUsername)
                            .orderByValue().equalTo(fromUsername)
                            .addListenerForSingleValueEvent(new ValueEventListener() {
                                @Override
                                public void onDataChange(@NonNull DataSnapshot snapshot) {
                                    if (snapshot.exists()) {
                                        Log.w(TAG, "Friend request from " + fromUsername + " to " + toUsername + " already exists.");
                                        onComplete.accept(false);
                                    } else {
                                        _db.child("friend_requests").child(toUsername).push().setValue(fromUsername)
                                                .addOnCompleteListener(task -> {
                                                    if (task.isSuccessful()) {
                                                        Log.d(TAG, "Friend request sent from " + fromUsername + " to " + toUsername);
                                                    } else {
                                                        Log.e(TAG, "sendFriendRequest failed: " + task.getException().getMessage());
                                                    }
                                                    onComplete.accept(task.isSuccessful());
                                                });
                                    }
                                }

                                @Override
                                public void onCancelled(@NonNull DatabaseError error) {
                                    Log.e(TAG, "sendFriendRequest check existing request failed: " + error.getMessage());
                                    onComplete.accept(false);
                                }
                            });
                }

                @Override
                public void onFail() {
                    Log.e(TAG, "sendFriendRequest failed: Sender user " + fromUsername + " not found.");
                    onComplete.accept(false);
                }
            });
        });
    }


    public void removeFriendRequest(String currentUsername, String fromUsername, Consumer<Boolean> onComplete) {
        _db.child("friend_requests").child(currentUsername)
                .orderByValue().equalTo(fromUsername).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        if (snapshot.exists()) {
                            for (DataSnapshot child : snapshot.getChildren()) {
                                child.getRef().removeValue()
                                        .addOnCompleteListener(task -> {
                                            if (!task.isSuccessful()) {
                                                Log.e(TAG, "Failed to remove friend request for " + currentUsername + " from " + fromUsername + ": " + task.getException().getMessage());
                                            } else {
                                                Log.d(TAG, "Friend request removed for " + currentUsername + " from " + fromUsername);
                                            }
                                        });
                            }
                            onComplete.accept(true);
                        } else {
                            Log.d(TAG, "No friend request found for " + currentUsername + " from " + fromUsername + ". Already removed?");
                            onComplete.accept(true);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e(TAG, "removeFriendRequest failed for " + currentUsername + " from " + fromUsername + ": " + error.getMessage());
                        onComplete.accept(false);
                    }
                });
    }


    private void addFriend(String username, String friendToAdd, Consumer<Boolean> onComplete) {
        _db.child("users").child(username).runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                User user = currentData.getValue(User.class);
                if (user == null) {
                    Log.w(TAG, "addFriend transaction: User " + username + " not found.");
                    return Transaction.abort();
                }

                if (user.getFriends() == null) {
                    user.setFriends(new ArrayList<>());
                }

                if (!user.getFriends().contains(friendToAdd)) {
                    user.getFriends().add(friendToAdd);
                }
                currentData.setValue(user);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError databaseError, boolean committed, DataSnapshot currentData) {
                if (databaseError != null) {
                    Log.e(TAG, "addFriend transaction for " + username + " failed: " + databaseError.getMessage());
                    onComplete.accept(false);
                } else if (!committed) {
                    Log.w(TAG, "addFriend transaction for " + username + " not committed.");
                    onComplete.accept(false);
                } else {
                    Log.d(TAG, friendToAdd + " added to " + username + "'s friends list.");
                    onComplete.accept(true);
                }
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


    public void removeFriend(String username, String friendUsername, Consumer<Boolean> onComplete) {
        _db.child("users").child(username).runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                User user = currentData.getValue(User.class);
                if (user == null) {
                    Log.w(TAG, "removeFriend transaction: User " + username + " not found.");
                    return Transaction.abort();
                }

                if (user.getFriends() != null) {
                    user.getFriends().remove(friendUsername);
                }
                currentData.setValue(user);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(DatabaseError databaseError, boolean committed, DataSnapshot currentData) {
                if (databaseError != null || !committed) {
                    Log.e(TAG, "removeFriend transaction for " + username + " failed: " + (databaseError != null ? databaseError.getMessage() : "not committed"));
                    onComplete.accept(false);
                } else {
                    Log.d(TAG, friendUsername + " removed from " + username + "'s friends list.");
                    _db.child("users").child(friendUsername).runTransaction(new Transaction.Handler() {
                        @NonNull
                        @Override
                        public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                            User friendUser = currentData.getValue(User.class);
                            if (friendUser == null) {
                                Log.w(TAG, "removeFriend transaction: Friend user " + friendUsername + " not found.");
                                return Transaction.abort();
                            }

                            if (friendUser.getFriends() != null) {
                                friendUser.getFriends().remove(username);
                            }
                            currentData.setValue(friendUser);
                            return Transaction.success(currentData);
                        }

                        @Override
                        public void onComplete(DatabaseError databaseError, boolean committed, DataSnapshot currentData) {
                            if (databaseError != null || !committed) {
                                Log.e(TAG, "removeFriend transaction for " + friendUsername + " failed: " + (databaseError != null ? databaseError.getMessage() : "not committed"));
                                onComplete.accept(false);
                            } else {
                                Log.d(TAG, username + " removed from " + friendUsername + "'s friends list.");
                                onComplete.accept(true);
                            }
                        }
                    });
                }
            }
        });
    }
}