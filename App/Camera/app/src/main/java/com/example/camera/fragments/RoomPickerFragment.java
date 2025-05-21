package com.example.camera.fragments;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

public class RoomPickerFragment extends Fragment {

    private static final String TAG = "RoomPickerFragment";
    private static final SimpleDateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    private FragmentRoomPickerBinding _views;
    private RoomAdapter _roomAdapter;

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

        _roomAdapter = new RoomAdapter(this::joinRoom);
        _views.roomsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        _views.roomsRecyclerView.setAdapter(_roomAdapter);

        _views.createRoomButton.setOnClickListener(this::showCreateRoomDialog);
        _views.helloUsernameText.setText("Hello, " + User.getConnectedUser().getUsername());


        DatabaseManager.getInstance().getRoomsData(this::updateRoomsUI);
        DatabaseManager.getInstance().setOnRoomsDataReceived(this::updateRoomsUI);
    }

    @SuppressLint("ScheduleExactAlarm")
    private void showCreateRoomDialog(View view) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Create room");

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_room_creator, null);
        builder.setView(dialogView);

        AlertDialog dialog = builder.create();

        EditText roomNameInput = dialogView.findViewById(R.id.dialogRoomName);
        Button createButton = dialogView.findViewById(R.id.dialogCreateRoomButton);
        Button cancelButton = dialogView.findViewById(R.id.dialogCancelCreateRoomButton);
        ImageButton setDateTimeButton = dialogView.findViewById(R.id.dialogSetTimeButton);
        ImageButton cancelScheduleButton = dialogView.findViewById(R.id.dialogCancelScheduledTimeButton);
        TextView scheduledTimeText = dialogView.findViewById(R.id.dialogShowScheduledMeetingTimeText);

        final Calendar[] scheduledTime = new Calendar[1];

        createButton.setOnClickListener(v -> {
            String roomName = roomNameInput.getText().toString().trim();
            if (roomName.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter a room name.", Toast.LENGTH_SHORT).show();
                return;
            }

            if (scheduledTime[0] != null) {
                scheduleRoom(roomName, scheduledTime[0]);
            } else {
                createRoom(roomName);
            }

            dialog.dismiss();
        });

        cancelButton.setOnClickListener(v -> dialog.dismiss());

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

    private void scheduleRoom(String roomName, Calendar scheduledTime) {
        long triggerTime = scheduledTime.getTimeInMillis();

        Intent intent = new Intent(requireContext(), RoomSchedulerReceiver.class);
        intent.putExtra("room_name", roomName);
        intent.putExtra("username", User.getConnectedUser().getUsername());
        intent.putExtra("password", User.getConnectedUser().getPassword());

        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                requireContext(),
                roomName.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        AlarmManager alarmManager = (AlarmManager) requireContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent);
            Toast.makeText(requireContext(),
                    "Room scheduled at: " + DATE_FORMAT.format(triggerTime),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void pickDateTime(Calendar[] scheduledTime,
                              TextView timeText,
                              ImageButton cancelButton) {
        Calendar now = Calendar.getInstance();

        DatePickerDialog datePickerDialog = new DatePickerDialog(requireContext(),
                R.style.TimePickerDialogTheme,
                (view, year, month, dayOfMonth) -> {

                    TimePickerDialog timePickerDialog = new TimePickerDialog(requireContext(),
                            R.style.TimePickerDialogTheme,
                            (timeView, hourOfDay, minute) -> {

                                Calendar selected = Calendar.getInstance();
                                selected.set(year, month, dayOfMonth, hourOfDay, minute, 0);
                                selected.set(Calendar.MILLISECOND, 0);


                                scheduledTime[0] = selected;
                                timeText.setText("Scheduled for: " + DATE_FORMAT.format(selected.getTime()));
                                cancelButton.setVisibility(View.VISIBLE);
                                
                            },
                            now.get(Calendar.HOUR_OF_DAY),
                            now.get(Calendar.MINUTE),
                            true);


                    timePickerDialog.show();
                },
                now.get(Calendar.YEAR),
                now.get(Calendar.MONTH),
                now.get(Calendar.DAY_OF_MONTH));

        datePickerDialog.getDatePicker().setMinDate(System.currentTimeMillis());
        datePickerDialog.show();
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
                                room,
                                success -> startActivity(new Intent(getActivity(), CallActivity.class))
                        );
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
                    startActivity(new Intent(getActivity(), CallActivity.class));
                });
    }

    private void updateRoomsUI(List<Room> rooms){
        if(User.getConnectedUser().getFriends() == null){
            _roomAdapter.setRooms(new ArrayList<Room>());
            return;
        }

        List<Room> filteredRooms = new ArrayList<>();
        rooms.forEach(
                room -> {
                    if(User.getConnectedUser().getFriends().contains(room.getCreator())){
                        filteredRooms.add(room);
                    }
                }
        );

        _roomAdapter.setRooms(filteredRooms);
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        _views = null;
    }
}
