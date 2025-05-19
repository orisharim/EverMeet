package com.example.camera.activities;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.example.camera.R;
import com.example.camera.classes.User;
import com.example.camera.databinding.ActivityHomeBinding;
import com.example.camera.fragments.FriendsFragment;
import com.example.camera.fragments.RoomPickerFragment;
import com.example.camera.utils.StorageUtils;

public class HomeActivity extends AppCompatActivity {
    private static final String TAG = "HomeActivity";
    private ActivityHomeBinding _views;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());

        // hide navigation bar
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        );

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); //lock orientation

        setFragment(new RoomPickerFragment());

        _views.roomPickerFragmentButton.setOnClickListener(v -> {setFragment(new RoomPickerFragment());});
        _views.friendsFragmentButton.setOnClickListener(v -> {setFragment(new FriendsFragment());});
        _views.logoutButton.setOnClickListener(view -> {
            User.setConnectedUser(null);
            Intent inte = new Intent(this, LoginActivity.class);
            StorageUtils.removeUserFromStorage(this);
            startActivity(inte);
        });
    }

    private void setFragment(Fragment fragment){
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}