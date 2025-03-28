package com.example.camera;


import android.Manifest;
import android.media.Image;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private final static String[] PERMS = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    private Camera _localCam;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        _localCam = new Camera(this, findViewById(R.id.previewView));

        if(!Permissions.hasPermissions(PERMS, this)){
            Permissions.requestPermissions(PERMS, 1000, this);
        }

        _localCam.startLocalCamera();

    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void onFrameReceive(ImageProxy frame){
        Image img = frame.getImage();
        
        img.getPlanes()
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        _localCam.stopCamera();
    }

}