package com.example.camera.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.example.camera.activities.HomeActivity;
import com.example.camera.utils.NetworkingUtils;



public class InternetConnectionChangeReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {

        if(!NetworkingUtils.isConnectedToInternet(context)){

            context.startActivity(new Intent(context, HomeActivity.class));

            Toast.makeText(context, "disconnected from the internet", Toast.LENGTH_SHORT).show();
        }
    }





}
