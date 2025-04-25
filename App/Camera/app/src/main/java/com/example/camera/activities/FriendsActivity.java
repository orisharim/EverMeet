package com.example.camera.activities;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.camera.R;
import com.example.camera.adapters.FriendRequestAdapter;
import com.example.camera.adapters.FriendsAdapter;
import com.example.camera.databinding.ActivityFriendsBinding;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.classes.User;

public class FriendsActivity extends AppCompatActivity {

    private ActivityFriendsBinding _views;
    private FriendRequestAdapter requestAdapter;
    private FriendsAdapter friendsAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityFriendsBinding.inflate(getLayoutInflater());
        setContentView(_views.getRoot());

        requestAdapter = new FriendRequestAdapter();
        _views.friendRequestsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        _views.friendRequestsRecyclerView.setAdapter(requestAdapter);

        friendsAdapter = new FriendsAdapter();
        _views.friendsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        _views.friendsRecyclerView.setAdapter(friendsAdapter);

        DatabaseManager.getInstance().setOnFriendRequestsReceived(requestAdapter::setRequests);
        DatabaseManager.getInstance().setOnFriendsDataReceived(friendsAdapter::setFriends);

        _views.btnSendFriendRequest.setOnClickListener(v -> showSendFriendRequestDialog());
    }

    private void showSendFriendRequestDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Send Friend Request");

        View view = getLayoutInflater().inflate(R.layout.dialog_send_friend_request, null);
        builder.setView(view);

        EditText etFriendUsername = view.findViewById(R.id.etFriendUsername);
        Button btnSend = view.findViewById(R.id.btnSendFriendRequest);
        Button btnCancel = view.findViewById(R.id.btnCancel);

        AlertDialog dialog = builder.create();

        btnSend.setOnClickListener(v -> {
            String targetUsername = etFriendUsername.getText().toString().trim();
            if (!targetUsername.isEmpty()) {
                DatabaseManager.getInstance().sendFriendRequest(User.getConnectedUser().getUsername(),
                        targetUsername, success -> {
                            if (success) {
                                Toast.makeText(FriendsActivity.this, "Friend request sent!", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(FriendsActivity.this, "Failed to send request.", Toast.LENGTH_SHORT).show();
                            }
                        });
                dialog.dismiss();
            } else {
                Toast.makeText(this, "Please enter a username.", Toast.LENGTH_SHORT).show();
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }


}

