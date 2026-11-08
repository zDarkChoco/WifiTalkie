package com.zdarkchoco.wifitalkie.net;

/**
 * Represents a discovered WifiTalkie peer on the local Wi-Fi network.
 */
public final class PeerInfo {
    public static final byte STATE_IDLE = 0;
    public static final byte STATE_PRE_BEEP = 1;
    public static final byte STATE_TRANSMITTING = 2;
    public static final byte STATE_RECEIVING = 3;

    public static final long PEER_TIMEOUT_MS = 6500L;

    public final String deviceId;
    public volatile String callsign;
    public volatile String ipAddress;
    public volatile int channel;
    public volatile byte state;
    public volatile int audioLevel;
    public volatile long lastSeenMs;

    public PeerInfo(
            String deviceId,
            String callsign,
            String ipAddress,
            int channel,
            byte state,
            int audioLevel,
            long lastSeenMs) {
        this.deviceId = deviceId;
        this.callsign = callsign;
        this.ipAddress = ipAddress;
        this.channel = channel;
        this.state = state;
        this.audioLevel = audioLevel;
        this.lastSeenMs = lastSeenMs;
    }

    public boolean isOnline(long nowMs) {
        return (nowMs - lastSeenMs) <= PEER_TIMEOUT_MS;
    }

    public boolean isTalking() {
        return state == STATE_PRE_BEEP || state == STATE_TRANSMITTING;
    }

    public String getStateLabel() {
        switch (state) {
            case STATE_PRE_BEEP:
            case STATE_TRANSMITTING:
                return "TALKING";
            case STATE_RECEIVING:
                return "LISTENING";
            default:
                return "STANDBY";
        }
    }
}
