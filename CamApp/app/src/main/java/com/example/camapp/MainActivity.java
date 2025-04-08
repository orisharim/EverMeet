// MainActivity.java
package com.example.camapp;

import android.Manifest;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

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
import java.util.List;

public class MainActivity extends AppCompatActivity {

    // UI elements
    private View loginLayout, callControlLayout, incomingCallLayout, targetUserLayout;
    private EditText usernameInput, targetUserInput;
    private TextView incomingCallText;
    private SurfaceViewRenderer localView, remoteView;
    private ImageView micButton, videoButton, endCallButton, switchCameraButton;

    // WebRTC components
    private EglBase.Context eglBaseContext;
    private PeerConnectionFactory peerConnectionFactory;
    private VideoTrack localVideoTrack;
    private AudioTrack localAudioTrack;
    private PeerConnection peerConnection;
    private VideoSource videoSource;
    private AudioSource audioSource;
    private CameraVideoCapturer videoCapturer;
    private MediaStream localStream;

    // Firebase
    private DatabaseReference dbRef;
    private String username;
    private String target;
    private final Gson gson = new Gson();
    private static final String LATEST_EVENT = "latest_event";

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

        // Initialize WebRTC components
        initWebRTC();

        // Set up click listeners
        setupButtonListeners();
    }

    private void initViews() {
        loginLayout = findViewById(R.id.loginLayout);
        callControlLayout = findViewById(R.id.callControlLayout);
        incomingCallLayout = findViewById(R.id.incomingCallLayout);
        targetUserLayout = findViewById(R.id.targetUserLayout);

        usernameInput = findViewById(R.id.usernameInput);
        targetUserInput = findViewById(R.id.targetUserInput);
        incomingCallText = findViewById(R.id.incomingCallText);

        localView = findViewById(R.id.localView);
        remoteView = findViewById(R.id.remoteView);

        micButton = findViewById(R.id.micButton);
        videoButton = findViewById(R.id.videoButton);
        endCallButton = findViewById(R.id.endCallButton);
        switchCameraButton = findViewById(R.id.switchCameraButton);

        // Initially only show login layout
        loginLayout.setVisibility(View.VISIBLE);
        callControlLayout.setVisibility(View.GONE);
        incomingCallLayout.setVisibility(View.GONE);
        targetUserLayout.setVisibility(View.GONE);
    }

    private void initWebRTC() {
        // Create EGL context
        EglBase eglBase = EglBase.create();
        eglBaseContext = eglBase.getEglBaseContext();

        // Initialize surface renderers
        localView.init(eglBaseContext, null);
        localView.setEnableHardwareScaler(true);
        localView.setMirror(true);

        remoteView.init(eglBaseContext, null);
        remoteView.setEnableHardwareScaler(true);
        remoteView.setMirror(false);

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
                .setVideoEncoderFactory(new DefaultVideoEncoderFactory(eglBaseContext, true, true))
                .setVideoDecoderFactory(new DefaultVideoDecoderFactory(eglBaseContext))
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

        // Call button click
        findViewById(R.id.callButton).setOnClickListener(v -> {
            String targetUser = targetUserInput.getText().toString().trim();
            if (targetUser.isEmpty()) {
                Toast.makeText(this, "Please enter target username", Toast.LENGTH_SHORT).show();
                return;
            }

            sendCallRequest(targetUser);
        });

        // Accept call button click
        findViewById(R.id.acceptButton).setOnClickListener(v -> {
            startCall();
            incomingCallLayout.setVisibility(View.GONE);
        });

        // Reject call button click
        findViewById(R.id.rejectButton).setOnClickListener(v -> {
            incomingCallLayout.setVisibility(View.GONE);
        });

        // End call button click
        endCallButton.setOnClickListener(v -> {
            endCall();
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

        dbRef.child(username).setValue("").addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                // Show call interface
                loginLayout.setVisibility(View.GONE);
                targetUserLayout.setVisibility(View.VISIBLE);

                // Start local stream
                initLocalStream();

                // Listen for incoming calls
                listenForCalls();
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
                "CaptureThread", eglBaseContext);

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
                                mediaStream.videoTracks.get(0).addSink(remoteView);
                            }
                        });
                    }

                    @Override
                    public void onConnectionChange(PeerConnection.PeerConnectionState newState) {
                        runOnUiThread(() -> {
                            if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                                // Call connected
                                targetUserLayout.setVisibility(View.GONE);
                                callControlLayout.setVisibility(View.VISIBLE);
                            } else if (newState == PeerConnection.PeerConnectionState.DISCONNECTED ||
                                    newState == PeerConnection.PeerConnectionState.CLOSED) {
                                // Call ended
                                resetToTargetUserView();
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
                });

        // Add local stream to connection
        peerConnection.addStream(localStream);
    }

    private void listenForCalls() {
        dbRef.child(username).child(LATEST_EVENT).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    try {
                        String data = snapshot.getValue().toString();
                        SignalingData signalingData = gson.fromJson(data, SignalingData.class);

                        if (signalingData != null) {
                            handleSignalingData(signalingData);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(MainActivity.this, "Database error", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void handleSignalingData(SignalingData data) {
        switch (data.type) {
            case "START_CALL":
                target = data.sender;
                runOnUiThread(() -> {
                    incomingCallText.setText(data.sender + " is calling you");
                    incomingCallLayout.setVisibility(View.VISIBLE);
                });
                break;

            case "OFFER":
                target = data.sender;
                if (peerConnection == null) {
                    createPeerConnection();
                }

                SessionDescription offer = new SessionDescription(
                        SessionDescription.Type.OFFER, data.data);

                peerConnection.setRemoteDescription(new SimpleSdpObserver(), offer);
                createAnswer();
                break;

            case "ANSWER":
                SessionDescription answer = new SessionDescription(
                        SessionDescription.Type.ANSWER, data.data);
                peerConnection.setRemoteDescription(new SimpleSdpObserver(), answer);
                break;

            case "ICE_CANDIDATE":
                try {
                    IceCandidate candidate = gson.fromJson(data.data, IceCandidate.class);
                    if (peerConnection != null) {
                        peerConnection.addIceCandidate(candidate);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
        }
    }

    private void sendCallRequest(String targetUser) {
        target = targetUser;

        // Check if target user exists
        dbRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (snapshot.child(targetUser).exists()) {
                    // Create peer connection
                    createPeerConnection();

                    // Send call request
                    SignalingData callRequest = new SignalingData();
                    callRequest.type = "START_CALL";
                    callRequest.sender = username;
                    callRequest.target = targetUser;

                    dbRef.child(targetUser).child(LATEST_EVENT)
                            .setValue(gson.toJson(callRequest));

                    // Start the call (create offer)
                    createOffer();
                } else {
                    Toast.makeText(MainActivity.this,
                            "User not found", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(MainActivity.this,
                        "Failed to check user", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startCall() {
        // Create peer connection
        createPeerConnection();

        // Create answer (handled when offer is received)
    }

    private void createOffer() {
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
                offerData.target = target;
                offerData.data = sessionDescription.description;

                dbRef.child(target).child(LATEST_EVENT).setValue(gson.toJson(offerData));
            }
        }, constraints);
    }

    private void createAnswer() {
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
                answerData.target = target;
                answerData.data = sessionDescription.description;

                dbRef.child(target).child(LATEST_EVENT).setValue(gson.toJson(answerData));

                runOnUiThread(() -> {
                    targetUserLayout.setVisibility(View.GONE);
                    callControlLayout.setVisibility(View.VISIBLE);
                });
            }
        }, constraints);
    }

    private void sendIceCandidate(IceCandidate iceCandidate) {
        SignalingData candidateData = new SignalingData();
        candidateData.type = "ICE_CANDIDATE";
        candidateData.sender = username;
        candidateData.target = target;
        candidateData.data = gson.toJson(iceCandidate);

        dbRef.child(target).child(LATEST_EVENT).setValue(gson.toJson(candidateData));
    }

    private void endCall() {
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }

        resetToTargetUserView();
    }

    private void resetToTargetUserView() {
        callControlLayout.setVisibility(View.GONE);
        targetUserLayout.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onDestroy() {
        if (videoCapturer != null) {
            try {
                videoCapturer.stopCapture();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            videoCapturer.dispose();
        }

        if (peerConnection != null) {
            peerConnection.close();
        }

        if (localView != null) {
            localView.release();
        }

        if (remoteView != null) {
            remoteView.release();
        }

        super.onDestroy();
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
        String type;      // START_CALL, OFFER, ANSWER, ICE_CANDIDATE
        String sender;    // Username of sender
        String target;    // Username of target
        String data;      // SDP or ICE candidate data
    }
}