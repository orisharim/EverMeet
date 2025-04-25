package com.example.camera.activities;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.example.camera.R;
import com.example.camera.databinding.ActivityCallBinding;
import com.example.camera.databinding.ActivityHomeBinding;
import com.example.camera.fragments.FriendsFragment;

public class HomeActivity extends AppCompatActivity {

    private ActivityHomeBinding _views;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        _views = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());

        setFragment(new FriendsFragment());

        _views.btnFragment1.setOnClickListener(v -> {setFragment(new RoomPickerFragment());});
        _views.btnFragment2.setOnClickListener(v -> {setFragment(new FriendsFragment());});

    }

    private void setFragment(Fragment fragment){
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
    }
}