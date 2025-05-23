package com.example.camera.activities;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;
import androidx.recyclerview.widget.GridLayoutManager;

import com.example.camera.R;
import com.example.camera.adapters.CamerasAdapter;
import com.example.camera.classes.Camera;
import com.example.camera.classes.Networking.PacketType;
import com.example.camera.classes.Room;
import com.example.camera.classes.User;
import com.example.camera.databinding.ActivityCallBinding;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.managers.PeerConnectionManager;
import com.example.camera.receivers.InternetConnectionChangeReceiver;
import com.example.camera.utils.ImageConversionUtils;

import java.util.HashMap;

public class CallActivity extends AppCompatActivity {

    private static final String TAG = "CallActivity";
    private static final String[] PERMS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET
    };

    private ActivityCallBinding _views;
    private Camera _localCam;
    private boolean _isCamClosed;
    private boolean _isMuted;
    private CamerasAdapter _camerasAdapter;
    private InternetConnectionChangeReceiver _internetConnectionChangeReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());

        setFullScreenMode();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        _isCamClosed = true;
        _isMuted = true;

        setupCameraGrid();
        setupLocalCamera();
        setupRoomListener();
        setupUIListeners();
        setupPeerFrameListener();
        enableLocalCameraDrag();

        _internetConnectionChangeReceiver = new InternetConnectionChangeReceiver();
        registerInternetConnectionChangeReceiver();

    }

    private void setFullScreenMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        );
    }

    private void setupCameraGrid() {
        _camerasAdapter = new CamerasAdapter();
        _views.camerasGrid.setLayoutManager(new GridLayoutManager(this, 2));
        _views.camerasGrid.setAdapter(_camerasAdapter);
    }

    private void setupLocalCamera() {
        _localCam = new Camera(
                CameraSelector.DEFAULT_FRONT_CAMERA,
                this,
                _views.localCamera,
                this::onLocalCamFrameReceive
        );
        _localCam.startLocalCamera();
    }

    private void setupRoomListener() {
        String roomId = Room.getConnectedRoom().getId();
        DatabaseManager.getInstance().setOnRoomDataReceived(roomId, room -> {
            if (room == null) {
                Toast.makeText(this, "Room closed", Toast.LENGTH_SHORT).show();
                leaveCall();
                return;
            }

            Room.connectToRoom(room);

            HashMap<String, Bitmap> otherParticipantsCameras = new HashMap<>();
            for (String username : room.getParticipants().keySet()) {
                if (!username.equals(User.getConnectedUser().getUsername())) {
                    otherParticipantsCameras.put(username, Bitmap.createBitmap(300, 300, Bitmap.Config.ALPHA_8));
                }
            }

            _camerasAdapter.setParticipants(otherParticipantsCameras);
        });
    }


    private void setupUIListeners() {
        _views.micButton.setOnClickListener(v -> {
            _isMuted = !_isMuted;
            _views.micButton.setImageResource(_isMuted ? R.drawable.muted_mic : R.drawable.mic);
        });

        _views.cameraButton.setOnClickListener(v -> {
            _isCamClosed = !_isCamClosed;
            _views.cameraButton.setImageResource(_isCamClosed ? R.drawable.closed_cam : R.drawable.cam);
        });

        _views.leaveButton.setOnClickListener(v -> leaveCall());
    }

    private void setupPeerFrameListener() {
        PeerConnectionManager.getInstance().setOnCompleteDataReceived(completeData -> {
            runOnUiThread(() -> _camerasAdapter.updateParticipantFrame(
                    completeData.getUsername(),
                    ImageConversionUtils.byteArrayToBitmap(completeData.getData())
            ));
        });
    }

    private void enableLocalCameraDrag() {
        _views.localCameraFrame.setOnTouchListener(new View.OnTouchListener() {
            private float dX, dY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = v.getX() - event.getRawX();
                        dY = v.getY() - event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        v.animate()
                                .x(event.getRawX() + dX)
                                .y(event.getRawY() + dY)
                                .setDuration(0)
                                .start();
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void onLocalCamFrameReceive(ImageProxy frame) {
        byte[] frameData;
//        if(!_isCamClosed) {
            frameData = ImageConversionUtils.bitmapToByteArray(
                    ImageConversionUtils.imageToBitmap(frame.getImage())
            );
//        }
//        else{
//            frameData = ImageConversionUtils.drawableToByteArray(Drawable.createFromPath("res/drawable/cam_off_in_call.xml"));
//        }

            PeerConnectionManager.getInstance().setDataSupplier(PacketType.VIDEO, () -> frameData);

    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        if (Room.getConnectedRoom() != null) leaveCall();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (Room.getConnectedRoom() != null) leaveCall();
    }

    private void leaveCall() {
        PeerConnectionManager.getInstance().shutdown();
        _localCam.stopCamera();
        DatabaseManager.getInstance().setOnRoomDataReceived(Room.getConnectedRoom().getId(), room -> {});
        DatabaseManager.getInstance().removeUserFromRoom(
                User.getConnectedUser(),
                Room.getConnectedRoom(),
                success -> {}
        );
        startActivity(new Intent(this, HomeActivity.class));
    }

    private void registerInternetConnectionChangeReceiver(){
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(_internetConnectionChangeReceiver, filter);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(_internetConnectionChangeReceiver);
    }
}

