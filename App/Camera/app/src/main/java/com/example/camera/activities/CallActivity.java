package com.example.camera.activities;

import android.Manifest;
import android.os.Bundle;
import android.widget.ImageView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;

import com.example.camera.R;
import com.example.camera.managers.CameraManager;
import com.example.camera.managers.PeerConnectionManager;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.ImageUtils;
import com.example.camera.utils.PermissionsUtils;
import com.example.camera.utils.Room;
import com.example.camera.utils.User;

public class CallActivity extends AppCompatActivity {

    private final static String[] PERMS = {android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET};
    private CameraManager _localCam;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        _localCam = new CameraManager(this, findViewById(R.id.previewView), this::onLocalCamFrameReceive);

        _localCam.startLocalCamera();



    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void onLocalCamFrameReceive(ImageProxy frame){
        byte[] data = ImageUtils.bitmapToByteArray(ImageUtils.imageToBitmap(frame.getImage()));

    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        _localCam.stopCamera();
        DatabaseManager.getInstance().removeUserFromRoom(User.getConnectedUser(), Room.getConnectedRoom());
    }
}