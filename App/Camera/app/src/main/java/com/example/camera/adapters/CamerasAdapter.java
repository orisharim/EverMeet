package com.example.camera.adapters;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;
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
import java.util.List;

public class CamerasAdapter extends RecyclerView.Adapter<CamerasAdapter.CameraViewHolder> {

    private HashMap<String, Bitmap> _participants; // username, frame
    private ArrayList<String> _participantsUsernames;

    public CamerasAdapter() {
        _participants = new HashMap<>();
        _participantsUsernames = new ArrayList<>();
    }

    // Set the participants' data and notify the adapter to update the RecyclerView
    public void setParticipants(HashMap<String, Bitmap> participants) {
        _participants = participants;
        _participantsUsernames = new ArrayList<>(participants.keySet());
        notifyDataSetChanged();
    }

    // Called when a new view holder is created
    @NonNull
    @Override
    public CameraViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_camera, parent, false);
        return new CameraViewHolder(view);
    }

    // Bind the data to the view holder (i.e., display the video frames)
    @Override
    public void onBindViewHolder(@NonNull CameraViewHolder holder, int position) {
        String participantUsername = _participantsUsernames.get(position);
        Bitmap frame = _participants.get(participantUsername);

        // Resize the SurfaceView to be 50% of the screen's width/height
        int screenWidth = holder.itemView.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = holder.itemView.getResources().getDisplayMetrics().heightPixels;

        int maxWidth = screenWidth / 2;
        int maxHeight = screenHeight / 2;

        // Calculate and set the aspect ratio for scaling the video
        float aspectRatio = (frame != null) ? (float) frame.getWidth() / frame.getHeight() : (4f / 3f);
        int finalWidth = maxWidth;
        int finalHeight = (int) (maxWidth / aspectRatio);

        // Adjust if the height exceeds the maximum allowed height
        if (finalHeight > maxHeight) {
            finalHeight = maxHeight;
            finalWidth = (int) (maxHeight * aspectRatio);
        }

        // Set the resized layout params for SurfaceView
        ViewGroup.LayoutParams params = holder.surfaceView.getLayoutParams();
        params.width = finalWidth;
        params.height = finalHeight;
        holder.surfaceView.setLayoutParams(params);

        // Draw the bitmap on the SurfaceView
        if (frame != null && holder.surfaceHolder.getSurface().isValid()) {
            Canvas canvas = holder.surfaceHolder.lockCanvas();
            if (canvas != null) {
                canvas.drawColor(Color.BLACK); // Clear the previous frame
                drawScaledBitmap(canvas, frame); // Draw the current frame with proper scaling
                holder.surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }

        // Set the username (below the video frame)
        holder.usernameTextView.setText(participantUsername);
    }

    // Calculate the correct scale to preserve aspect ratio and center the image
    private void drawScaledBitmap(Canvas canvas, Bitmap bitmap) {
        int canvasWidth = canvas.getWidth();
        int canvasHeight = canvas.getHeight();
        int bmpWidth = bitmap.getWidth();
        int bmpHeight = bitmap.getHeight();

        float canvasAspect = (float) canvasWidth / canvasHeight;
        float bmpAspect = (float) bmpWidth / bmpHeight;

        int drawWidth, drawHeight;

        if (bmpAspect > canvasAspect) {
            // Bitmap is wider than canvas
            drawWidth = canvasWidth;
            drawHeight = (int) (canvasWidth / bmpAspect);
        } else {
            // Bitmap is taller than canvas
            drawHeight = canvasHeight;
            drawWidth = (int) (canvasHeight * bmpAspect);
        }

        int left = (canvasWidth - drawWidth) / 2;
        int top = (canvasHeight - drawHeight) / 2;

        Rect destRect = new Rect(left, top, left + drawWidth, top + drawHeight);
        canvas.drawBitmap(bitmap, null, destRect, null);
    }

    // Update the video frame for a specific participant
    public void updateParticipantFrame(String username, Bitmap newFrame) {
        if (_participants.containsKey(username)) {
            _participants.put(username, newFrame);
            int position = _participantsUsernames.indexOf(username);
            if (position != -1) {
                notifyItemChanged(position);
            }
        } else {
            Log.e("CamerasAdapter", "Participant not found: " + username);
        }
    }

    // Get the number of participants (items in the adapter)
    @Override
    public int getItemCount() {
        return _participants.size();
    }

    // ViewHolder class for individual camera views
    public static class CameraViewHolder extends RecyclerView.ViewHolder {
        SurfaceView surfaceView;
        TextView usernameTextView;
        SurfaceHolder surfaceHolder;

        public CameraViewHolder(@NonNull View itemView) {
            super(itemView);
            surfaceView = itemView.findViewById(R.id.participantCamera);
            usernameTextView = itemView.findViewById(R.id.participantUsername);

            // Initialize the SurfaceHolder and listen for surface changes
            surfaceHolder = surfaceView.getHolder();
            surfaceHolder.addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    // Handle surface creation (optional)
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    // Handle surface size changes (optional)
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    // Handle surface destruction (optional)
                }
            });
        }
    }
}
