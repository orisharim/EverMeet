package com.example.camera.activities;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.example.camera.R;
import com.example.camera.classes.User;
import com.example.camera.databinding.ActivityHomeBinding;
import com.example.camera.fragments.FriendsFragment;
import com.example.camera.fragments.RoomPickerFragment;
import com.example.camera.receivers.InternetConnectionChangeReceiver;
import com.example.camera.utils.StorageUtils;

public class HomeActivity extends AppCompatActivity {

    private ActivityHomeBinding _views;
    private InternetConnectionChangeReceiver _internetConnectionChangeReceiver;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());

        setFullScreenMode();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT); // Lock orientation

        // Load default fragment
        setFragment(new RoomPickerFragment());

        _views.roomPickerFragmentButton.setOnClickListener(v ->
                setFragment(new RoomPickerFragment())
        );

        _views.friendsFragmentButton.setOnClickListener(v ->
                setFragment(new FriendsFragment())
        );

        _views.logoutButton.setOnClickListener(v -> {
            StorageUtils.removeUserFromStorage(this);
            User.disconnectFromConncetedUser();
            Intent loginIntent = new Intent(this, LoginActivity.class);
            startActivity(loginIntent);
        });

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

    private void setFragment(Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
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
