package com.example.camera.fragments;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
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

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class RoomPickerFragment extends Fragment {

    private static final String TAG = "RoomPickerFragment";
    private FragmentRoomPickerBinding binding;
    private RoomAdapter roomAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentRoomPickerBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        roomAdapter = new RoomAdapter(this::joinRoom);
        binding.roomsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.roomsRecyclerView.setAdapter(roomAdapter);

        binding.createRoomButton.setOnClickListener(this::showCreateRoomDialog);

        DatabaseManager.getInstance().setOnRoomsDataChange(roomAdapter::setRooms);
    }

    private void showCreateRoomDialog(View view) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Create room");

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_room_creator, null);
        builder.setView(dialogView);

        EditText roomNameInput = dialogView.findViewById(R.id.dialogRoomName);
        Button createRoomButton = dialogView.findViewById(R.id.dialogCreateRoomButton);
        Button cancelRoomButton = dialogView.findViewById(R.id.dialogCancelCreateRoomButton);
        ImageButton setDateTimeButton = dialogView.findViewById(R.id.dialogSetTimeButton);

        AlertDialog dialog = builder.create();

        createRoomButton.setOnClickListener(v -> {
            String roomName = roomNameInput.getText().toString().trim();
            if (!roomName.isEmpty()) {
                createRoom(roomName);
                dialog.dismiss();
            } else {
                Toast.makeText(requireContext(), "Please enter a room name.", Toast.LENGTH_SHORT).show();
            }
        });

        cancelRoomButton.setOnClickListener(v -> dialog.dismiss());

        setDateTimeButton.setOnClickListener(v -> pickDateTime(setDateTimeButton));

        dialog.show();
    }

    private void pickDateTime(ImageButton buttonToUpdate) {
        Calendar calendar = Calendar.getInstance();

        new DatePickerDialog(requireContext(),
                (view, year, month, dayOfMonth) -> new TimePickerDialog(requireContext(),
                        (timeView, hourOfDay, minute) -> {
                            Calendar selectedDateTime = Calendar.getInstance();
                            selectedDateTime.set(year, month, dayOfMonth, hourOfDay, minute);
                            String formatted = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                    .format(selectedDateTime.getTime());

                            // TODO: store selectedDateTime if needed
                        },
                        calendar.get(Calendar.HOUR_OF_DAY),
                        calendar.get(Calendar.MINUTE),
                        true
                ).show(),
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void createRoom(String roomName) {
        DatabaseManager.getInstance().addRoom(roomName, User.getConnectedUser().getUsername(),
                new DatabaseManager.OnRoomAdded() {
                    @Override
                    public void onSuccess(Room room) {
                        Room.connectToRoom(room);
                        DatabaseManager.getInstance().addUserToRoom(
                                User.getConnectedUser(),
                                NetworkingUtils.getIPv6Address(),
                                Room.getConnectedRoom(),
                                success -> {}
                        );
                        moveToCallActivity();
                    }

                    @Override
                    public void onFail() {
                        Toast.makeText(requireContext(), "Failed to create a room", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void joinRoom(Room room) {
        DatabaseManager.getInstance().addUserToRoom(
                User.getConnectedUser(),
                NetworkingUtils.getIPv6Address(),
                room,
                success -> {
                    Room.connectToRoom(room);
                    moveToCallActivity();
                });
    }

    private void moveToCallActivity() {
        Intent intent = new Intent(getActivity(), CallActivity.class);
        startActivity(intent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null; // avoid memory leaks
    }
}
