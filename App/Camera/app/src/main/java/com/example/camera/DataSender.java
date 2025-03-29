package com.example.camera;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.function.Consumer;

public class DataSender {

    private volatile byte[] _data;
    private final String _serverIp;
    private final int _serverPort;
    private Consumer<byte[]> _dataHandler;

    private final int PACKET_SIZE = 1400;

    enum PacketType{
        FRAME_PACKET_SIZE, //tells the server the amount of packets of data it should expect
        FRAME_DATA
    }

    public DataSender(String serverIp, int serverPort, Consumer<byte[]> dataHandler){
        _serverIp = new String(serverIp);
        _serverPort = serverPort;
        _dataHandler = dataHandler;

        Thread a = new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                InetAddress serverAddress = InetAddress.getByName(_serverIp);
                while (true) {

                    byte[] fullData = Arrays.copyOf(_data, _data.length);
                    ArrayList<byte[]> packets = new ArrayList<byte[]>();
                    byte[] firstPacketData = new byte[PACKET_SIZE];
                    firstPacketData[0] = 1;



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

    }

    public void updateData(byte[] data){
        _data = data;
    }

    private byte[] generateFirstPacket(int amountOfFrameDataPackets){
        return null;
    }




}
