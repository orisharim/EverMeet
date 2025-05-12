package com.example.camera.receivers;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;

import com.example.camera.R;
import com.example.camera.activities.CallActivity;
import com.example.camera.classes.Room;
import com.example.camera.classes.User;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.NetworkingUtils;

public class RoomSchedulerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String roomName = intent.getStringExtra("room_name");
        String username = intent.getStringExtra("username");



    }






}
