package com.example.camera.classes;

public class Connection {
    private String _username;
    private String _userIp;
    private Thread _sendThread;

    public Connection(String username, String userIp, Thread sendThread) {
        this._username = username;
        this._userIp = userIp;
        this._sendThread = sendThread;
    }

    public String getUsername() {
        return _username;
    }

    public void setUsername(String username) {
        this._username = username;
    }

    public String getUserIp() {
        return _userIp;
    }

    public void setUserIp(String userIp) {
        this._userIp = userIp;
    }

    public Thread getSendThread() {
        return _sendThread;
    }

    public void setSendThread(Thread sendThread) {
        this._sendThread = sendThread;
    }
}
