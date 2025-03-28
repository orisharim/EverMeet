package com.example.camera;


import android.Manifest;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import androidx.camera.core.ImageProxy;

public class MainActivity extends AppCompatActivity {

    private final static String[] PERMS = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET};
    private Camera _localCam;
    private UdpClient _sender;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        _localCam = new Camera(this, findViewById(R.id.previewView), this::onLocalCamFrameReceive);
        _sender = new UdpClient("192.168.56.1", 8000, bytes -> {
            Log.e("a", "a");});


        if(!Permissions.hasPermissions(PERMS, this)){
            Permissions.requestPermissions(PERMS, 1000, this);
        }

        _localCam.startLocalCamera();

    }

    private void onLocalCamFrameReceive(ImageProxy frame){
        byte[] data = ImageUtils.imageProxyToByteArray(frame);
        _sender.updateData(data);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        _localCam.stopCamera();
    }

}