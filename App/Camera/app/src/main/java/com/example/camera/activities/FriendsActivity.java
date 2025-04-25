package com.example.camera.activities;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.camera.R;
import com.example.camera.adapters.FriendRequestAdapter;
import com.example.camera.adapters.FriendsAdapter;
import com.example.camera.databinding.ActivityCallBinding;
import com.example.camera.databinding.ActivityFriendsBinding;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.User;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;
public class FriendsActivity extends AppCompatActivity {


    private ActivityFriendsBinding _views;
    private FriendRequestAdapter requestAdapter;
    private FriendsAdapter friendsAdapter;

    private List<String> friends = new ArrayList<>();
    private DatabaseReference db = FirebaseDatabase.getInstance().getReference();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        _views = ActivityFriendsBinding.inflate(getLayoutInflater());
        setContentView(R.layout.activity_friends);

        requestAdapter = new FriendRequestAdapter(new ArrayList<>());
        _views.friendRequestsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        _views.friendRequestsRecyclerView.setAdapter(requestAdapter);

        friendsAdapter = new FriendsAdapter(friends, User.getConnectedUser().getUsername());
        _views.friendsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        _views.friendsRecyclerView.setAdapter(friendsAdapter);

        DatabaseManager.getInstance().setOnFriendRequestsReceived(requestAdapter::setRequests);
        loadFriends();

        Button btnSendRequest = findViewById(R.id.btnSendFriendRequest);
        btnSendRequest.setOnClickListener(v -> showSendFriendRequestDialog());
    }



    private void loadFriends() {
        db.child("users").child(User.getConnectedUser().getUsername()).child("friends")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        friends.clear();
                        for (DataSnapshot child : snapshot.getChildren()) {
                            String friend = child.getValue(String.class);
                            if (friend != null) friends.add(friend);
                        }
                        friendsAdapter.notifyDataSetChanged();
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Log.e("Firebase", "Error loading friends", error.toException());
                    }
                });
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
                sendFriendRequest(User.getConnectedUser().getUsername(), targetUsername);
                dialog.dismiss();
            } else {
                Toast.makeText(this, "Please enter a username.", Toast.LENGTH_SHORT).show();
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void sendFriendRequest(String fromUsername, String toUsername) {
        DatabaseReference requestRef = db.child("friend_requests").child(toUsername);

        requestRef.push().setValue(fromUsername)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        Toast.makeText(FriendsActivity.this, "Friend request sent!", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(FriendsActivity.this, "Failed to send request.", Toast.LENGTH_SHORT).show();
                    }
                });
    }
}

