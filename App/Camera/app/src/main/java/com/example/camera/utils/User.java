package com.example.camera.utils;

public class User {
    private String _username;

    // required for firebase
    public User(){}

    public User(String username) {
        this._username = username;
    }

    public String getUsername() {
        return _username;
    }

    public void setUserId(String username) {
        this._username = username;
    }

    @Override
    public String toString() {
        return "User{userId='" + _username + "'}";
    }
}

