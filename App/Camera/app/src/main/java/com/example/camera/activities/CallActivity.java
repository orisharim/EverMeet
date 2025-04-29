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

import com.example.camera.R;
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

    private final static String[] PERMS = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET};
    private ActivityCallBinding _views;
    private Camera _localCam;

    private boolean _isCamClosed;
    private boolean _isMuted;

    private HashMap<String, ImageView> _participantViews = new HashMap<>();

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

        _localCam = new Camera(CameraSelector.DEFAULT_FRONT_CAMERA, this, findViewById(R.id.previewView), this::onLocalCamFrameReceive);
        _localCam.startLocalCamera();

        _isMuted = true;
        _isCamClosed = true;

        DatabaseManager.getInstance().setOnRoomDataChange(Room.getConnectedRoom().getId(), room -> {
            if (room == null) {
                Toast.makeText(this, "Room closed", Toast.LENGTH_SHORT).show();
                leaveCall();
                return;
            }

            Room.connectToRoom(room);

            if (Room.getConnectedRoom() != null) {
                HashMap<String, String> otherParticipants = new HashMap<>();
                for (String username : Room.getConnectedRoom().getParticipants().keySet()) {
                    if (!username.equals(User.getConnectedUser().getUsername()))
                        otherParticipants.put(username, Room.getConnectedRoom().getParticipants().get(username));
                }

                // Remove participants who have left
                for (String username : new ArrayList<>(_participantViews.keySet())) {
                    if (!otherParticipants.containsKey(username)) {
                        removeParticipant(username);
                    }
                }

                // Add new participants if needed
                for (String username : otherParticipants.keySet()) {
                    if (!_participantViews.containsKey(username)) {
                        addParticipantView(username);
                    }
                }
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
            updateParticipantFrame(data.getUsername(), ImageConversionUtils.byteArrayToBitmap(data.getPayload()));
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

    private void updateParticipantFrame(String username, Bitmap frame) {
        runOnUiThread(() -> {
            if (!_participantViews.containsKey(username)) {
                addParticipantView(username);
            }

            ImageView view = _participantViews.get(username);
            view.setImageBitmap(frame);
        });
    }

    private void addParticipantView(String username) {
        runOnUiThread(() -> {
            View participantCard = getLayoutInflater().inflate(R.layout.item_camera, _views.camerasGrid, false);

            ImageView imageView = participantCard.findViewById(R.id.participantCamera);
            TextView nameView = participantCard.findViewById(R.id.participantUsername);

            nameView.setText(username);

            _views.camerasGrid.addView(participantCard);

            _participantViews.put(username, imageView);
        });
    }


    private void removeParticipant(String username) {
        runOnUiThread(() -> {
            ImageView view = _participantViews.remove(username);
            if (view != null) {
                _views.camerasGrid.removeView(view);
            }
        });
    }
}
