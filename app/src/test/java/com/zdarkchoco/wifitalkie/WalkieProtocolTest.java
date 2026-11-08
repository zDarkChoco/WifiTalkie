package com.zdarkchoco.wifitalkie;

import com.zdarkchoco.wifitalkie.audio.AudioStreamManager;
import com.zdarkchoco.wifitalkie.audio.BeepSynthesizer;
import com.zdarkchoco.wifitalkie.net.FloorController;
import com.zdarkchoco.wifitalkie.net.PeerInfo;
import com.zdarkchoco.wifitalkie.net.WalkiePacket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Automated verification test suite for WifiTalkie:
 * - UDP binary protocol serialization/deserialization across 16 channels
 * - Push-To-Talk half-duplex floor locking ("need to wait for others to finish talk")
 * - Pre-talk beep & Pre-hear beep sequencing
 * - Simultaneous PTT tie-breaking & watchdog timeout
 */
public final class WalkieProtocolTest {

    public static void main(String[] args) {
        testPacketSerializationRoundTrip();
        testHalfDuplexWaitForOtherUserToFinish();
        testSimultaneousPttTieBreaker();
        testWatchdogAutoUnlock();
        testBeepSynthesisAndRms();
        System.out.println("ALL WIFITALKIE PROTOCOL & FLOOR CONTROL TESTS PASSED!");
    }

    private static void testPacketSerializationRoundTrip() {
        byte[] sampleAudio = new byte[640];
        for (int i = 0; i < sampleAudio.length; i++) {
            sampleAudio[i] = (byte) (i & 0xFF);
        }

        WalkiePacket original = new WalkiePacket(
                WalkiePacket.TYPE_AUDIO_PCM,
                7,
                PeerInfo.STATE_TRANSMITTING,
                42,
                1700000123456L,
                685,
                "WT-A1B2C3",
                "Alpha-7",
                sampleAudio);

        byte[] wire = original.toBytes();
        WalkiePacket parsed = WalkiePacket.fromBytes(wire, wire.length);
        assertTrue(parsed != null, "Parsed packet must not be null");
        assertTrue(parsed.type == WalkiePacket.TYPE_AUDIO_PCM, "Packet type mismatch");
        assertTrue(parsed.channel == 7, "Channel mismatch");
        assertTrue(parsed.senderState == PeerInfo.STATE_TRANSMITTING, "State mismatch");
        assertTrue(parsed.sequence == 42, "Sequence mismatch");
        assertTrue(parsed.timestampMs == 1700000123456L, "Timestamp mismatch");
        assertTrue(parsed.audioLevel == 685, "Audio level mismatch");
        assertTrue("WT-A1B2C3".equals(parsed.senderId), "Sender ID mismatch");
        assertTrue("Alpha-7".equals(parsed.callsign), "Callsign mismatch");
        assertTrue(parsed.payload.length == 640, "Payload length mismatch");
        assertTrue("CH 07".equals(WalkiePacket.getChannelCode(7)), "Channel code format");
    }

    private static void testHalfDuplexWaitForOtherUserToFinish() {
        AtomicInteger txStartBeeps = new AtomicInteger();
        AtomicInteger rxStartBeeps = new AtomicInteger();
        AtomicInteger rogerBeeps = new AtomicInteger();
        AtomicBoolean micStreaming = new AtomicBoolean(false);
        AtomicReference<String> busyDeniedBy = new AtomicReference<>(null);

        FloorController userB = new FloorController("WT-USER-B", new FloorController.Listener() {
            @Override
            public void onStateChanged(
                    FloorController.State newState,
                    String activeSpeakerId,
                    String activeSpeakerCallsign) {}

            @Override
            public void onPlayTxStartBeep() {
                txStartBeeps.incrementAndGet();
            }

            @Override
            public void onStartMicStreaming() {
                micStreaming.set(true);
            }

            @Override
            public void onStopMicStreaming() {
                micStreaming.set(false);
            }

            @Override
            public void onPlayRxStartBeep(String speakerCallsign) {
                rxStartBeeps.incrementAndGet();
            }

            @Override
            public void onPlayRogerBeep() {
                rogerBeeps.incrementAndGet();
            }

            @Override
            public void onPttDeniedChannelBusy(String currentSpeakerCallsign) {
                busyDeniedBy.set(currentSpeakerCallsign);
            }
        });

        // 1. User A starts talking on Channel 1
        WalkiePacket userAPttStart = new WalkiePacket(
                WalkiePacket.TYPE_PTT_REQUEST,
                1,
                PeerInfo.STATE_PRE_BEEP,
                1,
                1000L,
                0,
                "WT-USER-A",
                "Bravo-1",
                null);

        boolean accepted = userB.onRemoteTransmissionPacket(userAPttStart, 1005L);
        assertTrue(accepted, "User B should accept User A's transmission");
        assertTrue(userB.getState() == FloorController.State.RECEIVING, "User B must be RECEIVING");
        assertTrue(rxStartBeeps.get() == 1, "User B must hear Pre-Hear Beep before voice starts");

        // 2. While User A is talking, User B tries to press Push-To-Talk -> MUST BE BLOCKED!
        boolean canUserBTalk = userB.requestLocalPttStart("Unit-B", 1200L);
        assertTrue(!canUserBTalk, "User B must NOT be able to talk while User A is talking");
        assertTrue("Bravo-1".equals(busyDeniedBy.get()), "Busy callback must identify Bravo-1");
        assertTrue(txStartBeeps.get() == 0, "User B must not play TX start beep when blocked");

        // 3. User A finishes talking and releases PTT
        userB.onRemoteReleasePacket("WT-USER-A");
        assertTrue(userB.getState() == FloorController.State.IDLE, "Channel must return to IDLE");
        assertTrue(rogerBeeps.get() == 1, "Roger beep must play when User A finishes");

        // 4. Now that User A finished, User B presses Push-To-Talk -> SUCCEEDS!
        boolean canUserBTalkNow = userB.requestLocalPttStart("Unit-B", 1500L);
        assertTrue(canUserBTalkNow, "User B can now acquire PTT after User A finished");
        assertTrue(
                userB.getState() == FloorController.State.TX_PRE_BEEP,
                "User B enters TX_PRE_BEEP first");
        assertTrue(txStartBeeps.get() == 1, "Pre-Talk Beep must play before User B speaks");
        assertTrue(!micStreaming.get(), "Mic must wait until Pre-Talk Beep completes");

        // 5. After Pre-Talk Beep completes, mic goes live
        userB.confirmLocalMicLive("Unit-B");
        assertTrue(
                userB.getState() == FloorController.State.TRANSMITTING,
                "User B is now TRANSMITTING");
        assertTrue(micStreaming.get(), "Mic streaming is now active");

        // 6. User B releases PTT
        userB.releaseLocalPtt();
        assertTrue(userB.getState() == FloorController.State.IDLE, "User B returns to IDLE");
        assertTrue(!micStreaming.get(), "Mic streaming stops on PTT release");
        assertTrue(rogerBeeps.get() == 2, "Roger beep plays on PTT release");
    }

