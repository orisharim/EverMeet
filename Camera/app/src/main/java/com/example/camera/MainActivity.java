package com.example.camera;


import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private PreviewView previewView;
    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageCapture imageCapture;
    private Camera camera;
    private CameraSelector cameraSelector;
    private ExecutorService cameraExecutor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Your layout file

        previewView = findViewById(R.id.viewFinder);

        // Initialize camera thread
        cameraExecutor = Executors.newSingleThreadExecutor();

        startCamera(); // Call this after permissions are granted

    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();

                // Preview
                preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());  // Important!

                // ImageCapture (Optional - for taking photos)
                imageCapture = new ImageCapture.Builder().build();



                // Choose a camera selector (back or front)
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA; // Or CameraSelector.DEFAULT_FRONT_CAMERA

                // Unbind use cases before rebinding
                cameraProvider.unbindAll();

                // Bind use cases to the lifecycle of the camera
                Camera camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture); // Add imageCapture and imageAnalysis if used

            } catch (InterruptedException | ExecutionException e) {
                // Handle any errors
                Log.e("CameraX", "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture != null) {
            File photoFile = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "photo.jpg"); // Create a file

            ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

            imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                    // Photo saved successfully
                    Uri savedUri = Uri.fromFile(photoFile);
                    // ... Use the savedUri (e.g., display the image)
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    // Handle error
                    Log.e("CameraX", "Photo capture failed: " + exception.getMessage(), exception);
                }
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown(); // Important: Shut down the executor
    }


}