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
import com.example.camera.utils.NetworkingUtils;



public class InternetConnectionChangeReceiver extends BroadcastReceiver {

    private static Dialog _internetDialog;

    @Override
    public void onReceive(Context context, Intent intent) {

        boolean isConnected = NetworkingUtils.isConnectedToInternet(context);

        if (context instanceof Activity) {
            Activity activity = (Activity) context;

            if (!isConnected) {
                // Show the dialog if it's not already showing
                if (_internetDialog == null || !_internetDialog.isShowing()) {
                    _internetDialog = new Dialog(activity);
                    _internetDialog.setContentView(R.layout.dialog_no_internet_connection);

                    if (_internetDialog.getWindow() != null) {
                        _internetDialog.getWindow().setBackgroundDrawable(
                                new ColorDrawable(Color.TRANSPARENT)
                        );
                    }

                    _internetDialog.setCancelable(false);
                    _internetDialog.show();
                }

                Toast.makeText(context, "Disconnected from the internet", Toast.LENGTH_SHORT).show();

            } else {
                // Dismiss the dialog if it exists and is showing
                if (_internetDialog != null && _internetDialog.isShowing()) {
                    _internetDialog.dismiss();
                    _internetDialog = null;
                }

            }
        }
    }
}

