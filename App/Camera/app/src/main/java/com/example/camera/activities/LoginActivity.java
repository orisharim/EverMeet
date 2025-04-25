package com.example.camera.activities;


import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.camera.classes.User;
import com.example.camera.databinding.ActivityLoginBinding;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.PermissionsUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class LoginActivity extends AppCompatActivity {

    private final static String[] PERMS = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET};
    private ActivityLoginBinding _views;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());

        if(!PermissionsUtils.hasPermissions(PERMS, this)){
            PermissionsUtils.requestPermissions(PERMS, 1000, this);
        }

        // lock orientation
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        _views.loginButton.setOnClickListener(v -> {
            String username = _views.usernameInput.getText().toString().trim();

            if (username.isEmpty()) {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show();
                return;
            }

            if(!PermissionsUtils.hasPermissions(PERMS, this)){
                Toast.makeText(this, "Allow the app the permissions it needs", Toast.LENGTH_SHORT).show();
                return;
            }

            // Android studio forbids getting the host ip on the main thread
            new Thread(() -> login(username)).start();

        });

    }

    private void login(String username){
        Context thisActivity = this;
        DatabaseManager.getInstance().addUser(username, new DatabaseManager.OnUserAdded() {
            @Override
            public void onSuccess(User user) {
                User.setConnectedUser(user);
                startActivity(new Intent(thisActivity, HomeActivity.class));
            }

            @Override
            public void onFail() {
                Toast.makeText(thisActivity, "Login failed", Toast.LENGTH_SHORT).show();
            }
        });
    }



    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

}