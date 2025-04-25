package com.example.camera.adapters;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.camera.R;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.utils.Room;
import com.example.camera.utils.User;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;

public class FriendRequestAdapter extends RecyclerView.Adapter<FriendRequestAdapter.RequestViewHolder> {

    private List<String> requests;
    private String currentUser;
    private DatabaseReference db = FirebaseDatabase.getInstance().getReference();

    public FriendRequestAdapter(List<String> requests) {
        this.requests = requests;
        this.currentUser = User.getConnectedUser().getUsername();
    }

    public FriendRequestAdapter() {
        this(new ArrayList<>());
    }

    public void setRequests(List<String> requests) {
        this.requests.clear();
        this.requests.addAll(requests);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RequestViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_friend_request, parent, false);
        return new RequestViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RequestViewHolder holder, int position) {
        String requester = requests.get(position);
        holder.tvUsername.setText(requester);

        holder.btnAccept.setOnClickListener(v -> {
            DatabaseManager.getInstance().acceptFriendRequest(currentUser, requester, success -> {
                if (success) {
                    requests.remove(position);
                    notifyItemRemoved(position);
                }
            });
        });

        holder.btnDecline.setOnClickListener(v -> {
            DatabaseManager.getInstance().removeFriendRequest(currentUser, requester);
            requests.remove(position);
            notifyItemRemoved(position);
        });
    }

    @Override
    public int getItemCount() {
        return requests.size();
    }

    public static class RequestViewHolder extends RecyclerView.ViewHolder {
        TextView tvUsername;
        Button btnAccept, btnDecline;

        public RequestViewHolder(@NonNull View itemView) {
            super(itemView);
            tvUsername = itemView.findViewById(R.id.tvUsername);
            btnAccept = itemView.findViewById(R.id.btnAccept);
            btnDecline = itemView.findViewById(R.id.btnDecline);
        }
    }





}
