package com.example.camera.adapters;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.camera.R;

import java.util.ArrayList;
import java.util.HashMap;

public class CamerasAdapter extends RecyclerView.Adapter<CamerasAdapter.CameraViewHolder> {
    private final HashMap<String, Bitmap> _participants = new HashMap<>();
    private final ArrayList<String> _participantsUsernames = new ArrayList<>();

    public void setParticipants(HashMap<String, Bitmap> participants) {
        _participants.clear();
        _participants.putAll(participants);

        _participantsUsernames.clear();
        _participantsUsernames.addAll(participants.keySet());

        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public CameraViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_camera, parent, false);
        return new CameraViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CameraViewHolder holder, int position) {
        String username = _participantsUsernames.get(position);
        Bitmap frame = _participants.get(username);

        if (frame != null && holder.surfaceHolder.getSurface().isValid()) {
            Canvas canvas = holder.surfaceHolder.lockCanvas();
            if (canvas != null) {
                canvas.drawColor(Color.BLACK);
                canvas.drawBitmap(frame, 0, 0, null);
                holder.surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }

        if (!holder.usernameTextView.getText().toString().equals(username)) {
            holder.usernameTextView.setText(username);
        }
    }

    public void updateParticipantFrame(String username, Bitmap newFrame) {
        if (_participants.containsKey(username)) {
            _participants.put(username, newFrame);
            int position = _participantsUsernames.indexOf(username);
            if (position != -1) {
                notifyItemChanged(position);
            }
        }
    }

    @Override
    public int getItemCount() {
        return _participants.size();
    }

    public static class CameraViewHolder extends RecyclerView.ViewHolder {
        SurfaceView surfaceView;
        TextView usernameTextView;
        SurfaceHolder surfaceHolder;

        public CameraViewHolder(@NonNull View itemView) {
            super(itemView);
            surfaceView = itemView.findViewById(R.id.participantCamera);
            usernameTextView = itemView.findViewById(R.id.participantUsername);
            surfaceHolder = surfaceView.getHolder();
        }
    }
}
