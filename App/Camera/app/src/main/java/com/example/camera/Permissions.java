package com.example.camera;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class Permissions {

    public static void requestPermissions(String[] permissions, int requestCode, Activity activity) {
        if (!hasPermissions(permissions, activity)) {
            ActivityCompat.requestPermissions(activity, permissions, 100);
        } else {
            Toast.makeText(activity, "Permissions Already Granted", Toast.LENGTH_SHORT).show();
        }
    }

    public static boolean hasPermissions(String[] permissions, Activity activity) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

}
