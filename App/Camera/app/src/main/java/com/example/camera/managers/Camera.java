package com.example.camera.managers;

import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.example.camera.utils.Room;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public class Camera {
    private final AppCompatActivity _activity;
    private final PreviewView _previewView;
    private ProcessCameraProvider _cameraProvider;
    private Preview _cameraPreview;
    private final ExecutorService _cameraExecutor;
    private ImageAnalysis _frameReader;
    private Consumer<ImageProxy> _frameHandler;

    public Camera(AppCompatActivity activity, PreviewView previewView, Consumer<ImageProxy> frameHandler) {
        _activity = activity;
        _previewView = previewView;
        _cameraExecutor = Executors.newFixedThreadPool(2); // Increased threads for performance
        _frameHandler = frameHandler;
    }

    public Camera(AppCompatActivity activity, PreviewView previewView) {
        this(activity, previewView, null);
    }

    public void startLocalCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(_activity);
        cameraProviderFuture.addListener(() -> {
            try {
                _cameraProvider = cameraProviderFuture.get();
                _cameraPreview = new Preview.Builder().build();
                _cameraPreview.setSurfaceProvider(_previewView.getSurfaceProvider());

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                _cameraProvider.unbindAll();

                _frameReader = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();

                // Run the frame analysis on a separate thread (NOT the main thread)
                _frameReader.setAnalyzer(_cameraExecutor, this::onFrameReceive);

                androidx.camera.core.Camera camera = _cameraProvider.bindToLifecycle(
                        _activity, cameraSelector, _cameraPreview, _frameReader);

            } catch (InterruptedException | ExecutionException e) {
                Log.e("Camera", "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(_activity));
    }

    public void setFrameHandler(Consumer<ImageProxy> onFrameReceive) {
        _frameHandler = onFrameReceive;
    }

    public void stopCamera() {
        if (_cameraProvider != null) {
            _cameraProvider.unbindAll();
        }
        _cameraExecutor.shutdownNow(); // Stop processing immediately
    }

    private void onFrameReceive(ImageProxy image) {
        try {
            if (_frameHandler != null) {
                _frameHandler.accept(image);
            }
        } catch (Exception e) {
            Log.e("Camera", "Error processing frame", e);
        } finally {
            image.close(); // Ensure image is closed to avoid memory leaks
            Log.e("connected room", Room.getConnectedRoom().toString());
        }
    }
}
