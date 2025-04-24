package com.example.camera.adapters;

import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.camera.R;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ParticipantAdapter extends RecyclerView.Adapter<ParticipantAdapter.ViewHolder> {

    private final List<String> participants;
    private final Map<String, Bitmap> participantFrames;

    public ParticipantAdapter(List<String> participants) {
        this.participants = participants;
        this.participantFrames = new HashMap<>();
    }

    public void setParticipants(List<String> newParticipants) {
        participants.clear();
        participants.addAll(newParticipants);
        notifyDataSetChanged();
    }

    public void addParticipant(String username) {
        if (!participants.contains(username)) {
            participants.add(username);
            notifyItemInserted(participants.size() - 1);
        }
    }

    public void removeParticipant(String username) {
        int index = participants.indexOf(username);
        if (index >= 0) {
            participants.remove(index);
            notifyItemRemoved(index);
        }
    }

    public void updateFrame(String username, Bitmap frame) {
        participantFrames.put(username, frame);
        int index = participants.indexOf(username);
        if (index >= 0) {
            notifyItemChanged(index);
        }
    }



    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_participant, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String username = participants.get(position);
        Bitmap frame = participantFrames.get(username);
        if (frame != null) {
            holder.videoView.setImageBitmap(frame);
        }
    }

    @Override
    public int getItemCount() {
        return participants.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView videoView;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            videoView = itemView.findViewById(R.id.participantVideo);
        }
    }
}