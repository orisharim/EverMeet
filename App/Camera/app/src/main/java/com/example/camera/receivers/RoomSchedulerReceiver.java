package com.example.camera.receivers;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.example.camera.R;
import com.example.camera.activities.CallActivity;
import com.example.camera.classes.Room;
import com.example.camera.classes.User;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.NetworkingUtils;

public class RoomSchedulerReceiver extends BroadcastReceiver {

    public static final String ACTION_ACCEPT = "com.example.camera.ACTION_ACCEPT_ROOM";
    public static final String ACTION_DECLINE = "com.example.camera.ACTION_DECLINE_ROOM";
    private static final String CHANNEL_ID = "room_channel";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        if (ACTION_ACCEPT.equals(action)) {
            handleAcceptAction(context, intent);
        } else if (ACTION_DECLINE.equals(action)) {
            deleteNotification(context);
        } else {
            showRoomNotification(context, intent);
        }
    }

    private void handleAcceptAction(Context context, Intent intent) {
        String roomName = intent.getStringExtra("room_name");
        String username = intent.getStringExtra("username");
        String password = intent.getStringExtra("password");

        DatabaseManager.getInstance().addRoom(roomName, username, new DatabaseManager.OnRoomAdded() {
            @Override
            public void onSuccess(Room room) {
                DatabaseManager.getInstance().doesUsernameExist(username, usernameResult -> {
                    if (usernameResult) {
                        DatabaseManager.getInstance().checkPassword(username, password, passwordResult -> {
                            if (passwordResult) {
                                addUser(context, username, password, () -> connectToRoom(context, room));
                                deleteNotification(context);
                            } else {
                                Toast.makeText(context, "Wrong password", Toast.LENGTH_SHORT).show();
                            }
                        });
                    } else {
                        Toast.makeText(context, "User \"" + username + "\" doesn't exist", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onFail() {
                Toast.makeText(context, "Failed to create room", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void connectToRoom(Context context, Room room) {
        Room.connectToRoom(room);

        DatabaseManager.getInstance().addUserToRoom(
                User.getConnectedUser(),
                NetworkingUtils.getIPv6Address(),
                room,
                success -> {
                    Intent callIntent = new Intent(context, CallActivity.class);
                    callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(callIntent);
                }
        );
    }

    private void addUser(Context context, String username, String password, Runnable onSuccess) {
        DatabaseManager.getInstance().addNewUser(username, password, new DatabaseManager.OnUserAdded() {
            @Override
            public void onSuccess(User user) {
                User.connectToUser(user);
                onSuccess.run();
            }

            @Override
            public void onFail() {
                Toast.makeText(context, "Login failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void deleteNotification(Context context){
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancelAll();
        }
    }

    private void showRoomNotification(Context context, Intent intent) {
        String roomName = intent.getStringExtra("room_name");
        String username = intent.getStringExtra("username");
        String password = intent.getStringExtra("password");

        PendingIntent acceptPendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                new Intent(context, RoomSchedulerReceiver.class)
                        .setAction(ACTION_ACCEPT)
                        .putExtra("room_name", roomName)
                        .putExtra("username", username)
                        .putExtra("password", password),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent declinePendingIntent = PendingIntent.getBroadcast(
                context,
                1,
                new Intent(context, RoomSchedulerReceiver.class)
                        .setAction(ACTION_DECLINE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;

        createNotificationChannel(manager);

        Notification notification = buildNotification(context, roomName, acceptPendingIntent, declinePendingIntent);
        manager.notify(roomName.hashCode(), notification);
    }

    private Notification buildNotification(Context context, String roomName, PendingIntent acceptIntent, PendingIntent declineIntent) {
        String title = "Scheduled Room";
        String content = "You scheduled the room: \"" + roomName + "\" ";

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setSmallIcon(R.drawable.cam)
                    .setColor(ContextCompat.getColor(context, R.color.colored_background))
                    .addAction(R.drawable.add, "Accept", acceptIntent)
                    .addAction(R.drawable.close, "Decline", declineIntent)
                    .setAutoCancel(true)
                    .build();
        } else {
            return new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setSmallIcon(R.drawable.cam)
                    .setColor(ContextCompat.getColor(context, R.color.colored_background))
                    .addAction(R.drawable.add, "Accept", acceptIntent)
                    .addAction(R.drawable.close, "Decline", declineIntent)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build();
        }
    }

    private void createNotificationChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Scheduled Rooms",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Notifications for scheduled meeting rooms");
            manager.createNotificationChannel(channel);
        }
    }
}