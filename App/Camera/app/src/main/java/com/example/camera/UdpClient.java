package com.example.camera;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class UdpClient {

    private volatile byte[] _data;
    private final String _serverIp;
    private final int _serverPort;
    private Consumer<byte[]> _dataHandler;

    public UdpClient(String serverIp, int serverPort, Consumer<byte[]> dataHandler){
        _serverIp = new String(serverIp);
        _serverPort = serverPort;
        _dataHandler = dataHandler;

        Thread a = new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                InetAddress serverAddress = InetAddress.getByName(_serverIp);
                while (true) {
                    DatagramPacket sendPacket = new DatagramPacket(_data, _data.length, serverAddress, _serverPort);
                    socket.send(sendPacket);

                    byte[] receiveData = new byte[1024];
                    DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
                    socket.receive(receivePacket);
                    if(_dataHandler != null)
                        _dataHandler.accept(receiveData);

                    socket.close();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        a.setDaemon(true);
                a.start();
    }

    public void updateData(byte[] data){
        _data = data;
    }


}
