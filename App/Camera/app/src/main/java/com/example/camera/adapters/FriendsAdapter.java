package com.example.camera.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.camera.R;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.List;
public class FriendsAdapter extends RecyclerView.Adapter<FriendsAdapter.FriendViewHolder> {

    private List<String> friends;
    private String currentUser;
    private DatabaseReference db = FirebaseDatabase.getInstance().getReference();

    public FriendsAdapter(List<String> friends, String currentUser) {
        this.friends = friends;
        this.currentUser = currentUser;
    }

    @NonNull
    @Override
    public FriendViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_friend, parent, false);
        return new FriendViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FriendViewHolder holder, int position) {
        String friend = friends.get(position);
        holder.tvUsername.setText(friend);

        // Add more logic here for actions like messaging the friend or viewing their profile
        holder.itemView.setOnClickListener(v -> {
            // Example: Show a message or profile action
            Toast.makeText(holder.itemView.getContext(), "Clicked on " + friend, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    public int getItemCount() {
        return friends.size();
    }

    public static class FriendViewHolder extends RecyclerView.ViewHolder {
        TextView tvUsername;

        public FriendViewHolder(@NonNull View itemView) {
            super(itemView);
            tvUsername = itemView.findViewById(R.id.tvUsername);
        }
    }
}


