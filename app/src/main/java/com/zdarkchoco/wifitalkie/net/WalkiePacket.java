package com.zdarkchoco.wifitalkie.net;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Compact binary UDP packet format for WifiTalkie local Wi-Fi communication.
 *
 * Wire format (Big-Endian):
 * - [0..3]   Magic bytes "WFTK" (0x57, 0x46, 0x54, 0x4B)
 * - [4]      Protocol Version (1)
 * - [5]      Packet Type (HELLO, PTT_REQUEST, PTT_RELEASE, AUDIO_PCM, PTT_BUSY_DENY, PING_BEEP)
 * - [6]      Channel (1..16)
 * - [7]      Sender State (0=IDLE, 1=PRE_BEEP, 2=TRANSMITTING, 3=RECEIVING)
 * - [8..11]  Sequence Number (int32)
 * - [12..19] Timestamp / Floor Claim Epoch (int64)
 * - [20..21] Audio RMS Level 0..1000 (int16)
 * - [22]     SenderId UTF-8 byte length (uint8)
 * - [..]     SenderId UTF-8 bytes
 * - [next]   Callsign UTF-8 byte length (uint8)
 * - [..]     Callsign UTF-8 bytes
 * - [next..next+1] Payload byte length (uint16)
 * - [..]     Payload bytes (e.g. 640 bytes of 16kHz 16-bit mono PCM audio)
 */
public final class WalkiePacket {
    public static final byte[] MAGIC = new byte[] {'W', 'F', 'T', 'K'};
    public static final byte VERSION = 1;

    public static final byte TYPE_HELLO = 1;
    public static final byte TYPE_PTT_REQUEST = 2;
    public static final byte TYPE_PTT_RELEASE = 3;
    public static final byte TYPE_AUDIO_PCM = 4;
    public static final byte TYPE_PTT_BUSY_DENY = 5;
    public static final byte TYPE_PING_BEEP = 6;

    public static final int MIN_CHANNEL = 1;
    public static final int MAX_CHANNEL = 16;
    public static final int MAX_PACKET_SIZE = 1440;

    private static final String[] CHANNEL_NAMES = new String[] {
        "MAIN NET",
        "TACTICAL",
        "ALPHA OPS",
        "BRAVO SQUAD",
        "CHARLIE LINK",
        "DELTA RECON",
        "ECHO RELAY",
        "FOXTROT BASE",
        "GUARDIAN",
        "HORIZON",
        "INTERCEPT",
        "JAVELIN",
        "KILO WATCH",
        "LIMA PATROL",
        "METRO SYNC",
        "NIGHT OWL"
    };

    public final byte type;
    public final int channel;
    public final byte senderState;
    public final int sequence;
    public final long timestampMs;
    public final int audioLevel;
    public final String senderId;
    public final String callsign;
    public final byte[] payload;

    public WalkiePacket(
            byte type,
            int channel,
            byte senderState,
            int sequence,
            long timestampMs,
            int audioLevel,
            String senderId,
            String callsign,
            byte[] payload) {
        this.type = type;
        this.channel = clampChannel(channel);
        this.senderState = senderState;
        this.sequence = sequence;
        this.timestampMs = timestampMs;
        this.audioLevel = Math.max(0, Math.min(1000, audioLevel));
        this.senderId = senderId != null ? senderId : "UNKNOWN";
        this.callsign = callsign != null ? callsign : "Unit";
        this.payload = payload != null ? payload : new byte[0];
    }

    public static int clampChannel(int ch) {
        if (ch < MIN_CHANNEL) return MIN_CHANNEL;
        if (ch > MAX_CHANNEL) return MAX_CHANNEL;
        return ch;
    }

    public static String getChannelCode(int channel) {
        int ch = clampChannel(channel);
        return (ch < 10 ? "CH 0" : "CH ") + ch;
    }

    public static String getChannelName(int channel) {
        int idx = clampChannel(channel) - 1;
        return CHANNEL_NAMES[idx];
    }

    public static String getChannelFrequencyMhz(int channel) {
        int idx = clampChannel(channel) - 1;
        // Standard PMR446 12.5 kHz spacing starting at 446.00625 MHz
        double freq = 446.00625 + (idx * 0.0125);
        return String.format(java.util.Locale.US, "%.4f MHz", freq);
    }

    public byte[] toBytes() {
        byte[] idBytes = truncateUtf8(senderId, 32);
        byte[] callBytes = truncateUtf8(callsign, 32);
        int payloadLen = Math.min(payload.length, 1280);
        int totalSize = 22 + 1 + idBytes.length + 1 + callBytes.length + 2 + payloadLen;

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.put(MAGIC);
        buf.put(VERSION);
        buf.put(type);
        buf.put((byte) channel);
        buf.put(senderState);
        buf.putInt(sequence);
        buf.putLong(timestampMs);
        buf.putShort((short) audioLevel);

        buf.put((byte) idBytes.length);
        buf.put(idBytes);

        buf.put((byte) callBytes.length);
        buf.put(callBytes);

        buf.putShort((short) payloadLen);
        if (payloadLen > 0) {
            buf.put(payload, 0, payloadLen);
        }
        return buf.array();
    }

    public static WalkiePacket fromBytes(byte[] data, int length) {
        if (data == null || length < 26) {
            return null;
        }
        if (data[0] != MAGIC[0]
                || data[1] != MAGIC[1]
                || data[2] != MAGIC[2]
                || data[3] != MAGIC[3]) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.wrap(data, 0, length);
        buf.position(4);
        byte version = buf.get();
        if (version != VERSION) {
            return null;
        }
        byte type = buf.get();
        int channel = buf.get() & 0xFF;
        byte senderState = buf.get();
        int sequence = buf.getInt();
        long timestampMs = buf.getLong();
        int audioLevel = buf.getShort() & 0xFFFF;

        int idLen = buf.get() & 0xFF;
        if (idLen <= 0 || idLen > 64 || buf.remaining() < idLen + 3) {
            return null;
        }
        byte[] idBytes = new byte[idLen];
        buf.get(idBytes);
        String senderId = new String(idBytes, StandardCharsets.UTF_8);

        int callLen = buf.get() & 0xFF;
        if (callLen < 0 || callLen > 64 || buf.remaining() < callLen + 2) {
            return null;
        }
        byte[] callBytes = new byte[callLen];
        buf.get(callBytes);
        String callsign = new String(callBytes, StandardCharsets.UTF_8);

        int payloadLen = buf.getShort() & 0xFFFF;
        if (payloadLen < 0 || buf.remaining() < payloadLen) {
            return null;
        }
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            buf.get(payload);
        }

        return new WalkiePacket(
                type,
                channel,
                senderState,
                sequence,
                timestampMs,
                audioLevel,
                senderId,
                callsign,
                payload);
    }

    private static byte[] truncateUtf8(String input, int maxBytes) {
        byte[] raw = input.getBytes(StandardCharsets.UTF_8);
        if (raw.length <= maxBytes) {
            return raw;
        }
        byte[] cut = new byte[maxBytes];
        System.arraycopy(raw, 0, cut, 0, maxBytes);
        return cut;
    }
}
