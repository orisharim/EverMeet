package com.example.camera.utils;


import android.content.Context;
import android.content.SharedPreferences;

public class StorageUtils {
    public static void saveUserInStorage(Context context, String username, String password){
        SharedPreferences sharedPreferences = context.getSharedPreferences("EverMeetAppPrefs", android.content.Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("username", username);
        editor.putString("password", password);
        editor.apply();
    }


    public static void removeUserFromStorage(Context context){
        SharedPreferences sharedPreferences = context.getSharedPreferences("EverMeetAppPrefs", android.content.Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString("username", "");
        editor.putString("password", "");
        editor.apply();
    }
    public static String getLoggedInUsernameFromStorage(Context context){
        SharedPreferences sharedPreferences = context.getSharedPreferences("EverMeetAppPrefs", android.content.Context.MODE_PRIVATE);
        return sharedPreferences.getString("username", "");
    }

    public static String getLoggedInPasswordFromStorage(Context context){
        SharedPreferences sharedPreferences = context.getSharedPreferences("EverMeetAppPrefs", android.content.Context.MODE_PRIVATE);
        return sharedPreferences.getString("password", "");
    }

}
