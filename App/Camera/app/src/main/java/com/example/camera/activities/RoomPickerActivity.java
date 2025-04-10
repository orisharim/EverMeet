package com.example.camera.activities;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.camera.R;
import com.example.camera.databinding.ActivityLoginBinding;
import com.example.camera.databinding.ActivityRoomPickerBinding;

public class RoomPickerActivity extends AppCompatActivity {

    private ActivityRoomPickerBinding _views;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityRoomPickerBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());

    }
}