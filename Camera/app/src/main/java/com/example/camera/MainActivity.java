package com.example.camera;


import android.Manifest;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
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
    private PreviewView _previewView;
    private ProcessCameraProvider _cameraProvider;
    private Preview _cameraPreview;
    private ExecutorService _cameraExecutor;
    private ImageAnalysis _frameReader;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        _previewView = findViewById(R.id.previewView);
        _cameraExecutor = Executors.newSingleThreadExecutor();

        if(!Permissions.hasPermissions(PERMS, this)){
            //TODO: bitch to the user about permissions
            Permissions.requestPermissions(PERMS, 1000, this);

        }

        startCamera();

    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                _cameraProvider = cameraProviderFuture.get();
                _cameraPreview = new Preview.Builder().build();
                _cameraPreview.setSurfaceProvider(_previewView.getSurfaceProvider());
                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                _cameraProvider.unbindAll();
                _frameReader = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                _frameReader.setAnalyzer(ContextCompat.getMainExecutor(this), image -> {});
                Camera camera = _cameraProvider.bindToLifecycle(
                        this, cameraSelector, _cameraPreview, _frameReader);
            } catch (InterruptedException | ExecutionException e) {
                Log.e("Camera", "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void stopCamera(){
        _cameraExecutor.shutdown();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera();
    }

}