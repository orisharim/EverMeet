package com.example.camera.classes;

import com.example.camera.managers.DatabaseManager;

import java.util.List;

public class User {
    private static User _connectedUser = null;
    private String _username;
    private List<String> _friends;
    private String _password;

    // required for firebase
    public User(){}

    public User(String username, String password, List<String> friends) {
        _username = username;
        _password = password;
        _friends = friends;
    }

    public User(User user){
        this(user.getUsername(), user.getPassword(), user.getFriends());
    }

    public String getUsername() {
        return _username;
    }

    public void setUsername(String username) {
        this._username = username;
    }

    public List<String> getFriends() {
        return _friends;
    }

    public void setFriends(List<String> friends) {
        this._friends = friends;
    }

    public String getPassword() {
        return _password;
    }

    public void setPassword(String password) {
        this._password = password;
    }

    @Override
    public String toString() {
        return "User{userId='" + _username + "'}";
    }

    public static User getConnectedUser(){
        return _connectedUser;
    }

    public static void connectToUser(User user){
        _connectedUser = user;
        if(user != null)
            DatabaseManager.getInstance().setOnUserDataReceived(user.getUsername(), User::connectToUser);
    }

    public static void disconnectFromConncetedUser() {
        _connectedUser = null;
    }

    public static boolean isUserConnected(){
        return _connectedUser != null;
    }


}

