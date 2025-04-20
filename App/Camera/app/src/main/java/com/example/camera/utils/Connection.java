package com.example.camera.utils;

public class Connection {
    private User _user;
    private Thread _sendThread;
    private Thread _receiveThread;

    public Connection(User user, Thread sendThread, Thread receiveThread) {
        this._user = user;
        this._sendThread = sendThread;
        this._receiveThread = receiveThread;
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

    public Thread getReceiveThread() {
        return _receiveThread;
    }

    public void setReceiveThread(Thread receiveThread) {
        this._receiveThread = receiveThread;
    }

}
