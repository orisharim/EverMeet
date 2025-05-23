package com.example.camera.receivers;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.widget.Toast;

import com.example.camera.R;
import com.example.camera.activities.HomeActivity;
import com.example.camera.utils.NetworkingUtils;



public class InternetConnectionChangeReceiver extends BroadcastReceiver {

    private static Dialog internetDialog;

    @Override
    public void onReceive(Context context, Intent intent) {

        boolean isConnected = NetworkingUtils.isConnectedToInternet(context);

        if (context instanceof Activity) {
            Activity activity = (Activity) context;

            if (!isConnected) {
                // Show the dialog if it's not already showing
                if (internetDialog == null || !internetDialog.isShowing()) {
                    internetDialog = new Dialog(activity);
                    internetDialog.setContentView(R.layout.dialog_no_internet_connection);

                    if (internetDialog.getWindow() != null) {
                        internetDialog.getWindow().setBackgroundDrawable(
                                new ColorDrawable(Color.TRANSPARENT)
                        );
                    }

                    internetDialog.setCancelable(false);
                    internetDialog.show();
                }

                Toast.makeText(context, "Disconnected from the internet", Toast.LENGTH_SHORT).show();

            } else {
                // Dismiss the dialog if it exists and is showing
                if (internetDialog != null && internetDialog.isShowing()) {
                    internetDialog.dismiss();
                    internetDialog = null;
                }

            }
        }
    }
}

