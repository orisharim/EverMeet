package com.example.camera.utils;

import java.net.InetAddress;

public class User {
    private static User _connectedUser = null;
    private String _username;
    private String _ip;

    // required for firebase
    public User(){}

    public User(String username, String ip) {
        _username = username;
        _ip = ip;
    }

    public User(User user){
        this(user.getUsername(), user.getIp());
    }

    public String getUsername() {
        return _username;
    }

    public String getIp(){
        return _ip;
    }

    public void setUsername(String username) {
        this._username = username;
    }

    public void setIp(String ip){
        _ip = ip;
    }

    @Override
    public String toString() {
        return "User{userId='" + _username + "'}";
    }

    public static User getConnectedUser(){
        return _connectedUser;
    }

    public static void setConnectedUser(User user){
        _connectedUser = new User(user);
    }

    public static boolean isUserConnected(){
        return _connectedUser != null;
    }


}

