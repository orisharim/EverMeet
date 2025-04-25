package com.example.camera.activities;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageProxy;
import androidx.constraintlayout.helper.widget.Flow;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.example.camera.R;
import com.example.camera.databinding.ActivityCallBinding;
import com.example.camera.managers.PeerConnectionManager;
import com.example.camera.classes.Camera;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.ImageConversionUtils;
import com.example.camera.classes.Room;
import com.example.camera.classes.User;

import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public class CallActivity extends AppCompatActivity {

    private final static String[] PERMS = {android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET};
    private ActivityCallBinding _views;
    private Camera _localCam;

    private boolean _isCamClosed;
    private boolean _isMuted;

    private HashMap<String, ImageView> participantViews = new HashMap<>();
    private int viewIdCounter = 1000; // Ensure unique view IDs


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

        _localCam = new Camera(this, findViewById(R.id.previewView), this::onLocalCamFrameReceive);
        _localCam.startLocalCamera();

        _isMuted = true;
        _isCamClosed = true;

        DatabaseManager.getInstance().setOnRoomDataChange(Room.getConnectedRoom().getId(), r -> {
            List<String> otherParticipants = Room.getConnectedRoom().getParticipants().stream()
                    .map(User::getUsername)
                    .filter(name -> !name.equals(User.getConnectedUser().getUsername()))
                    .collect(Collectors.toList());

            for (String participant: otherParticipants) {
                addParticipantView(participant);
            }
        });

        _views.micButton.setOnClickListener(view -> {
            _isMuted = !_isMuted;
            if(_isMuted)
                _views.micButton.setImageResource(R.drawable.muted_mic);
            else
                _views.micButton.setImageResource(R.drawable.mic);
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
            updateParticipantFrame(data.getUsername(), ImageConversionUtils.byteArrayToBitmap(data.getPayload()));
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
        startActivity(new Intent(this, HomeActivity.class));
    }

    private void updateParticipantFrame(String username, Bitmap frame) {
        runOnUiThread(() -> {
            if (!participantViews.containsKey(username)) {
                addParticipantView(username);
            }

            ImageView view = participantViews.get(username);
            view.setImageBitmap(frame);
        });
    }

    private void addParticipantView(String username) {
        ConstraintLayout container = findViewById(R.id.call);
        ImageView imageView = new ImageView(this);
        int viewId = viewIdCounter++;
        imageView.setId(viewId);
        imageView.setLayoutParams(new ViewGroup.LayoutParams(300, 300));
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setContentDescription(username);

        container.addView(imageView);
        participantViews.put(username, imageView);

        updateFlowIds();
    }

    private void removeParticipant(String username) {
        runOnUiThread(() -> {
            ConstraintLayout container = findViewById(R.id.call);
            ImageView view = participantViews.remove(username);
            if (view != null) {
                container.removeView(view);
                updateFlowIds();
            }
        });
    }

    private void updateFlowIds() {
        Flow flow = findViewById(R.id.participantFlow);
        int[] ids = new int[participantViews.size()];
        int i = 0;
        for (ImageView view : participantViews.values()) {
            ids[i++] = view.getId();
        }
        flow.setReferencedIds(ids);
    }

}