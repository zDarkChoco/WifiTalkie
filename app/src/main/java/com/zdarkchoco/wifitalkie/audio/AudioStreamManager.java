package com.zdarkchoco.wifitalkie.audio;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Low-latency 16kHz 16-bit Mono PCM Audio Capture & Playback Engine.
 *
 * Frame geometry:
 * - Sample rate: 16,000 Hz
 * - Frame duration: 20 ms (320 samples = 640 bytes per UDP packet)
 */
public final class AudioStreamManager {
    public static final int SAMPLE_RATE = 16000;
    public static final int SAMPLES_PER_FRAME = 320; // 20ms at 16kHz
    public static final int BYTES_PER_FRAME = SAMPLES_PER_FRAME * 2; // 640 bytes

    public interface FrameCallback {
        void onMicAudioFrame(byte[] pcmBytes, int rmsLevel);
        void onPlaybackAudioLevel(int rmsLevel);
    }

    private final Context context;
    private final FrameCallback callback;
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final AtomicBoolean playbackRunning = new AtomicBoolean(false);
    private final ArrayBlockingQueue<byte[]> jitterQueue = new ArrayBlockingQueue<>(14);

    private Thread captureThread;
    private Thread playbackThread;
    private volatile boolean speakerphoneOn = true;
    private volatile boolean holdPlaybackForBeep = false;

    public AudioStreamManager(Context context, FrameCallback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    public void startPlaybackEngine() {
        if (playbackRunning.getAndSet(true)) {
            return;
        }
        applyAudioRouting(speakerphoneOn);
        playbackThread = new Thread(this::runPlaybackLoop, "WifiTalkie-AudioRx");
        playbackThread.setDaemon(true);
        playbackThread.start();
    }

    public void stopPlaybackEngine() {
        playbackRunning.set(false);
        jitterQueue.clear();
        if (playbackThread != null) {
            playbackThread.interrupt();
            playbackThread = null;
        }
    }

    public void setSpeakerphoneEnabled(boolean enabled) {
        this.speakerphoneOn = enabled;
        applyAudioRouting(enabled);
    }

    public boolean isSpeakerphoneEnabled() {
        return speakerphoneOn;
    }

    private void applyAudioRouting(boolean speakerphone) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(speakerphone);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Temporarily holds incoming voice frames in the jitter buffer for ~100ms while the
     * incoming Pre-Hear Beep plays so the beep is never drowned out by voice.
     */
    public void setHoldPlaybackForBeep(boolean hold) {
        this.holdPlaybackForBeep = hold;
    }

    public void clearPlaybackQueue() {
        jitterQueue.clear();
    }

    public void enqueueIncomingPcmFrame(byte[] pcmBytes) {
        if (pcmBytes == null || pcmBytes.length == 0 || !playbackRunning.get()) {
            return;
        }
        if (!jitterQueue.offer(pcmBytes)) {
            // Drop oldest frame to keep latency low (< 240ms)
            jitterQueue.poll();
            jitterQueue.offer(pcmBytes);
        }
    }

    public void startMicCapture() {
        if (capturing.getAndSet(true)) {
            return;
        }
        captureThread = new Thread(this::runCaptureLoop, "WifiTalkie-AudioTx");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    public void stopMicCapture() {
        capturing.set(false);
        if (captureThread != null) {
            captureThread.interrupt();
            captureThread = null;
        }
    }

    private void runCaptureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioRecord record = null;
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        AutomaticGainControl agc = null;

        try {
            int minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(minBuf * 2, BYTES_PER_FRAME * 4);

            try {
                record = new AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);
            } catch (Throwable t) {
                record = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);
            }

            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                capturing.set(false);
                return;
            }

            int sessionId = record.getAudioSessionId();
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(sessionId);
                    if (aec != null) aec.setEnabled(true);
                }
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(sessionId);
                    if (ns != null) ns.setEnabled(true);
                }
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(sessionId);
                    if (agc != null) agc.setEnabled(true);
                }
            } catch (Throwable ignored) {
            }

            record.startRecording();
            byte[] frameBuf = new byte[BYTES_PER_FRAME];

            while (capturing.get() && !Thread.currentThread().isInterrupted()) {
                int readTotal = 0;
                while (readTotal < BYTES_PER_FRAME && capturing.get()) {
                    int r = record.read(frameBuf, readTotal, BYTES_PER_FRAME - readTotal);
                    if (r <= 0) {
                        break;
                    }
                    readTotal += r;
                }
                if (readTotal == BYTES_PER_FRAME && capturing.get()) {
                    byte[] packetCopy = new byte[BYTES_PER_FRAME];
                    System.arraycopy(frameBuf, 0, packetCopy, 0, BYTES_PER_FRAME);
                    int rms = computeRmsLevel(packetCopy);
                    callback.onMicAudioFrame(packetCopy, rms);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (aec != null) {
                try { aec.release(); } catch (Throwable ignored) {}
            }
            if (ns != null) {
                try { ns.release(); } catch (Throwable ignored) {}
            }
            if (agc != null) {
                try { agc.release(); } catch (Throwable ignored) {}
            }
            if (record != null) {
                try { record.stop(); } catch (Throwable ignored) {}
                try { record.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private void runPlaybackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        AudioTrack track = null;
        try {
            int minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(minBuf, BYTES_PER_FRAME * 4);

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();

            track.play();

            while (playbackRunning.get() && !Thread.currentThread().isInterrupted()) {
                if (holdPlaybackForBeep) {
                    Thread.sleep(15L);
                    continue;
                }
                byte[] frame = jitterQueue.poll(60L, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    track.write(frame, 0, frame.length);
                    int rms = computeRmsLevel(frame);
                    callback.onPlaybackAudioLevel(rms);
                } else {
                    callback.onPlaybackAudioLevel(0);
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {
        } finally {
            if (track != null) {
                try { track.stop(); } catch (Throwable ignored) {}
                try { track.release(); } catch (Throwable ignored) {}
            }
        }
    }

    public static int computeRmsLevel(byte[] pcm16LittleEndian) {
        if (pcm16LittleEndian == null || pcm16LittleEndian.length < 2) {
            return 0;
        }
        int samples = pcm16LittleEndian.length / 2;
        long sumSquares = 0L;
        for (int i = 0; i < samples * 2; i += 2) {
            int lo = pcm16LittleEndian[i] & 0xFF;
            int hi = pcm16LittleEndian[i + 1]; // sign-extended
            short sample = (short) ((hi << 8) | lo);
            sumSquares += (long) sample * sample;
        }
        double rms = Math.sqrt((double) sumSquares / samples);
        // Normalize typical speech RMS (0..10000) into 0..1000 UI scale
        int scaled = (int) Math.min(1000.0, (rms / 8500.0) * 1000.0);
        return Math.max(0, scaled);
    }
}
