package com.example.camera.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.camera.R;
import com.example.camera.managers.DatabaseManager;
import com.example.camera.classes.User;

import java.util.ArrayList;
import java.util.List;

public class FriendRequestAdapter extends RecyclerView.Adapter<FriendRequestAdapter.RequestViewHolder> {

    private List<String> _requests;
    private String _currentUser;

    public FriendRequestAdapter(List<String> requests) {
        _requests = requests;
        _currentUser = User.getConnectedUser().getUsername();
    }

    public FriendRequestAdapter() {
        this(new ArrayList<>());
    }

    public void setRequests(List<String> requests) {
        _requests = requests;
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
        String requester = _requests.get(position);
        holder.tvUsername.setText(requester);

        holder.btnAccept.setOnClickListener(v -> {
            DatabaseManager.getInstance().acceptFriendRequest(_currentUser, requester, success -> {
                if (success) {
                    _requests.remove(position);
                    notifyItemRemoved(position);
                }
            });
        });

        holder.btnDecline.setOnClickListener(v -> {
            DatabaseManager.getInstance().removeFriendRequest(_currentUser, requester, aBoolean -> {});
            _requests.remove(position);
            notifyItemRemoved(position);
        });
    }

    @Override
    public int getItemCount() {
        return _requests.size();
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
