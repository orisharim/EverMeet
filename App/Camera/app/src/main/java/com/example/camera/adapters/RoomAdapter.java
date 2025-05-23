package com.example.camera.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.camera.R;
import com.example.camera.classes.Room;
import com.example.camera.classes.User;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class RoomAdapter extends RecyclerView.Adapter<RoomAdapter.RoomViewHolder> {
    private static final String TAG = "RoomAdapter";
    private List<Room> _rooms = new ArrayList<>();
    private Consumer<Room> _onRoomClick;

    public RoomAdapter(Consumer<Room> onRoomClick) {
        _onRoomClick = onRoomClick;
    }

    public void setRooms(List<Room> rooms) {
        this._rooms = rooms;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RoomViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_room, parent, false);
        return new RoomViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RoomViewHolder holder, int position) {
        Room room = _rooms.get(position);
        holder.bind(room);
    }

    @Override
    public int getItemCount() {
        return _rooms.size();
    }

    public class RoomViewHolder extends RecyclerView.ViewHolder {
        private final TextView roomNameText, roomCreatorText, participantCountText;

        public RoomViewHolder(@NonNull View itemView) {
            super(itemView);
            roomNameText = itemView.findViewById(R.id.roomNameText);
            roomCreatorText = itemView.findViewById(R.id.roomCreatorText);
            participantCountText = itemView.findViewById(R.id.participantCountText);
        }

        public void bind(Room room) {
            roomNameText.setText(room.getName());
            roomCreatorText.setText("Created by: " + room.getCreator());
            int participantCount = room.getParticipants() != null ? room.getParticipants().size() : 0;
            participantCountText.setText(participantCount + " participants");

            itemView.setOnClickListener(v -> _onRoomClick.accept(room));
        }
    }
}