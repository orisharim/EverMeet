package com.example.camera.activities;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.camera.classes.User;
import com.example.camera.databinding.ActivityLoginBinding;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.NetworkingUtils;
import com.example.camera.utils.PermissionsUtils;
import com.example.camera.utils.StorageUtils;

public class LoginActivity extends AppCompatActivity {


    private static final String TAG = "LoginActivity";
    private static final String[] PERMISSIONS = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE
    };

    private static final int MAX_USERNAME_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 8;

    private static final String INVALID_USERNAME_CHARS = ".#$[]";

    private ActivityLoginBinding _views;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());

        setFullScreenMode();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        if (!PermissionsUtils.hasPermissions(PERMISSIONS, this)) {
            PermissionsUtils.requestPermissions(PERMISSIONS, 1000, this);
        }
        else {
            attemptAutoLogin();
        }

        setupFormSwitching();
        setupLoginButton();
        setupSignupButton();
    }

    private void setFullScreenMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        );
    }

    private void attemptAutoLogin() {
        String savedUsername = StorageUtils.getLoggedInUsernameFromStorage(this);
        if (!savedUsername.isEmpty()) {
            String savedPassword = StorageUtils.getLoggedInPasswordFromStorage(this);
            DatabaseManager.getInstance().doesUsernameExist(savedUsername, exists -> {
                if (exists) {
                    DatabaseManager.getInstance().checkPassword(savedUsername, savedPassword, correct -> {
                        if (correct) {
                            loadUser(savedUsername, savedPassword);
                        }
                    });
                }
            });
        }
    }

    private void setupFormSwitching() {
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
    }

    private void setupLoginButton() {
        _views.loginButton.setOnClickListener(v -> {

            String username = _views.loginUsername.getText().toString().trim();
            String password = _views.loginPassword.getText().toString();

            if(!checkInternetConnection()) return;
            if (!validateUsernameAndPassword(username, password)) return;
            if (!checkPermissionsGranted()) return;

            DatabaseManager.getInstance().doesUsernameExist(username, exists -> {
                if (exists) {
                    DatabaseManager.getInstance().checkPassword(username, password, correct -> {
                        if (correct) {
                            loadUser(username, password);
                        } else {
                            Toast.makeText(this, "Wrong password", Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    Toast.makeText(this, "User " + username + " doesn't exist", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void setupSignupButton() {
        _views.signupButton.setOnClickListener(v -> {
            String username = _views.signupUsername.getText().toString().trim();
            String password = _views.signupPassword.getText().toString();
            String confirmPassword = _views.signupConfirmPassword.getText().toString();

            if(!checkInternetConnection()) return;
            if (!validateUsernameAndPassword(username, password)) return;
            if (!password.equals(confirmPassword)) {
                Toast.makeText(this, "passwords do not match", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!checkPermissionsGranted()) return;


            DatabaseManager.getInstance().doesUsernameExist(username, exists -> {
                if (!exists) {
                    new Thread(() -> loadUser(username, password)).start();
                } else {
                    Toast.makeText(this, "A user named " + username + " already exists", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private boolean validateUsernameAndPassword(String username, String password) {
        if (username.isEmpty()) {
            Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (username.length() > MAX_USERNAME_LENGTH) {
            Toast.makeText(this, "Username must be at most " + MAX_USERNAME_LENGTH + " characters", Toast.LENGTH_SHORT).show();
            return false;
        }
        for (char c : INVALID_USERNAME_CHARS.toCharArray()) {
            if (username.indexOf(c) != -1) {
                Toast.makeText(this, "Username must not contain '" + c + "'", Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        if (password.isEmpty()) {
            Toast.makeText(this, "Please enter a password", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            Toast.makeText(this, "Password must be at most " + MAX_PASSWORD_LENGTH + " characters", Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    private boolean checkPermissionsGranted() {
        if (!PermissionsUtils.hasPermissions(PERMISSIONS, this)) {
            Toast.makeText(this, "Please allow the required permissions", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private boolean checkInternetConnection(){
        if(!NetworkingUtils.isConnectedToInternet(this)){
            Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;

    }

    private void loadUser(String username, String password) {
        Context thisActivity = this;
        DatabaseManager.getInstance().doesUsernameExist(username, exists -> {
            if (exists) {
                DatabaseManager.getInstance().getUser(username, new DatabaseManager.OnUserLoaded() {
                    @Override
                    public void onSuccess(User user) {
                       enter(user);
                    }

                    @Override
                    public void onFail() {
                        Toast.makeText(thisActivity, "Login failed", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                DatabaseManager.getInstance().addNewUser(username, password, new DatabaseManager.OnUserAdded() {
                    @Override
                    public void onSuccess(User user) {
                        enter(user);
                    }

                    @Override
                    public void onFail() {
                        Toast.makeText(thisActivity, "Login failed", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }

    private void enter(User user){
        Log.e(TAG, "logging in");
        User.connectToUser(user);
        StorageUtils.saveUserInStorage(this, user.getUsername(), user.getPassword());
        startActivity(new Intent(this, HomeActivity.class));
    }

}
