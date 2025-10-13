package com.example.camera.classes;

import android.Manifest;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;

import androidx.annotation.RequiresPermission;

import java.util.Arrays;
import java.util.function.Consumer;

public class Microphone {

    private final AudioRecord audioRecord;
    private final int bufferSize;
    private boolean isRecording = false;
    private Thread recordingThread;
    private final Consumer<byte[]> audioConsumer;
    private AcousticEchoCanceler _echoCanceler;

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public Microphone(int sampleRate, Consumer<byte[]> consumer) {
        this.bufferSize = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT) * 2;

        this.audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION ,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        this.audioConsumer = consumer;

        // Echo Canceler
        if (AcousticEchoCanceler.isAvailable()) {
            _echoCanceler = AcousticEchoCanceler.create(audioRecord.getAudioSessionId());
            if (_echoCanceler != null) {
                _echoCanceler.setEnabled(true);
            }
        }
    }

    public void start() {
        if (isRecording) return;
        isRecording = true;
        audioRecord.startRecording();

        recordingThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            while (isRecording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read > 0) {
                    audioConsumer.accept(Arrays.copyOf(buffer, read));
                }
            }
        });
        recordingThread.setDaemon(true);
        recordingThread.start();
    }

    public void stop() {
        isRecording = false;
        if (recordingThread != null) {
            try {
                recordingThread.join();
            } catch (InterruptedException ignored) {}
        }
        audioRecord.stop();
        audioRecord.release();
    }
}
