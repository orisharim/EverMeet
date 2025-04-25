package com.example.camera.activities;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.camera.R;
import com.example.camera.adapters.RoomAdapter;
import com.example.camera.databinding.FragmentRoomPickerBinding;
import com.example.camera.fragments.FriendsFragment;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.classes.Room;
import com.example.camera.classes.User;

public class RoomPickerFragment extends Fragment {

    private FragmentRoomPickerBinding _views;
    private RoomAdapter _roomAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        _views = FragmentRoomPickerBinding.inflate(inflater, container, false);
        return _views.getRoot(); // Inflate the fragment layout
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Lock orientation (this can still be done inside a fragment as well)
        if (getActivity() != null) {
            getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }

        _roomAdapter = new RoomAdapter(this::joinRoom);
        _views.roomsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        _views.roomsRecyclerView.setAdapter(_roomAdapter);

        _views.createRoomButton.setOnClickListener(this::showCreateRoomDialog);

        DatabaseManager.getInstance().setOnRoomsDataChange(_roomAdapter::setRooms);
    }

    private void showCreateRoomDialog(View view) {
        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_room_creator, null);
        EditText roomNameInput = dialogView.findViewById(R.id.roomNameInput);

        new AlertDialog.Builder(requireContext())
                .setTitle("Create Room")
                .setView(dialogView)
                .setPositiveButton("Create", (dialog, which) -> {
                    String roomName = roomNameInput.getText().toString().trim();
                    if (!roomName.isEmpty()) {
                        createRoom(roomName);
                    } else {
                        Toast.makeText(requireContext(), "Room name cannot be empty", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createRoom(String roomName) {
        Room room = DatabaseManager.getInstance().createNewRoom(roomName, success -> {
            if (success) {
                moveToCallActivity();
            } else {
                Toast.makeText(requireContext(), "Failed to create a room", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void joinRoom(Room room) {
        DatabaseManager.getInstance().addUserToRoom(User.getConnectedUser(), room, success -> {
            Room.connectToRoom(room);
            moveToCallActivity();
        });
    }

    private void moveToCallActivity() {
        if (getActivity() != null) {
            Intent callActivity = new Intent(getActivity(), CallActivity.class);
            startActivity(callActivity);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        _views = null; // Prevent memory leaks by nulling the views
    }
}