package com.example.camera.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.camera.R;
import com.example.camera.classes.User;
import com.example.camera.managers.DatabaseManager;

import java.util.ArrayList;
import java.util.List;
public class FriendsAdapter extends RecyclerView.Adapter<FriendsAdapter.FriendViewHolder> {

    private List<String> _friends;
    private String _currentUser;

    public FriendsAdapter() {
        this._friends = new ArrayList<>();
        this._currentUser = User.getConnectedUser().getUsername();
    }

    public void setFriends(List<String> _friends) {
        this._friends = _friends;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FriendViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_friend, parent, false);
        return new FriendViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FriendViewHolder holder, int position) {
        String friend = _friends.get(position);
        holder.friendUsername.setText(friend);

    }

    @Override
    public int getItemCount() {
        return _friends.size();
    }

    public static class FriendViewHolder extends RecyclerView.ViewHolder {
        TextView friendUsername;

        public FriendViewHolder(@NonNull View itemView) {
            super(itemView);
            friendUsername = itemView.findViewById(R.id.friendUsername);
            itemView.findViewById(R.id.deleteFriendButton).setOnClickListener(v -> {
                DatabaseManager.getInstance().removeFriend(User.getConnectedUser().getUsername(), friendUsername.getText().toString(), task -> {});
            });
        }
    }
}


