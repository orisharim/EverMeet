package com.example.camera.classes;

import java.util.List;

public class User {
    private static User _connectedUser = null;
    private String _username;
    private String _ip;
    private List<String> _friends;
    private String _password;

    // required for firebase
    public User(){}

    public User(String username, String password, String ip, List<String> friends) {
        _username = username;
        _password = password;
        _ip = ip;
        _friends = friends;
    }

    public User(User user){
        this(user.getUsername(), user.getPassword(), user.getIp(), user.getFriends());
    }

    public String getUsername() {
        return _username;
    }

    public void setUsername(String username) {
        this._username = username;
    }

    public String getIp(){
        return _ip;
    }

    public void setIp(String ip){
        _ip = ip;
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

    public static void setConnectedUser(User user){
        _connectedUser = user;
    }

    public static boolean isUserConnected(){
        return _connectedUser != null;
    }


}

