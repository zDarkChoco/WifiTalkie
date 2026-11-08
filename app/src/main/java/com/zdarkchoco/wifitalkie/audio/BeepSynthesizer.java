package com.zdarkchoco.wifitalkie.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Synthesizes authentic, zero-click 16kHz 16-bit PCM Walkie-Talkie tones:
 * - Pre-Talk Start Beep (chirp played BEFORE user starts talking)
 * - Pre-Hear Incoming Beep (chirp played BEFORE user hears remote peer)
 * - End-of-Transmission Roger Beep
 * - Channel Busy / Wait Lockout Tone
 * - Channel Switch Click & Call Alert Warble
 */
public final class BeepSynthesizer {
    private static final int SAMPLE_RATE = 16000;

    private final ExecutorService toneExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean beepEnabled = true;

    private final short[] txStartPcm;
    private final short[] rxStartPcm;
    private final short[] rogerBeepPcm;
    private final short[] busyDenyPcm;
    private final short[] channelTickPcm;
    private final short[] callAlertPcm;

    public BeepSynthesizer() {
        // 1. Pre-Talk Start Beep: crisp dual-rising walkie-talkie chirp (1200Hz -> 1680Hz, 120ms)
        this.txStartPcm = concat(
                generateTone(1200.0, 42, 0.58),
                generateSilence(12),
                generateTone(1680.0, 66, 0.65));

        // 2. Pre-Hear Incoming Beep: distinct clear alert before voice starts (1060Hz -> 1480Hz, 100ms)
        this.rxStartPcm = concat(
                generateTone(1060.0, 38, 0.54),
                generateSilence(10),
                generateTone(1480.0, 52, 0.60));

        // 3. Roger Beep: classic walkie-talkie end-of-over double pip (1568Hz -> 1318Hz, 95ms)
        this.rogerBeepPcm = concat(
                generateTone(1568.0, 42, 0.45),
                generateSilence(8),
                generateTone(1318.0, 48, 0.42));

        // 4. Busy / Wait Lockout Tone: low double bonk when channel is occupied (380Hz -> 300Hz)
        this.busyDenyPcm = concat(
                generateTone(380.0, 55, 0.55),
                generateSilence(22),
                generateTone(300.0, 75, 0.55));

        // 5. Channel Switch Click: short liquid glass blip (940Hz, 26ms)
        this.channelTickPcm = generateTone(940.0, 26, 0.32);

        // 6. Incoming Call Alert Warble
        this.callAlertPcm = concat(
                generateTone(1420.0, 60, 0.58),
                generateTone(1820.0, 60, 0.58),
                generateTone(1420.0, 60, 0.58),
                generateTone(1820.0, 75, 0.60));
    }

    public void setBeepEnabled(boolean enabled) {
        this.beepEnabled = enabled;
    }

    public boolean isBeepEnabled() {
        return beepEnabled;
    }

    /**
     * Plays the Pre-Talk Start Beep synchronously (blocks ~120ms) so the user hears the beep
     * right before the microphone begins transmitting.
     */
    public void playTxStartBeepBlocking() {
        if (!beepEnabled) return;
        playPcmBlocking(txStartPcm, 125L);
    }

    /**
     * Plays the Pre-Hear Incoming Beep synchronously (blocks ~100ms) right before the incoming
     * peer's voice frames begin playing.
     */
    public void playRxStartBeepBlocking() {
        if (!beepEnabled) return;
        playPcmBlocking(rxStartPcm, 105L);
    }

    public void playRogerBeepAsync() {
        if (!beepEnabled) return;
        toneExecutor.execute(() -> playPcmBlocking(rogerBeepPcm, 105L));
    }

    public void playBusyDenyBeepAsync() {
        toneExecutor.execute(() -> playPcmBlocking(busyDenyPcm, 160L));
    }

    public void playChannelSwitchBeepAsync() {
        if (!beepEnabled) return;
        toneExecutor.execute(() -> playPcmBlocking(channelTickPcm, 35L));
    }

    public void playCallAlertBeepAsync() {
        toneExecutor.execute(() -> playPcmBlocking(callAlertPcm, 270L));
    }

    public void shutdown() {
        toneExecutor.shutdownNow();
    }

    private void playPcmBlocking(short[] samples, long sleepMs) {
        if (samples == null || samples.length == 0) return;
        AudioTrack track = null;
        try {
            int byteSize = samples.length * 2;
            int minBuf = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(byteSize, minBuf);

            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(bufferSize)
                    .build();

            track.write(samples, 0, samples.length);
            track.play();
            Thread.sleep(sleepMs);
        } catch (Throwable ignored) {
            // Audio device unavailable or interrupted
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Throwable ignored) {
                }
                try {
                    track.release();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static short[] generateTone(double freqHz, int durationMs, double amplitude) {
        int numSamples = Math.max(1, (SAMPLE_RATE * durationMs) / 1000);
        short[] out = new short[numSamples];
        int rampSamples = Math.min(numSamples / 4, (SAMPLE_RATE * 6) / 1000);
        for (int i = 0; i < numSamples; i++) {
            double env = 1.0;
            if (rampSamples > 0) {
                if (i < rampSamples) {
                    env = 0.5 * (1.0 - Math.cos(Math.PI * i / rampSamples));
                } else if (i >= numSamples - rampSamples) {
                    int rem = numSamples - 1 - i;
                    env = 0.5 * (1.0 - Math.cos(Math.PI * rem / rampSamples));
                }
            }
            double angle = 2.0 * Math.PI * freqHz * i / SAMPLE_RATE;
            // Subtle 2nd harmonic gives authentic radio transceiver timbre
            double wave = 0.88 * Math.sin(angle) + 0.12 * Math.sin(2.0 * angle);
            double val = wave * amplitude * env * 32767.0;
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, (int) val));
        }
        return out;
    }

    private static short[] generateSilence(int durationMs) {
        int numSamples = Math.max(1, (SAMPLE_RATE * durationMs) / 1000);
        return new short[numSamples];
    }

    private static short[] concat(short[]... arrays) {
        int total = 0;
        for (short[] a : arrays) {
            total += a.length;
        }
        short[] res = new short[total];
        int pos = 0;
        for (short[] a : arrays) {
            System.arraycopy(a, 0, res, pos, a.length);
            pos += a.length;
        }
        return res;
    }
}
