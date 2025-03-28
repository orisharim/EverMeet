package com.example.camera;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class DataSender {

    private volatile byte[] _data;
    private final String _serverIp;
    private final int _serverPort;

    public DataSender(String serverIp, int serverPort){
        _serverIp = new String(serverIp);
        _serverPort = serverPort;


        new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                InetAddress serverAddress = InetAddress.getByName(_serverIp);

                while (true) {
                    DatagramPacket sendPacket = new DatagramPacket(_data, _data.length, serverAddress, _serverPort);
                    socket.send(sendPacket);
                    System.out.println("Sent frame");

                    Thread.sleep(1);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void updateData(byte[] data){
        _data = data;
    }


}
