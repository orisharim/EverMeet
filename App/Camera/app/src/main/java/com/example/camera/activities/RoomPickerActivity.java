package com.example.camera.activities;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.camera.R;
import com.example.camera.adapters.RoomAdapter;
import com.example.camera.databinding.ActivityRoomPickerBinding;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.Room;
import com.example.camera.utils.User;

public class RoomPickerActivity extends AppCompatActivity {

    private ActivityRoomPickerBinding _views;
    private RoomAdapter _roomAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityRoomPickerBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());

        _roomAdapter = new RoomAdapter(User.getConnectedUser(), this::joinRoom);
        _views.roomsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        _views.roomsRecyclerView.setAdapter(_roomAdapter);

        _views.createRoomButton.setOnClickListener(this::showCreateRoomDialog);

        DatabaseManager.getInstance().setOnRoomsDataChangeReceive(_roomAdapter::setRooms);
    }

    private void showCreateRoomDialog(View view) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_room_creator, null);
        EditText roomNameInput = dialogView.findViewById(R.id.roomNameInput);

        new AlertDialog.Builder(this)
                .setTitle("Create Room")
                .setView(dialogView)
                .setPositiveButton("Create", (dialog, which) -> {
                    String roomName = roomNameInput.getText().toString().trim();
                    if (!roomName.isEmpty()) {
                        createRoom(roomName);
                    } else {
                        Toast.makeText(this, "Room name cannot be empty", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createRoom(String roomName){
        DatabaseManager.getInstance().addRoom(roomName, (success) -> { });
        moveToCallActivity();
    }

    private void joinRoom(Room room){
        DatabaseManager.getInstance().addUserToRoom(User.getConnectedUser(), room);
        Room.setConnectedRoom(room);
        moveToCallActivity();
    }

    private void moveToCallActivity(){
        Intent callActivity = new Intent(this, CallActivity.class);
        startActivity(callActivity);
    }









}