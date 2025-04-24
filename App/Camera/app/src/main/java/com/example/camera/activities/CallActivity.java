package com.example.camera.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Bundle;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.camera.R;
import com.example.camera.adapters.ParticipantAdapter;
import com.example.camera.databinding.ActivityCallBinding;
import com.example.camera.managers.PeerConnectionManager;
import com.example.camera.utils.Camera;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.ImageConversionUtils;
import com.example.camera.utils.Room;
import com.example.camera.utils.User;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CallActivity extends AppCompatActivity {

    private final static String[] PERMS = {android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET};
    private ActivityCallBinding _views;
    private Camera _localCam;

    private boolean _isCamClosed;
    private boolean _isMuted;

    private ParticipantAdapter _adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());

        // lock orientation
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        _localCam = new Camera(this, findViewById(R.id.previewView), this::onLocalCamFrameReceive);
        _localCam.startLocalCamera();

        _isMuted = true;
        _isCamClosed = true;

        _adapter = new ParticipantAdapter(new ArrayList<>());
        _views.participantsRecyclerView.setAdapter(_adapter);
        _views.participantsRecyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));


        DatabaseManager.getInstance().setOnRoomDataChangeReceive(Room.getConnectedRoom(), () -> {
            List<String> otherParticipants = Room.getConnectedRoom().getParticipants().stream()
                    .map(User::getUsername)
                    .filter(name -> !name.equals(User.getConnectedUser().getUsername()))
                    .collect(Collectors.toList());

            _adapter.setParticipants(otherParticipants);
        });


        _views.micButton.setOnClickListener(view -> {
            _isMuted = !_isMuted;
            if(_isMuted)
                _views.micButton.setImageResource(R.drawable.muted_mic);
            else
                _views.micButton.setImageResource(R.drawable.mic);
            PeerConnectionManager.getInstance().connectToParticipants();
        });

        _views.cameraButton.setOnClickListener(view -> {
            _isCamClosed = !_isCamClosed;
            if(_isCamClosed)
                _views.cameraButton.setImageResource(R.drawable.closed_cam);
            else
                _views.cameraButton.setImageResource(R.drawable.cam);

        });

        _views.leaveButton.setOnClickListener(view -> {leaveCall();});

        PeerConnectionManager.getInstance().setOnCompleteDataReceived(data -> {
            _adapter.updateFrame(data.getUsername(), ImageConversionUtils.byteArrayToBitmap(data.getPayload()));
        });
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void onLocalCamFrameReceive(ImageProxy frame){
        byte[] frameData = ImageConversionUtils.bitmapToByteArray(ImageConversionUtils.imageToBitmap(frame.getImage()));
        PeerConnectionManager.getInstance().setDataSupplier(() -> {return frameData;});
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        leaveCall();
    }

    @Override
    public void onStop() {
        super.onStop();
        leaveCall();
    }

    private void leaveCall(){
        _localCam.stopCamera();
        DatabaseManager.getInstance().removeUserFromRoom(User.getConnectedUser(), Room.getConnectedRoom(), aBoolean -> {});
        startActivity(new Intent(this, RoomPickerActivity.class));
    }
}