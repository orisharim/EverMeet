package com.example.camera.receivers;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.example.camera.R;
import com.example.camera.activities.CallActivity;
import com.example.camera.activities.HomeActivity;
import com.example.camera.classes.Room;
import com.example.camera.classes.User;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.NetworkingUtils;

public class RoomStartupService extends Service {

    private static final String CHANNEL_ID = "room_startup_channel";

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String roomName = intent.getStringExtra("room_name");
        String username = intent.getStringExtra("username");
        String password = intent.getStringExtra("password");

        DatabaseManager.getInstance().addRoom(roomName, username, new DatabaseManager.OnRoomAdded() {
            @Override
            public void onSuccess(Room room) {

                DatabaseManager.getInstance().doesUsernameExist(username, usernameResult -> {
                    if(usernameResult) {
                        DatabaseManager.getInstance().checkPassword(username, password, passwordResult -> {
                            if(passwordResult){
                                addUser(username, password);
                            } else {
//                                Toast.makeText(intent, "Wrong password", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } else{
//                        Toast.makeText(intent, "User " + username + " doesn't exist", Toast.LENGTH_SHORT).show();
                    }
                });


                Room.connectToRoom(room);

                DatabaseManager.getInstance().addUserToRoom(
                        User.getConnectedUser(),
                        NetworkingUtils.getIPv6Address(),
                        Room.getConnectedRoom(),
                        success -> {

                            Intent callIntent = new Intent(RoomStartupService.this, CallActivity.class);
                            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(callIntent);

                            stopSelf();
                        }
                );
            }

            @Override
            public void onFail() {
                stopSelf();
            }
        });

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void addUser(String username, String password){
        Context thisActivity = this;
        DatabaseManager.getInstance().addUser(username, password, new DatabaseManager.OnUserAdded() {
            @Override
            public void onSuccess(User user) {
                User.setConnectedUser(user);
            }

            @Override
            public void onFail() {
                Toast.makeText(thisActivity, "Login failed", Toast.LENGTH_SHORT).show();
            }
        });
    }


}
