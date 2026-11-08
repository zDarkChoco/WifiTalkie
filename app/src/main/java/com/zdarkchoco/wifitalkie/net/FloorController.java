package com.zdarkchoco.wifitalkie.net;

/**
 * Deterministic Half-Duplex Walkie-Talkie Floor Control State Machine.
 *
 * Guarantees:
 * 1. Push-To-Talk method only (user must hold PTT to acquire floor and release to free floor).
 * 2. Half-duplex collision prevention: when a remote user on the same channel is talking,
 *    local PTT is locked out and the local user must wait for the speaker to finish.
 * 3. Deterministic tie-breaking if two users press PTT within the ~125ms pre-beep window:
 *    earlier timestamp (or lexicographically smaller deviceId on exact millisecond tie) wins.
 * 4. Watchdog auto-release if a remote speaker's UDP release packet is dropped.
 */
public final class FloorController {

    public enum State {
        IDLE,
        TX_PRE_BEEP,
        TRANSMITTING,
        RECEIVING
    }

    public interface Listener {
        void onStateChanged(State newState, String activeSpeakerId, String activeSpeakerCallsign);
        void onPlayTxStartBeep();
        void onStartMicStreaming();
        void onStopMicStreaming();
        void onPlayRxStartBeep(String speakerCallsign);
        void onPlayRogerBeep();
        void onPttDeniedChannelBusy(String currentSpeakerCallsign);
    }

    public static final long RX_WATCHDOG_TIMEOUT_MS = 700L;

    private final String localDeviceId;
    private final Listener listener;

    private State state = State.IDLE;
    private String activeSpeakerId = null;
    private String activeSpeakerCallsign = null;
    private long activeClaimTimestampMs = 0L;
    private long lastRxPacketTimeMs = 0L;

