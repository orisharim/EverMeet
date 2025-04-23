package com.example.camera.utils;

public class Connection {
    private User _user;
    private Thread _sendThread;

    public Connection(User user, Thread sendThread) {
        this._user = user;
        this._sendThread = sendThread;
    }

    public User getUser() {
        return _user;
    }

    public void setUser(User user) {
        this._user = user;
    }

    public Thread getSendThread() {
        return _sendThread;
    }

    public void setSendThread(Thread sendThread) {
        this._sendThread = sendThread;
    }
}

