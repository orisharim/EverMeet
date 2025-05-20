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

    private static final String TAG = "FriendsFragment";
    private FragmentFriendsBinding _views;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        _views = FragmentFriendsBinding.inflate(inflater, container, false);
        return _views.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupRecyclerViews();
        setupDatabaseListeners();
        setupSendRequestButton();
    }

    private void setupRecyclerViews() {
        FriendRequestAdapter requestAdapter = new FriendRequestAdapter();
        _views.friendRequestsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        _views.friendRequestsRecyclerView.setAdapter(requestAdapter);

        FriendsAdapter friendsAdapter = new FriendsAdapter();
        _views.friendsRecyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        _views.friendsRecyclerView.setAdapter(friendsAdapter);
    }

    private void setupDatabaseListeners() {
        String username = User.getConnectedUser().getUsername();

        DatabaseManager.getInstance().setOnFriendRequestsReceived(username, requests -> {
            FriendRequestAdapter adapter = (FriendRequestAdapter) _views.friendRequestsRecyclerView.getAdapter();
            if (adapter != null) adapter.setRequests(requests);
        });

        DatabaseManager.getInstance().setOnFriendsDataReceived(username, friends -> {
            FriendsAdapter adapter = (FriendsAdapter) _views.friendsRecyclerView.getAdapter();
            if (adapter != null) adapter.setFriends(friends);
        });
    }

    private void setupSendRequestButton() {
        _views.sendFriendRequestButton.setOnClickListener(v -> showSendFriendRequestDialog());
    }

    private void showSendFriendRequestDialog() {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(requireContext());
        dialogBuilder.setTitle("Send Friend Request");

        View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_send_friend_request, null);
        dialogBuilder.setView(dialogView);

        EditText inputField = dialogView.findViewById(R.id.friendUsernameInput);
        Button sendButton = dialogView.findViewById(R.id.sendFriendRequestButton);
        Button cancelButton = dialogView.findViewById(R.id.cancelButton);

        AlertDialog dialog = dialogBuilder.create();

        sendButton.setOnClickListener(v -> {
            String targetUsername = inputField.getText().toString().trim();
            if (targetUsername.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter a username.", Toast.LENGTH_SHORT).show();
                return;
            }

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
        });

        cancelButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        _views = null;
    }
}
