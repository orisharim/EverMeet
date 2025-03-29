package com.example.camera;


import android.Manifest;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;

import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;

public class MainActivity extends AppCompatActivity {

    private final static String[] PERMS = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET};
    private Camera _localCam;
    private DataSender _sender;
    private ImageView image;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        _localCam = new Camera(this, findViewById(R.id.previewView), this::onLocalCamFrameReceive);
        _sender = new DataSender("10.0.0.32", 12345, bytes -> {});

        image = findViewById(R.id.imageView);;

        if(!Permissions.hasPermissions(PERMS, this)){
            Permissions.requestPermissions(PERMS, 1000, this);
        }

        _localCam.startLocalCamera();




    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void onLocalCamFrameReceive(ImageProxy frame){
        byte[] data = ImageUtils.bitmapToByteArray(ImageUtils.imageToBitmap(frame.getImage()));
        _sender.updateData(data);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        _localCam.stopCamera();
    }

}