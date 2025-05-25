package com.example.camera.classes;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

public class Speaker {

    private final AudioTrack audioTrack;
    private final int bufferSize;
    private boolean isPlaying = false;

    public Speaker(int sampleRate) {
        this.bufferSize = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        this.audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, // use MUSIC for general sounds
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
                AudioTrack.MODE_STREAM);
    }

    public void start() {
        if (!isPlaying) {
            isPlaying = true;
            audioTrack.play();
        }
    }

    public void stop() {
        if (isPlaying) {
            isPlaying = false;
            audioTrack.stop();
            audioTrack.release();
        }
    }

    public void playAudio(byte[] audioData) {
        if (isPlaying && audioData != null && audioData.length > 0) {
            audioTrack.write(audioData, 0, audioData.length);
        }
    }

}
