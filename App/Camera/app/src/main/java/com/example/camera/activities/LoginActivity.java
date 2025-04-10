package com.example.camera.activities;


import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.camera.databinding.ActivityLoginBinding;
import com.example.camera.utils.DatabaseManager;
import com.example.camera.utils.Permissions;
import com.example.camera.utils.User;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class LoginActivity extends AppCompatActivity {

    private final static String[] PERMS = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET};
    private ActivityLoginBinding _views;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());



        if(!Permissions.hasPermissions(PERMS, this)){
            Permissions.requestPermissions(PERMS, 1000, this);
        }

        _views.loginButton.setOnClickListener(v -> {
            String username = _views.usernameInput.getText().toString().trim();

            if (username.isEmpty()) {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show();
                return;
            }

            if(!Permissions.hasPermissions(PERMS, this)){
                Toast.makeText(this, "Allow the app the permissions it needs", Toast.LENGTH_SHORT).show();
                return;
            }

            DatabaseManager.getInstance().addUser(new User(username), (success) ->{
                if(success){
                    Intent roomPickerActivity = new Intent(this, RoomPickerActivity.class);
                    roomPickerActivity.putExtra("USERNAME", username);
                    startActivity(roomPickerActivity);
                } else {
                    Toast.makeText(this, "Login failed", Toast.LENGTH_SHORT).show();
                }
            });
        });




    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

}