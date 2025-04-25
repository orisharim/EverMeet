package com.example.camera.fragments;


import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.example.camera.R;
import com.example.camera.adapters.FriendRequestAdapter;
import com.example.camera.adapters.FriendsAdapter;

import com.example.camera.databinding.FragmentFriendsBinding;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.classes.User;
public class FriendsFragment extends Fragment {

    private FragmentFriendsBinding _views;
    private FriendRequestAdapter requestAdapter;
    private FriendsAdapter friendsAdapter;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        _views = FragmentFriendsBinding.inflate(inflater, container, false);
        return _views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        requestAdapter = new FriendRequestAdapter();
        _views.friendRequestsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        _views.friendRequestsRecyclerView.setAdapter(requestAdapter);

        friendsAdapter = new FriendsAdapter();
        _views.friendsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        _views.friendsRecyclerView.setAdapter(friendsAdapter);

        DatabaseManager.getInstance().setOnFriendRequestsReceived(requestAdapter::setRequests);
        DatabaseManager.getInstance().setOnFriendsDataReceived(friendsAdapter::setFriends);

        _views.btnSendFriendRequest.setOnClickListener(v -> showSendFriendRequestDialog());
    }

    private void showSendFriendRequestDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Send Friend Request");

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_send_friend_request, null);
        builder.setView(dialogView);

        EditText etFriendUsername = dialogView.findViewById(R.id.etFriendUsername);
        Button btnSend = dialogView.findViewById(R.id.btnSendFriendRequest);
        Button btnCancel = dialogView.findViewById(R.id.btnCancel);

        AlertDialog dialog = builder.create();

        btnSend.setOnClickListener(v -> {
            String targetUsername = etFriendUsername.getText().toString().trim();
            if (!targetUsername.isEmpty()) {
                DatabaseManager.getInstance().sendFriendRequest(
                        User.getConnectedUser().getUsername(),
                        targetUsername,
                        success -> {
                            if (success) {
                                Toast.makeText(requireContext(), "Friend request sent!", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(requireContext(), "Failed to send request.", Toast.LENGTH_SHORT).show();
                            }
                        });
                dialog.dismiss();
            } else {
                Toast.makeText(requireContext(), "Please enter a username.", Toast.LENGTH_SHORT).show();
            }
        });

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        _views = null;
    }
}

