package com.example.camera.activities;


import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;

import com.example.camera.R;
import com.example.camera.classes.User;
import com.example.camera.databinding.ActivityLoginBinding;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.NetworkingUtils;
import com.example.camera.utils.PermissionsUtils;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

public class LoginActivity extends AppCompatActivity {
    private static final String TAG = "LoginActivity";
    private static final String[] PERMS = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET};
    private static final int MAX_USERNAME_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 8;

    private ActivityLoginBinding _views;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());

        // hide navigation bar
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        );

        if(!PermissionsUtils.hasPermissions(PERMS, this)){
            PermissionsUtils.requestPermissions(PERMS, 1000, this);
        }

        // lock orientation
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, String.valueOf(10))
                .setSmallIcon(R.drawable.ic_launcher_background)
                .setContentTitle("0mer")
                .setContentText("omeromer")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        

        _views.switchToSignup.setOnClickListener(v -> {
            _views.loginCard.setVisibility(View.GONE);
            _views.signupCard.setVisibility(View.VISIBLE);
            _views.signupAppName.setVisibility(View.VISIBLE);
        });

        _views.switchToLogin.setOnClickListener(v -> {
            _views.signupCard.setVisibility(View.GONE);
            _views.loginCard.setVisibility(View.VISIBLE);
            _views.loginAppName.setVisibility(View.VISIBLE);
        });

        _views.loginButton.setOnClickListener(v -> {
            String username = _views.loginUsername.getText().toString().trim();
            String password = _views.loginPassword.getText().toString();

            if (username.isEmpty()) {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show();
                return;
            }

            if(username.length() > MAX_USERNAME_LENGTH){
                Toast.makeText(this, "Please enter a username that shorter than " + (MAX_USERNAME_LENGTH + 1), Toast.LENGTH_SHORT).show();
                return;
            }

            if(username.indexOf('.') != -1 || username.indexOf('#') != -1 || username.indexOf('$') != -1 || username.indexOf('[') != -1 || username.indexOf(']') != -1 ){
                Toast.makeText(this, "Please enter a username that does not contain '.', '#', '$', '[', or ']' " + (MAX_USERNAME_LENGTH + 1), Toast.LENGTH_SHORT).show();
                return;
            }

            if (password.isEmpty()) {
                Toast.makeText(this, "Please enter a password", Toast.LENGTH_SHORT).show();
                return;
            }

            if(password.length() > MAX_USERNAME_LENGTH){
                Toast.makeText(this, "Please enter a username that shorter than " + (MAX_USERNAME_LENGTH + 1), Toast.LENGTH_SHORT).show();
                return;
            }

            if(!PermissionsUtils.hasPermissions(PERMS, this)){
                Toast.makeText(this, "Allow the app the permissions it needs", Toast.LENGTH_SHORT).show();
                return;
            }

            DatabaseManager.getInstance().doesUsernameExist(username, usernameResult -> {
                if(usernameResult) {
                    DatabaseManager.getInstance().checkPassword(username, password, passwordResult -> {
                        if(passwordResult){
                            addUser(username, password);
                        } else {
                            Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else{
                    Toast.makeText(this, "User " + username + " doesn't exist", Toast.LENGTH_SHORT).show();
                }
            });
        });

        _views.signupButton.setOnClickListener(v -> {
            String username = _views.signupUsername.getText().toString().trim();
            String password = _views.signupPassword.getText().toString();

            if(username.isEmpty()) {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show();
                return;
            }

            if(username.length() > MAX_USERNAME_LENGTH){
                Toast.makeText(this, "Please enter a username that shorter than " + (MAX_USERNAME_LENGTH + 1), Toast.LENGTH_SHORT).show();
                return;
            }

            if(username.indexOf('.') != -1 || username.indexOf('#') != -1 || username.indexOf('$') != -1 || username.indexOf('[') != -1 || username.indexOf(']') != -1 ){
                Toast.makeText(this, "Please enter a username that does not contain '.', '#', '$', '[', or ']' " + (MAX_USERNAME_LENGTH + 1), Toast.LENGTH_SHORT).show();
                return;
            }

            if(password.isEmpty()) {
                Toast.makeText(this, "Please enter a password", Toast.LENGTH_SHORT).show();
                return;
            }

            if(password.length() > MAX_USERNAME_LENGTH){
                Toast.makeText(this, "Please enter a username that shorter than " + (MAX_USERNAME_LENGTH + 1), Toast.LENGTH_SHORT).show();
                return;
            }

            String confirmPassword = _views.signupConfirmPassword.getText().toString();
            if(!password.equals(confirmPassword)){
                Toast.makeText(this, "Passwords are not equal", Toast.LENGTH_SHORT).show();
                return;
            }

            if(!PermissionsUtils.hasPermissions(PERMS, this)){
                Toast.makeText(this, "Allow the app the permissions it needs", Toast.LENGTH_SHORT).show();
                return;
            }

            DatabaseManager.getInstance().doesUsernameExist(username, usernameResult -> {
                if(!usernameResult) {
                    // Android studio forbids getting the host ip on the main thread
                    new Thread(() -> addUser(username, password)).start();
                } else{
                    Toast.makeText(this, "A user named " + username + " already exists", Toast.LENGTH_SHORT).show();
                }
            });

        });

    }

    private void addUser(String username, String password){
        Context thisActivity = this;
        DatabaseManager.getInstance().addUser(username, password, new DatabaseManager.OnUserAdded() {
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