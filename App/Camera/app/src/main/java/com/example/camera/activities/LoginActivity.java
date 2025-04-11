package com.example.camera.activities;


import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.camera.databinding.ActivityLoginBinding;
import com.example.camera.utils.DatabaseManager;
import com.example.camera.utils.Permissions;
import com.example.camera.utils.User;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

public class LoginActivity extends AppCompatActivity {

    private final static String[] PERMS = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.INTERNET};
    private ActivityLoginBinding _views;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityLoginBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());

        if(!Permissions.hasPermissions(PERMS, this)){
            Permissions.requestPermissions(PERMS, 1000, this);
        }

        _views.loginButton.setOnClickListener(v -> {
            String username = _views.usernameInput.getText().toString().trim();

            if (username.isEmpty()) {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show();
                return;
            }

            if(!Permissions.hasPermissions(PERMS, this)){
                Toast.makeText(this, "Allow the app the permissions it needs", Toast.LENGTH_SHORT).show();
                return;
            }

            // Android studio forbids getting the host ip on the main thread
            new Thread(() -> login(username)).start();

        });

    }

    private void login(String username){
        String ip = getLocalIpAddress();

        User newUser = new User(username, ip);
        User.setConnectedUser(newUser);

        DatabaseManager.getInstance().addUser(User.getConnectedUser(), (success) ->{
            if(success){
                startActivity(new Intent(this, RoomPickerActivity.class));
            } else {
                Toast.makeText(this, "Login failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException ex) {
            ex.printStackTrace();
        }
        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

}