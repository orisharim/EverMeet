package com.example.camera.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;
import androidx.recyclerview.widget.GridLayoutManager;

import com.example.camera.R;
import com.example.camera.adapters.CamerasAdapter;
import com.example.camera.databinding.ActivityCallBinding;
import com.example.camera.managers.PeerConnectionManager;
import com.example.camera.classes.Camera;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.ImageConversionUtils;
import com.example.camera.classes.Room;
import com.example.camera.classes.User;

import java.util.ArrayList;
import java.util.HashMap;

public class CallActivity extends AppCompatActivity {
    private static final String TAG = "CallActivity";
    private final static String[] PERMS = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET};
    private ActivityCallBinding _views;
    private Camera _localCam;

    private boolean _isCamClosed;
    private boolean _isMuted;

    private HashMap<String, Bitmap> _participantsCameras;
    private CamerasAdapter _camerasAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());


        // hide navigation bar
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        );

        // lock orientation
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        _participantsCameras = new HashMap<>();
        _camerasAdapter = new CamerasAdapter();
        _views.camerasGrid.setLayoutManager(new GridLayoutManager(this, 2)); // 2 columns grid
        _views.camerasGrid.setAdapter(_camerasAdapter);

        _localCam = new Camera(CameraSelector.DEFAULT_FRONT_CAMERA, this, findViewById(R.id.previewView), this::onLocalCamFrameReceive);
        _localCam.startLocalCamera();

        _isMuted = true;
        _isCamClosed = true;

        _views.camerasGrid.setLayoutManager(new GridLayoutManager(this, 2));

        DatabaseManager.getInstance().setOnRoomDataChange(Room.getConnectedRoom().getId(), room -> {
            if (room == null) {
                Toast.makeText(this, "Room closed", Toast.LENGTH_SHORT).show();
                leaveCall();
                return;
            }

            Room.connectToRoom(room);

            if (Room.getConnectedRoom() != null) {
                HashMap<String, Bitmap> otherParticipantsCameras = new HashMap<>();
                for (String username : Room.getConnectedRoom().getParticipants().keySet()) {
                    if (!username.equals(User.getConnectedUser().getUsername()))
                        otherParticipantsCameras.put(username, Bitmap.createBitmap(300, 300, Bitmap.Config.ALPHA_8));
                }

                _camerasAdapter.setParticipants(otherParticipantsCameras);
            }
        });

        _views.micButton.setOnClickListener(view -> {
            _isMuted = !_isMuted;
            _views.micButton.setImageResource(_isMuted ? R.drawable.muted_mic : R.drawable.mic);
        });

        _views.cameraButton.setOnClickListener(view -> {
            _isCamClosed = !_isCamClosed;
            _views.cameraButton.setImageResource(_isCamClosed ? R.drawable.closed_cam : R.drawable.cam);
        });

        _views.leaveButton.setOnClickListener(view -> leaveCall());

        PeerConnectionManager.getInstance().setOnCompleteDataReceived(data -> {
            runOnUiThread(() -> {
                _camerasAdapter.updateParticipantFrame(
                        data.getUsername(),
                        ImageConversionUtils.byteArrayToBitmap(data.getPayload())
                );
            });
        });
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void onLocalCamFrameReceive(ImageProxy frame) {
        byte[] frameData = ImageConversionUtils.bitmapToByteArray(ImageConversionUtils.imageToBitmap(frame.getImage()));
        PeerConnectionManager.getInstance().setDataSupplier(() -> frameData);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if (Room.getConnectedRoom() != null)
            leaveCall();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (Room.getConnectedRoom() != null)
            leaveCall();
    }

    private void leaveCall() {
        PeerConnectionManager.getInstance().shutdown();
        _localCam.stopCamera();
        DatabaseManager.getInstance().setOnRoomDataChange(Room.getConnectedRoom().getId(), room -> {});
        DatabaseManager.getInstance().removeUserFromRoom(User.getConnectedUser(), Room.getConnectedRoom(), aBoolean -> {});
        startActivity(new Intent(this, HomeActivity.class));
    }







}
