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

import com.example.camera.R;
import com.example.camera.databinding.ActivityCallBinding;
import com.example.camera.managers.PeerConnectionManager;
import com.example.camera.utils.Camera;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.ImageConversionUtils;
import com.example.camera.utils.Room;
import com.example.camera.utils.User;

public class CallActivity extends AppCompatActivity {

    private final static String[] PERMS = {android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET};
    private ActivityCallBinding _views;
    private Camera _localCam;

    private boolean _isCamClosed;
    private boolean _isMuted;

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
        PeerConnectionManager.getInstance().setOnCompleteDataReceived(bytes -> {
            Bitmap frameDataBitmap = ImageConversionUtils.byteArrayToBitmap(bytes);
            _views.imageView.setImageBitmap(frameDataBitmap);
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
        DatabaseManager.getInstance().removeUserFromRoom(User.getConnectedUser(), Room.getConnectedRoom());
        startActivity(new Intent(this, RoomPickerActivity.class));
    }
}