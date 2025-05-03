package com.example.camera.fragments;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.camera.R;
import com.example.camera.activities.CallActivity;
import com.example.camera.adapters.RoomAdapter;
import com.example.camera.databinding.FragmentRoomPickerBinding;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.classes.Room;
import com.example.camera.classes.User;
import com.example.camera.utils.NetworkingUtils;

public class RoomPickerFragment extends Fragment {

    private FragmentRoomPickerBinding _views;
    private RoomAdapter _roomAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        _views = FragmentRoomPickerBinding.inflate(inflater, container, false);
        return _views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        _roomAdapter = new RoomAdapter(this::joinRoom);
        _views.roomsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        _views.roomsRecyclerView.setAdapter(_roomAdapter);

        _views.createRoomButton.setOnClickListener(this::showCreateRoomDialog);

        DatabaseManager.getInstance().setOnRoomsDataChange(_roomAdapter::setRooms);
    }

    private void showCreateRoomDialog(View view) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Create room");

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_room_creator, null);
        builder.setView(dialogView);

        EditText roomNameInput = dialogView.findViewById(R.id.dialogRoomName);
        Button createRoom = dialogView.findViewById(R.id.dialogCreateRoomButton);
        Button cancelRoom = dialogView.findViewById(R.id.dialogCancelCreateRoomButton);

        AlertDialog dialog = builder.create();

        createRoom.setOnClickListener(v -> {
            String roomName = roomNameInput.getText().toString().trim();
            if (!roomName.isEmpty()) {
                createRoom(roomName);
                dialog.dismiss();
            } else {
                Toast.makeText(requireContext(), "Please enter a room name.", Toast.LENGTH_SHORT).show();
            }
        });

        cancelRoom.setOnClickListener(v -> dialog.dismiss());

        dialog.show();


    }



    private void createRoom(String roomName) {
        DatabaseManager.getInstance().addRoom(roomName, User.getConnectedUser().getUsername(), new DatabaseManager.OnRoomAdded(){
            @Override
            public void onSuccess(Room room) {
                Room.connectToRoom(room);
                DatabaseManager.getInstance().addUserToRoom(User.getConnectedUser(), NetworkingUtils.getIPv6Address(), Room.getConnectedRoom(), aBoolean -> {});
                moveToCallActivity();
            }

            @Override
            public void onFail() {
                Toast.makeText(requireContext(), "Failed to create a room", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void joinRoom(Room room) {
        DatabaseManager.getInstance().addUserToRoom(User.getConnectedUser(), NetworkingUtils.getIPv6Address(), room, success -> {
            Room.connectToRoom(room);
            moveToCallActivity();
        });
    }

    private void moveToCallActivity() {
        Intent callActivity = new Intent(getActivity(), CallActivity.class);
        startActivity(callActivity);

    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        _views = null; // prevent memory leaks by nulling the views(recommended by someone from stack overflow)
    }
}