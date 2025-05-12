package com.example.camera.fragments;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
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
import com.example.camera.receivers.RoomSchedulerReceiver;
import com.example.camera.utils.NetworkingUtils;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class RoomPickerFragment extends Fragment {

    private static final String TAG = "RoomPickerFragment";
    private FragmentRoomPickerBinding _views;
    private RoomAdapter roomAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        _views = FragmentRoomPickerBinding.inflate(inflater, container, false);
        return _views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        roomAdapter = new RoomAdapter(this::joinRoom);
        _views.roomsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        _views.roomsRecyclerView.setAdapter(roomAdapter);

        _views.createRoomButton.setOnClickListener(this::showCreateRoomDialog);

        DatabaseManager.getInstance().setOnRoomsDataChange(roomAdapter::setRooms);
    }

    @SuppressLint("ScheduleExactAlarm")
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
        ImageButton cancelScheduleButton = dialogView.findViewById(R.id.dialogCancelScheduledTimeButton);
        TextView scheduledTimeText = dialogView.findViewById(R.id.dialogShowScheduledMeetingTimeText);

        AlertDialog dialog = builder.create();

        final Calendar[] scheduledTime = new Calendar[1];  // To store picked date & time

        createRoomButton.setOnClickListener(v -> {
            String roomName = roomNameInput.getText().toString().trim();
            if (!roomName.isEmpty()) {
                if (scheduledTime[0] != null) {
                    // Room is scheduled for later – optional: save scheduled time in DB
                    Toast.makeText(requireContext(),
                            "Room scheduled for: " +
                                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                            .format(scheduledTime[0].getTime()),
                            Toast.LENGTH_LONG).show();

                    String roomNameFinal = roomName;
                    long triggerTime = scheduledTime[0].getTimeInMillis();

                    Intent alarmIntent = new Intent(requireContext(), RoomSchedulerReceiver.class);
                    alarmIntent.putExtra("room_name", roomNameFinal);
                    alarmIntent.putExtra("username", User.getConnectedUser().getUsername());

                    PendingIntent pendingIntent = PendingIntent.getBroadcast(
                            requireContext(),
                            roomNameFinal.hashCode(),  // Unique requestCode
                            alarmIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                    );

                    AlarmManager alarmManager = (AlarmManager) requireContext().getSystemService(Context.ALARM_SERVICE);
                    if (alarmManager != null) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
                        Toast.makeText(requireContext(), "Room scheduled at: " +
                                        new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(triggerTime),
                                Toast.LENGTH_LONG).show();
                    }



                } else {
                    // No scheduled time – create immediately
                    createRoom(roomName);
                }
                dialog.dismiss();
            } else {
                Toast.makeText(requireContext(), "Please enter a room name.", Toast.LENGTH_SHORT).show();
            }
        });

        cancelRoomButton.setOnClickListener(v -> dialog.dismiss());

        setDateTimeButton.setOnClickListener(v ->
                pickDateTime(scheduledTime, scheduledTimeText, cancelScheduleButton));

        cancelScheduleButton.setOnClickListener(v -> {
            scheduledTime[0] = null;
            scheduledTimeText.setText("No scheduled time");
            cancelScheduleButton.setVisibility(View.GONE);
        });

        cancelScheduleButton.setVisibility(View.GONE);
        scheduledTimeText.setText("No scheduled time");

        dialog.show();
    }


    private void pickDateTime(Calendar[] scheduledTime,
                              TextView scheduledTimeText,
                              ImageButton cancelScheduleButton) {
        Calendar calendar = Calendar.getInstance();

        new DatePickerDialog(requireContext(),
                (view, year, month, dayOfMonth) -> new TimePickerDialog(requireContext(),
                        (timeView, hourOfDay, minute) -> {
                            Calendar selectedDateTime = Calendar.getInstance();
                            selectedDateTime.set(year, month, dayOfMonth, hourOfDay, minute);
                            scheduledTime[0] = selectedDateTime;

                            String formatted = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                    .format(selectedDateTime.getTime());
                            scheduledTimeText.setText("Scheduled for: " + formatted);
                            cancelScheduleButton.setVisibility(View.VISIBLE);
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
        _views = null; // avoid memory leaks
    }
}
