// MainActivity.java
package com.example.camapp;

import android.Manifest;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.google.gson.Gson;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.SurfaceTextureHelper;
import org.webrtc.SurfaceViewRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    // UI elements
    private View loginLayout, roomListLayout, callLayout, callControlLayout;
    private EditText usernameInput;
    private RecyclerView roomsRecyclerView, participantsRecyclerView;
    private SurfaceViewRenderer localView;
    private ImageView micButton, videoButton, endCallButton, switchCameraButton;

    // Adapters
    private RoomAdapter roomAdapter;
    private ParticipantAdapter participantAdapter;

    // WebRTC components
    private EglBase eglBase;
    private PeerConnectionFactory peerConnectionFactory;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private CameraVideoCapturer videoCapturer;
    private MediaStream localStream;

    // Connection management
    private Map<String, Peer> peers = new HashMap<>();

    // Firebase
    private DatabaseReference dbRef;
    private String username;
    private Room currentRoom;
    private final Gson gson = new Gson();

    // State flags
    private boolean isMicMuted = false;
    private boolean isVideoMuted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize Firebase
        dbRef = FirebaseDatabase.getInstance().getReference();

        // Initialize UI elements
        initViews();

        // Set up adapters
        setupAdapters();

        // Initialize WebRTC components
        initWebRTC();

        // Set up click listeners
        setupButtonListeners();
    }

    private void initViews() {
        loginLayout = findViewById(R.id.loginLayout);
        roomListLayout = findViewById(R.id.roomListLayout);
        callLayout = findViewById(R.id.callLayout);
        callControlLayout = findViewById(R.id.callControlLayout);

        usernameInput = findViewById(R.id.usernameInput);
//        roomNameInput = findViewById(R.id.roomNameInput);

        roomsRecyclerView = findViewById(R.id.roomsRecyclerView);
        participantsRecyclerView = findViewById(R.id.participantsRecyclerView);

        localView = findViewById(R.id.localView);

        micButton = findViewById(R.id.micButton);
        videoButton = findViewById(R.id.videoButton);
        endCallButton = findViewById(R.id.endCallButton);
        switchCameraButton = findViewById(R.id.switchCameraButton);

        // Initially only show login layout
        loginLayout.setVisibility(View.VISIBLE);
        roomListLayout.setVisibility(View.GONE);
        callLayout.setVisibility(View.GONE);
        callControlLayout.setVisibility(View.GONE);
    }

    private void setupAdapters() {
        // Set up room adapter
        roomAdapter = new RoomAdapter();
        roomsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        roomsRecyclerView.setAdapter(roomAdapter);

        // Set up participant adapter
        participantAdapter = new ParticipantAdapter();
        participantsRecyclerView.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        participantsRecyclerView.setAdapter(participantAdapter);
    }

    private void initWebRTC() {
        // Create EGL context
        eglBase = EglBase.create();

        // Initialize local view
        localView.init(eglBase.getEglBaseContext(), null);
        localView.setEnableHardwareScaler(true);
        localView.setMirror(true);

        // Initialize PeerConnectionFactory
        PeerConnectionFactory.InitializationOptions options =
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .setEnableInternalTracer(true)
                        .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(options);

        PeerConnectionFactory.Options factoryOptions = new PeerConnectionFactory.Options();
        factoryOptions.disableNetworkMonitor = false;
        factoryOptions.disableEncryption = false;

        peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBase.getEglBaseContext(), true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                .setOptions(factoryOptions)
                .createPeerConnectionFactory();
    }

    private void setupButtonListeners() {
        // Login button click
        findViewById(R.id.loginButton).setOnClickListener(v -> {
            if (usernameInput.getText().toString().trim().isEmpty()) {
                Toast.makeText(this, "Please enter a username", Toast.LENGTH_SHORT).show();
                return;
            }

            requestPermissionsAndLogin();
        });

        // Create room button click
        findViewById(R.id.createRoomButton).setOnClickListener(v -> {
            showCreateRoomDialog();
        });

        // End call button click
        endCallButton.setOnClickListener(v -> {
            leaveRoom();
        });

        // Mute mic button click
        micButton.setOnClickListener(v -> {
            isMicMuted = !isMicMuted;
            micButton.setImageResource(isMicMuted ?
                    R.drawable.baseline_mic_24 :
                    R.drawable.baseline_mic_off_24);
            if (localAudioTrack != null) {
                localAudioTrack.setEnabled(!isMicMuted);
            }
        });

        // Toggle video button click
        videoButton.setOnClickListener(v -> {
            isVideoMuted = !isVideoMuted;
            videoButton.setImageResource(isVideoMuted ?
                    R.drawable.baseline_videocam_24 :
                    R.drawable.baseline_videocam_off_24);
            if (localVideoTrack != null) {
                localVideoTrack.setEnabled(!isVideoMuted);
            }
        });

        // Switch camera button click
        switchCameraButton.setOnClickListener(v -> {
            if (videoCapturer != null) {
                videoCapturer.switchCamera(null);
            }
        });
    }

    private void requestPermissionsAndLogin() {
        login();
    }

    private void login() {
        username = usernameInput.getText().toString().trim();

        // Save user to database
        dbRef.child("users").child(username).setValue(true).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                // Show room list interface
                loginLayout.setVisibility(View.GONE);
                roomListLayout.setVisibility(View.VISIBLE);

                // Start local stream
                initLocalStream();

                // Load room list
                loadRoomList();

                // Listen for signaling messages
                listenForSignalingMessages();
            } else {
                Toast.makeText(this, "Login failed", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void initLocalStream() {
        // Create video capturer
        videoCapturer = createCameraCapturer();
        videoSource = peerConnectionFactory.createVideoSource(false);
        audioSource = peerConnectionFactory.createAudioSource(new MediaConstraints());

        // Create thread for capturer
        SurfaceTextureHelper surfaceTextureHelper = SurfaceTextureHelper.create(
                "CaptureThread", eglBase.getEglBaseContext());

        // Initialize capturer
        videoCapturer.initialize(surfaceTextureHelper, this, videoSource.getCapturerObserver());
        videoCapturer.startCapture(480, 360, 30);

        // Create local video track
        localVideoTrack = peerConnectionFactory.createVideoTrack("video_track", videoSource);
        localVideoTrack.addSink(localView);

        // Create local audio track
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource);

        // Create local media stream
        localStream = peerConnectionFactory.createLocalMediaStream("local_stream");
        localStream.addTrack(localVideoTrack);
        localStream.addTrack(localAudioTrack);
    }

    private CameraVideoCapturer createCameraCapturer() {
        Camera2Enumerator enumerator = new Camera2Enumerator(this);
        String[] deviceNames = enumerator.getDeviceNames();

        // Try to find front facing camera
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null);
            }
        }

        // If no front camera, use the first one
        if (deviceNames.length > 0) {
            return enumerator.createCapturer(deviceNames[0], null);
        }

        throw new RuntimeException("No camera available");
    }

    private void loadRoomList() {
        dbRef.child("rooms").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                List<Room> rooms = new ArrayList<>();
                for (DataSnapshot roomSnapshot : snapshot.getChildren()) {
                    Room room = roomSnapshot.getValue(Room.class);
                    if (room != null) {
                        room.setId(roomSnapshot.getKey());
                        rooms.add(room);
                    }
                }
                roomAdapter.setRooms(rooms);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(MainActivity.this, "Failed to load rooms", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showCreateRoomDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_create_room, null);
        EditText roomNameInput = dialogView.findViewById(R.id.roomNameInput);

        new AlertDialog.Builder(this)
                .setTitle("Create Room")
                .setView(dialogView)
                .setPositiveButton("Create", (dialog, which) -> {
                    String roomName = roomNameInput.getText().toString().trim();
                    if (!roomName.isEmpty()) {
                        createRoom(roomName);
                    } else {
                        Toast.makeText(this, "Room name cannot be empty", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createRoom(String roomName) {
        // Create a new room
        String roomId = dbRef.child("rooms").push().getKey();
        if (roomId == null) {
            Toast.makeText(this, "Failed to create room", Toast.LENGTH_SHORT).show();
            return;
        }

        Room room = new Room(roomId, roomName, username);
        room.getParticipants().put(username, true);

        dbRef.child("rooms").child(roomId).setValue(room)
                .addOnCompleteListener(task -> {
                    if (task.isSuccessful()) {
                        joinRoom(room);
                    } else {
                        Toast.makeText(this, "Failed to create room", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void joinRoom(Room room) {
        currentRoom = room;

        // Update room participants
        if (!room.getParticipants().containsKey(username)) {
            dbRef.child("rooms").child(room.getId()).child("participants").child(username).setValue(true);
        }

        // Show call interface
        roomListLayout.setVisibility(View.GONE);
        callLayout.setVisibility(View.VISIBLE);
        callControlLayout.setVisibility(View.VISIBLE);

        // Listen for participant updates
        listenForParticipants();

        // Connect to existing participants (except self)
        for (String participantId : room.getParticipants().keySet()) {
            if (!participantId.equals(username)) {
                connectToParticipant(participantId);
            }
        }
    }

    private void listenForParticipants() {
        dbRef.child("rooms").child(currentRoom.getId()).child("participants")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        List<String> participants = new ArrayList<>();
                        for (DataSnapshot participantSnapshot : snapshot.getChildren()) {
                            String participantId = participantSnapshot.getKey();
                            if (participantId != null) {
                                participants.add(participantId);

                                // Connect to new participants that we're not connected to yet
                                if (!participantId.equals(username) && !peers.containsKey(participantId)) {
                                    connectToParticipant(participantId);
                                }
                            }
                        }

                        // Remove peers that left the room
                        List<String> peersToRemove = new ArrayList<>();
                        for (String peerId : peers.keySet()) {
                            if (!participants.contains(peerId)) {
                                peersToRemove.add(peerId);
                            }
                        }

                        for (String peerId : peersToRemove) {
                            Peer peer = peers.get(peerId);
                            if (peer != null) {
                                peer.close();
                                peers.remove(peerId);
                                participantAdapter.removeParticipant(peerId);
                            }
                        }

                        // Update participant list in UI
                        participantAdapter.setParticipants(participants);
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Toast.makeText(MainActivity.this, "Failed to load participants", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void connectToParticipant(String participantId) {
        if (peers.containsKey(participantId)) {
            return;
        }

        // Create a new peer connection
        Peer peer = new Peer(participantId);
        peers.put(participantId, peer);

        // Create offer if we're the one initiating the connection
        // (we use alphabetical order to determine who initiates)
        if (username.compareTo(participantId) < 0) {
            peer.createOffer();
        }
    }

    private void listenForSignalingMessages() {
        dbRef.child("users").child(username).child("messages")
                .addValueEventListener(new ValueEventListener() {
                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        for (DataSnapshot messageSnapshot : snapshot.getChildren()) {
                            try {
                                String data = messageSnapshot.getValue(String.class);
                                if (data != null) {
                                    SignalingData signalingData = gson.fromJson(data, SignalingData.class);
                                    handleSignalingData(signalingData);
                                }

                                // Remove the message after processing
                                messageSnapshot.getRef().removeValue();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        Toast.makeText(MainActivity.this, "Failed to load messages", Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void handleSignalingData(SignalingData data) {
        // Handle room invitation
        if ("ROOM_INVITE".equals(data.type)) {
            String roomId = data.data;
            dbRef.child("rooms").child(roomId).get().addOnSuccessListener(snapshot -> {
                Room room = snapshot.getValue(Room.class);
                if (room != null) {
                    room.setId(snapshot.getKey());
                    // Ask user if they want to join
                    showRoomInviteDialog(room);
                }
            });
            return;
        }

        // All other messages are WebRTC signaling
        String senderId = data.sender;

        // Get or create the peer
        Peer peer = peers.get(senderId);
        if (peer == null) {
            peer = new Peer(senderId);
            peers.put(senderId, peer);
        }

        switch (data.type) {
            case "OFFER":
                SessionDescription offer = new SessionDescription(
                        SessionDescription.Type.OFFER, data.data);
                peer.setRemoteDescription(offer);
                peer.createAnswer();
                break;

            case "ANSWER":
                SessionDescription answer = new SessionDescription(
                        SessionDescription.Type.ANSWER, data.data);
                peer.setRemoteDescription(answer);
                break;

            case "ICE_CANDIDATE":
                try {
                    IceCandidate candidate = gson.fromJson(data.data, IceCandidate.class);
                    peer.addIceCandidate(candidate);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
        }
    }

    private void showRoomInviteDialog(Room room) {
        new AlertDialog.Builder(this)
                .setTitle("Room Invitation")
                .setMessage(room.getCreator() + " invites you to join " + room.getName())
                .setPositiveButton("Join", (dialog, which) -> joinRoom(room))
                .setNegativeButton("Decline", null)
                .show();
    }

    private void leaveRoom() {
        if (currentRoom != null) {
            // Remove user from room participants
            dbRef.child("rooms").child(currentRoom.getId()).child("participants").child(username).removeValue();

            // Close all peer connections
            for (Peer peer : peers.values()) {
                peer.close();
            }
            peers.clear();
            participantAdapter.clearParticipants();

            // Go back to room list
            callLayout.setVisibility(View.GONE);
            callControlLayout.setVisibility(View.GONE);
            roomListLayout.setVisibility(View.VISIBLE);

            // Check if room is empty and remove if needed
            checkIfRoomEmpty();

            currentRoom = null;
        }
    }

    private void checkIfRoomEmpty() {
        dbRef.child("rooms").child(currentRoom.getId()).child("participants")
                .get().addOnSuccessListener(snapshot -> {
                    if (!snapshot.exists() || snapshot.getChildrenCount() == 0) {
                        // Room is empty, remove it
                        dbRef.child("rooms").child(currentRoom.getId()).removeValue();
                    }
                });
    }

    @Override
    protected void onDestroy() {
        if (currentRoom != null) {
            leaveRoom();
        }

        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            videoCapturer.dispose();
        }

        if (localView != null) {
            localView.release();
        }

        if (eglBase != null) {
            eglBase.release();
        }

        super.onDestroy();
    }

    // Room adapter for displaying available rooms
    private class RoomAdapter extends RecyclerView.Adapter<RoomAdapter.RoomViewHolder> {
        private List<Room> rooms = new ArrayList<>();

        public void setRooms(List<Room> rooms) {
            this.rooms = rooms;
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
            Room room = rooms.get(position);
            holder.bind(room);
        }

        @Override
        public int getItemCount() {
            return rooms.size();
        }

        private class RoomViewHolder extends RecyclerView.ViewHolder {
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

                itemView.setOnClickListener(v -> joinRoom(room));
            }
        }
    }

    // Participant adapter for displaying participants in a call
    private class ParticipantAdapter extends RecyclerView.Adapter<ParticipantAdapter.ParticipantViewHolder> {
        private List<String> participants = new ArrayList<>();

        public void setParticipants(List<String> participants) {
            this.participants = new ArrayList<>(participants);
            notifyDataSetChanged();
        }

        public void removeParticipant(String participantId) {
            int index = participants.indexOf(participantId);
            if (index != -1) {
                participants.remove(index);
                notifyItemRemoved(index);
            }
        }

        public void clearParticipants() {
            int size = participants.size();
            participants.clear();
            notifyItemRangeRemoved(0, size);
        }

        @NonNull
        @Override
        public ParticipantViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_participant, parent, false);
            return new ParticipantViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ParticipantViewHolder holder, int position) {
            String participantId = participants.get(position);
            holder.bind(participantId);
        }

        @Override
        public int getItemCount() {
            return participants.size();
        }

        private class ParticipantViewHolder extends RecyclerView.ViewHolder {
            private final TextView participantNameText;
            private final SurfaceViewRenderer participantVideo;

            public ParticipantViewHolder(@NonNull View itemView) {
                super(itemView);
                participantNameText = itemView.findViewById(R.id.participantNameText);
                participantVideo = itemView.findViewById(R.id.participantVideo);

                // Initialize video renderer
                participantVideo.init(eglBase.getEglBaseContext(), null);
                participantVideo.setEnableHardwareScaler(true);
                participantVideo.setMirror(false);
            }

            public void bind(String participantId) {
                participantNameText.setText(participantId);

                // Set video stream if available
                Peer peer = peers.get(participantId);
                if (peer != null && peer.getRemoteVideoTrack() != null) {
                    peer.getRemoteVideoTrack().addSink(participantVideo);
                } else if (participantId.equals(username) && localVideoTrack != null) {
                    // Show local stream for current user
                    localVideoTrack.addSink(participantVideo);
                }
            }
        }
    }

    // Peer class to manage connection with a specific peer
    private class Peer {
        private final String peerId;
        private PeerConnection peerConnection;
        private VideoTrack remoteVideoTrack;

        public Peer(String peerId) {
            this.peerId = peerId;
            createPeerConnection();
        }

        private void createPeerConnection() {
            // Configure ICE servers (STUN/TURN)
            List<PeerConnection.IceServer> iceServers = new ArrayList<>();
            iceServers.add(PeerConnection.IceServer.builder("turn:a.relay.metered.ca:443?transport=tcp")
                    .setUsername("83eebabf8b4cce9d5dbcb649")
                    .setPassword("2D7JvfkOQtBdYW3R")
                    .createIceServer());

            // Create peer connection
            peerConnection = peerConnectionFactory.createPeerConnection(
                    iceServers,
                    new PeerConnection.Observer() {
                        @Override
                        public void onIceCandidate(IceCandidate iceCandidate) {
                            sendIceCandidate(iceCandidate);
                        }

                        @Override
                        public void onAddStream(MediaStream mediaStream) {
                            runOnUiThread(() -> {
                                if (mediaStream.videoTracks.size() > 0) {
                                    remoteVideoTrack = mediaStream.videoTracks.get(0);

                                    // Add to participant video if already in adapter
                                    int index = participantAdapter.participants.indexOf(peerId);
                                    if (index != -1) {
                                        participantAdapter.notifyItemChanged(index);
                                    }
                                }
                            });
                        }

                        // Empty implementations for required methods
                        @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
                        @Override public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {}
                        @Override public void onIceConnectionReceivingChange(boolean b) {}
                        @Override public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}
                        @Override public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
                        @Override public void onRemoveStream(MediaStream mediaStream) {}
                        @Override public void onDataChannel(org.webrtc.DataChannel dataChannel) {}
                        @Override public void onRenegotiationNeeded() {}
                        @Override public void onAddTrack(org.webrtc.RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {}
                        @Override public void onConnectionChange(PeerConnection.PeerConnectionState newState) {}
                    });

            // Add local stream to connection
            peerConnection.addStream(localStream);
        }

        public void createOffer() {
            MediaConstraints constraints = new MediaConstraints();
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

            peerConnection.createOffer(new SimpleSdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {
                    peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);

                    // Send offer to remote peer
                    SignalingData offerData = new SignalingData();
                    offerData.type = "OFFER";
                    offerData.sender = username;
                    offerData.target = peerId;
                    offerData.data = sessionDescription.description;

                    sendSignalingData(offerData);
                }
            }, constraints);
        }

        public void createAnswer() {
            MediaConstraints constraints = new MediaConstraints();
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));

            peerConnection.createAnswer(new SimpleSdpObserver() {
                @Override
                public void onCreateSuccess(SessionDescription sessionDescription) {
                    peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);

                    // Send answer to remote peer
                    SignalingData answerData = new SignalingData();
                    answerData.type = "ANSWER";
                    answerData.sender = username;
                    answerData.target = peerId;
                    answerData.data = sessionDescription.description;

                    sendSignalingData(answerData);
                }
            }, constraints);
        }

        public void setRemoteDescription(SessionDescription sessionDescription) {
            peerConnection.setRemoteDescription(new SimpleSdpObserver(), sessionDescription);
        }

        public void addIceCandidate(IceCandidate iceCandidate) {
            if (peerConnection != null) {
                peerConnection.addIceCandidate(iceCandidate);
            }
        }

        private void sendIceCandidate(IceCandidate iceCandidate) {
            SignalingData candidateData = new SignalingData();
            candidateData.type = "ICE_CANDIDATE";
            candidateData.sender = username;
            candidateData.target = peerId;
            candidateData.data = gson.toJson(iceCandidate);

            sendSignalingData(candidateData);
        }

        public VideoTrack getRemoteVideoTrack() {
            return remoteVideoTrack;
        }

        public void close() {
            if (peerConnection != null) {
                peerConnection.close();
                peerConnection = null;
            }
            remoteVideoTrack = null;
        }
    }

    private void sendSignalingData(SignalingData data) {
        // Add message to recipient's message queue
        String messageId = dbRef.child("users").child(data.target).child("messages").push().getKey();
        if (messageId != null) {
            dbRef.child("users").child(data.target).child("messages").child(messageId)
                    .setValue(gson.toJson(data));
        }
    }

    // Simple SDP observer with empty implementations
    private static class SimpleSdpObserver implements org.webrtc.SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) {}
        @Override public void onSetFailure(String s) {}
    }

    // Data model for signaling
    private static class SignalingData {
        String type;      // OFFER, ANSWER, ICE_CANDIDATE, ROOM_INVITE
        String sender;    // Username of sender
        String target;    // Username of target
        String data;      // SDP, ICE candidate data, or room ID
    }

    // Room model
    public static class Room {
        private String id;
        private String name;
        private String creator;
        private Map<String, Boolean> participants = new HashMap<>();

        // Required empty constructor for Firebase
        public Room() {}

        public Room(String id, String name, String creator) {
            this.id = id;
            this.name = name;
            this.creator = creator;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCreator() {
            return creator;
        }

        public void setCreator(String creator) {
            this.creator = creator;
        }

        public Map<String, Boolean> getParticipants() {
            return participants;
        }

        public void setParticipants(Map<String, Boolean> participants) {
            this.participants = participants;
        }
    }
}