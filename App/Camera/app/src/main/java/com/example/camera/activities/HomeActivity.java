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

public class HomeActivity extends AppCompatActivity {

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

        _views.btnRoomPickerFragment.setOnClickListener(v -> {setFragment(new RoomPickerFragment());});
        _views.btnFriendsFragment.setOnClickListener(v -> {setFragment(new FriendsFragment());});
        _views.logout.setOnClickListener(view -> {
            User.setConnectedUser(null);
            startActivity(new Intent(this, LoginActivity.class));
        });
    }

    private void setFragment(Fragment fragment){
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}