    private static void testSimultaneousPttTieBreaker() {
        FloorController controller = new FloorController("WT-USER-B", new FloorController.Listener() {
            @Override
            public void onStateChanged(FloorController.State s, String id, String c) {}
            @Override
            public void onPlayTxStartBeep() {}
            @Override
            public void onStartMicStreaming() {}
            @Override
            public void onStopMicStreaming() {}
            @Override
            public void onPlayRxStartBeep(String speakerCallsign) {}
            @Override
            public void onPlayRogerBeep() {}
            @Override
            public void onPttDeniedChannelBusy(String currentSpeakerCallsign) {}
        });

        // User B presses PTT at t=2000ms
        controller.requestLocalPttStart("Unit-B", 2000L);
        assertTrue(controller.getState() == FloorController.State.TX_PRE_BEEP, "In pre-beep");

        // Remote User A pressed PTT earlier at t=1980ms
        WalkiePacket earlierRemote = new WalkiePacket(
                WalkiePacket.TYPE_PTT_REQUEST,
                1,
                PeerInfo.STATE_PRE_BEEP,
                1,
                1980L,
                0,
                "WT-USER-A",
                "Alpha",
                null);
        boolean yielded = controller.onRemoteTransmissionPacket(earlierRemote, 2010L);
        assertTrue(yielded, "Must yield to earlier PTT press");
        assertTrue(
                controller.getState() == FloorController.State.RECEIVING,
                "Must switch to RECEIVING");
    }

    private static void testWatchdogAutoUnlock() {
        FloorController controller = new FloorController("WT-USER-B", new FloorController.Listener() {
            @Override
            public void onStateChanged(FloorController.State s, String id, String c) {}
            @Override
            public void onPlayTxStartBeep() {}
            @Override
            public void onStartMicStreaming() {}
            @Override
            public void onStopMicStreaming() {}
            @Override
            public void onPlayRxStartBeep(String speakerCallsign) {}
            @Override
            public void onPlayRogerBeep() {}
            @Override
            public void onPttDeniedChannelBusy(String currentSpeakerCallsign) {}
        });

        WalkiePacket pkt = new WalkiePacket(
                WalkiePacket.TYPE_AUDIO_PCM,
                1,
                PeerInfo.STATE_TRANSMITTING,
                1,
                5000L,
                500,
                "WT-USER-A",
                "Alpha",
                new byte[640]);
        controller.onRemoteTransmissionPacket(pkt, 5000L);
        assertTrue(controller.getState() == FloorController.State.RECEIVING, "Locked in RX");

        controller.checkWatchdog(5400L);
        assertTrue(controller.getState() == FloorController.State.RECEIVING, "Still within timeout");

        controller.checkWatchdog(5850L);
        assertTrue(
                controller.getState() == FloorController.State.IDLE,
                "Watchdog must unlock channel after timeout");
    }

    private static void testBeepSynthesisAndRms() {
        short[] tone = BeepSynthesizer.generateTone(1200.0, 50, 0.6);
        assertTrue(tone.length == 800, "50ms at 16kHz must be 800 samples");
        byte[] bytes = new byte[tone.length * 2];
        for (int i = 0; i < tone.length; i++) {
            bytes[i * 2] = (byte) (tone[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((tone[i] >> 8) & 0xFF);
        }
        int rms = AudioStreamManager.computeRmsLevel(bytes);
        assertTrue(rms > 400 && rms <= 1000, "Synthesized tone must have strong non-zero RMS");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Test failed: " + message);
        }
    }
}