    public FloorController(String localDeviceId, Listener listener) {
        this.localDeviceId = localDeviceId;
        this.listener = listener;
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized String getActiveSpeakerId() {
        return activeSpeakerId;
    }

    public synchronized String getActiveSpeakerCallsign() {
        return activeSpeakerCallsign;
    }

    public synchronized long getActiveClaimTimestampMs() {
        return activeClaimTimestampMs;
    }

    public synchronized byte getLocalWireState() {
        switch (state) {
            case TX_PRE_BEEP:
                return PeerInfo.STATE_PRE_BEEP;
            case TRANSMITTING:
                return PeerInfo.STATE_TRANSMITTING;
            case RECEIVING:
                return PeerInfo.STATE_RECEIVING;
            default:
                return PeerInfo.STATE_IDLE;
        }
    }

    /**
     * Called when the local user presses and holds the Push-To-Talk button.
     * Returns true if the channel is clear and pre-talk beep sequence begins,
     * or false if another user is currently talking on this channel.
     */
    public boolean requestLocalPttStart(String localCallsign, long nowMs) {
        String busySpeaker = null;
        synchronized (this) {
            if (state == State.RECEIVING) {
                busySpeaker = activeSpeakerCallsign != null ? activeSpeakerCallsign : "Peer";
            } else if (state == State.TX_PRE_BEEP || state == State.TRANSMITTING) {
                return true;
            } else {
                state = State.TX_PRE_BEEP;
                activeSpeakerId = localDeviceId;
                activeSpeakerCallsign = localCallsign;
                activeClaimTimestampMs = nowMs;
            }
        }

        if (busySpeaker != null) {
            listener.onPttDeniedChannelBusy(busySpeaker);
            return false;
        }

        listener.onStateChanged(State.TX_PRE_BEEP, localDeviceId, localCallsign);
        listener.onPlayTxStartBeep();
        return true;
    }

    /**
     * Called once the local pre-talk beep finishes (~125ms) and user is still holding PTT.
     */
    public void confirmLocalMicLive(String localCallsign) {
        synchronized (this) {
            if (state != State.TX_PRE_BEEP) {
                return;
            }
            state = State.TRANSMITTING;
            activeSpeakerId = localDeviceId;
            activeSpeakerCallsign = localCallsign;
        }
        listener.onStateChanged(State.TRANSMITTING, localDeviceId, localCallsign);
        listener.onStartMicStreaming();
    }

    /**
     * Called when the local user releases the Push-To-Talk button.
     */
    public boolean releaseLocalPtt() {
        boolean wasTransmittingOrPreBeep = false;
        synchronized (this) {
            if (state == State.TX_PRE_BEEP || state == State.TRANSMITTING) {
                wasTransmittingOrPreBeep = true;
                state = State.IDLE;
                activeSpeakerId = null;
                activeSpeakerCallsign = null;
                activeClaimTimestampMs = 0L;
            }
        }
        if (wasTransmittingOrPreBeep) {
            listener.onStopMicStreaming();
            listener.onPlayRogerBeep();
            listener.onStateChanged(State.IDLE, null, null);
        }
        return wasTransmittingOrPreBeep;
    }

    /**
     * Called when a remote peer on the SAME channel sends PTT_REQUEST or AUDIO_PCM.
     * Returns true if incoming audio should be accepted & played.
     */
    public boolean onRemoteTransmissionPacket(WalkiePacket packet, long nowMs) {
        if (packet == null || localDeviceId.equals(packet.senderId)) {
            return false;
        }

        boolean switchedFromPreBeep = false;
        boolean newlyEnteredReceiving = false;
        String speakerCallsign = packet.callsign;

        synchronized (this) {
            if (state == State.IDLE) {
                state = State.RECEIVING;
                activeSpeakerId = packet.senderId;
                activeSpeakerCallsign = packet.callsign;
                activeClaimTimestampMs = packet.timestampMs;
                lastRxPacketTimeMs = nowMs;
                newlyEnteredReceiving = true;
            } else if (state == State.RECEIVING) {
                if (activeSpeakerId == null || activeSpeakerId.equals(packet.senderId)) {
                    activeSpeakerId = packet.senderId;
                    activeSpeakerCallsign = packet.callsign;
                    lastRxPacketTimeMs = nowMs;
                } else {
                    // Another remote peer collided; keep whoever claimed earlier
                    if (winsTieBreaker(
                            packet.timestampMs,
                            packet.senderId,
                            activeClaimTimestampMs,
                            activeSpeakerId)) {
                        activeSpeakerId = packet.senderId;
                        activeSpeakerCallsign = packet.callsign;
                        activeClaimTimestampMs = packet.timestampMs;
                        lastRxPacketTimeMs = nowMs;
                    } else {
                        return false;
                    }
                }
            } else if (state == State.TX_PRE_BEEP) {
                // Simultaneous PTT press tie-breaker
                if (winsTieBreaker(
                        packet.timestampMs,
                        packet.senderId,
                        activeClaimTimestampMs,
                        localDeviceId)) {
                    state = State.RECEIVING;
                    activeSpeakerId = packet.senderId;
                    activeSpeakerCallsign = packet.callsign;
                    activeClaimTimestampMs = packet.timestampMs;
                    lastRxPacketTimeMs = nowMs;
                    switchedFromPreBeep = true;
                    newlyEnteredReceiving = true;
                } else {
                    return false;
                }
            } else if (state == State.TRANSMITTING) {
                // Local user already owns the floor
                return false;
            }
        }

        if (switchedFromPreBeep) {
            listener.onStopMicStreaming();
        }
        if (newlyEnteredReceiving) {
            listener.onPlayRxStartBeep(speakerCallsign);
            listener.onStateChanged(State.RECEIVING, packet.senderId, speakerCallsign);
        }
        return true;
    }

    /**
     * Called when a remote peer on the SAME channel sends PTT_RELEASE.
     */
    public void onRemoteReleasePacket(String senderId) {
        boolean finishedReceiving = false;
        synchronized (this) {
            if (state == State.RECEIVING
                    && (activeSpeakerId == null || activeSpeakerId.equals(senderId))) {
                state = State.IDLE;
                activeSpeakerId = null;
                activeSpeakerCallsign = null;
                activeClaimTimestampMs = 0L;
                lastRxPacketTimeMs = 0L;
                finishedReceiving = true;
            }
        }
        if (finishedReceiving) {
            listener.onPlayRogerBeep();
            listener.onStateChanged(State.IDLE, null, null);
        }
    }

    /**
     * Called periodically (e.g. every 150ms) to release RX lock if remote peer dropped off Wi-Fi
     * or UDP release packets were lost.
     */
    public void checkWatchdog(long nowMs) {
        boolean timedOut = false;
        synchronized (this) {
            if (state == State.RECEIVING
                    && lastRxPacketTimeMs > 0L
                    && (nowMs - lastRxPacketTimeMs) > RX_WATCHDOG_TIMEOUT_MS) {
                state = State.IDLE;
                activeSpeakerId = null;
                activeSpeakerCallsign = null;
                activeClaimTimestampMs = 0L;
                lastRxPacketTimeMs = 0L;
                timedOut = true;
            }
        }
        if (timedOut) {
            listener.onPlayRogerBeep();
            listener.onStateChanged(State.IDLE, null, null);
        }
    }

    /**
     * Resets the floor state when switching to a new channel.
     */
    public void resetForChannelSwitch() {
        boolean stopMic = false;
        synchronized (this) {
            if (state == State.TX_PRE_BEEP || state == State.TRANSMITTING) {
                stopMic = true;
            }
            state = State.IDLE;
            activeSpeakerId = null;
            activeSpeakerCallsign = null;
            activeClaimTimestampMs = 0L;
            lastRxPacketTimeMs = 0L;
        }
        if (stopMic) {
            listener.onStopMicStreaming();
        }
        listener.onStateChanged(State.IDLE, null, null);
    }

    private static boolean winsTieBreaker(
            long candidateTime, String candidateId, long currentTime, String currentId) {
        if (candidateTime != currentTime) {
            return candidateTime < currentTime;
        }
        if (candidateId == null) return false;
        if (currentId == null) return true;
        return candidateId.compareTo(currentId) < 0;
    }
}
