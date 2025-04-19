package com.example.camera.activities;

import android.Manifest;
import android.os.Bundle;
import android.widget.ImageView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;

import com.example.camera.R;
import com.example.camera.utils.Camera;
import com.example.camera.utils.DataSender;
import com.example.camera.utils.DatabaseManager;
import com.example.camera.utils.ImageUtils;
import com.example.camera.utils.Permissions;
import com.example.camera.utils.Room;
import com.example.camera.utils.User;

public class CallActivity extends AppCompatActivity {

    private final static String[] PERMS = {android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET};
    private Camera _localCam;
    private DataSender _sender;
    private ImageView image;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_call);

        _localCam = new Camera(this, findViewById(R.id.previewView), this::onLocalCamFrameReceive);
        _sender = new DataSender("10.0.0.32", 12345, bytes -> {});

        image = findViewById(R.id.imageView);;

        if(!Permissions.hasPermissions(PERMS, this)){
            Permissions.requestPermissions(PERMS, 1000, this);
        }

        _localCam.startLocalCamera();




    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void onLocalCamFrameReceive(ImageProxy frame){
        byte[] data = ImageUtils.bitmapToByteArray(ImageUtils.imageToBitmap(frame.getImage()));
        _sender.updateData(data);

    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        _localCam.stopCamera();
        DatabaseManager.getInstance().removeUserFromRoom(User.getConnectedUser(), Room.getConnectedRoom());
    }
}