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

    public FriendRequestAdapter(List<String> requests, String currentUser) {
        this.requests = requests;
        this.currentUser = currentUser;
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
            acceptFriendRequest(currentUser, requester, success -> {
                if (success) {
                    requests.remove(position);
                    notifyItemRemoved(position);
                }
            });
        });

        holder.btnDecline.setOnClickListener(v -> {
            removeFriendRequest(currentUser, requester);
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

    private void removeFriendRequest(String currentUsername, String fromUsername) {
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("friend_requests").child(currentUsername);
        ref.orderByValue().equalTo(fromUsername).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                for (DataSnapshot child : snapshot.getChildren()) {
                    child.getRef().removeValue();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e("Firebase", "Failed to decline request", error.toException());
            }
        });
    }

    private void acceptFriendRequest(String currentUsername, String fromUsername, Consumer<Boolean> onComplete) {
        DatabaseReference usersRef = db.child("users");
        DatabaseReference requestsRef = db.child("friend_requests");

        usersRef.child(currentUsername).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot1) {
                User currentUser = snapshot1.getValue(User.class);
                if (currentUser == null) {
                    currentUser = new User(currentUsername, "", new ArrayList<>(), true);
                } else if (currentUser.getFriends() == null) {
                    currentUser.setFriends(new ArrayList<>());
                }

                if (!currentUser.getFriends().contains(fromUsername)) {
                    currentUser.getFriends().add(fromUsername);
                }

                User finalCurrentUser = currentUser;
                usersRef.child(fromUsername).addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot2) {
                        User fromUser = snapshot2.getValue(User.class);
                        if (fromUser == null) {
                            fromUser = new User(fromUsername, "", new ArrayList<>(), true);
                        } else if (fromUser.getFriends() == null) {
                            fromUser.setFriends(new ArrayList<>());
                        }

                        if (!fromUser.getFriends().contains(currentUsername)) {
                            fromUser.getFriends().add(currentUsername);
                        }

                        // Save updated users
                        User finalFromUser = fromUser;
                        usersRef.child(currentUsername).setValue(finalCurrentUser).addOnCompleteListener(task1 -> {
                            if (task1.isSuccessful()) {
                                usersRef.child(fromUsername).setValue(finalFromUser).addOnCompleteListener(task2 -> {
                                    if (task2.isSuccessful()) {
                                        // Remove the friend request from currentUser's requests list
                                        requestsRef.child(currentUsername)
                                                .orderByValue()
                                                .equalTo(fromUsername)
                                                .addListenerForSingleValueEvent(new ValueEventListener() {
                                                    @Override
                                                    public void onDataChange(@NonNull DataSnapshot requestSnapshot) {
                                                        for (DataSnapshot child : requestSnapshot.getChildren()) {
                                                            child.getRef().removeValue(); // Delete matching request
                                                        }
                                                        onComplete.accept(true);
                                                    }

                                                    @Override
                                                    public void onCancelled(@NonNull DatabaseError error) {
                                                        onComplete.accept(false);
                                                    }
                                                });
                                    } else {
                                        onComplete.accept(false);
                                    }
                                });
                            } else {
                                onComplete.accept(false);
                            }
                        });
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        onComplete.accept(false);
                    }
                });
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                onComplete.accept(false);
            }
        });
    }



}
