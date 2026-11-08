package com.zdarkchoco.wifitalkie.net;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import com.zdarkchoco.wifitalkie.audio.AudioStreamManager;
import com.zdarkchoco.wifitalkie.audio.BeepSynthesizer;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Full Local Wi-Fi Walkie-Talkie Engine:
 * - Zero-server UDP Multicast (239.255.42.99:50005) + Subnet Broadcast + Direct Unicast Mesh
 * - Multi-user discovery across 16 channels
 * - Strict Half-Duplex Push-To-Talk floor arbitration (wait for others to finish talking)
 * - Pre-Talk & Pre-Hear Beep synthesis
 */
public final class WifiTalkieEngine
        implements FloorController.Listener, AudioStreamManager.FrameCallback {

    public static final int UDP_PORT = 50005;
    public static final String MULTICAST_GROUP = "239.255.42.99";

    public interface UiListener {
        void onStateUpdated(
                FloorController.State floorState,
                int currentChannel,
                String activeSpeakerCallsign,
                String localIp,
                List<PeerInfo> allPeers,
                int[] channelCounts);

        void onAudioLevelUpdated(int level0To1000, boolean isTransmitting);

        void onChannelBusyToast(String activeSpeakerCallsign);

        void onCallAlertReceived(String fromCallsign, int channel);
    }

    private final Context context;
    private final String localDeviceId;
    private volatile String localCallsign;
    private volatile int currentChannel = 1;
    private volatile UiListener uiListener;

    private final FloorController floorController;
    private final BeepSynthesizer beepSynthesizer;
    private final AudioStreamManager audioStreamManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ConcurrentHashMap<String, PeerInfo> peersById = new ConcurrentHashMap<>();
    private final Set<String> manualPeerIps = Collections.synchronizedSet(new HashSet<>());
    private final AtomicInteger packetSeq = new AtomicInteger(1);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean userHoldingPtt = new AtomicBoolean(false);

    private ExecutorService netSendExecutor;
    private ExecutorService beepCoordinatorExecutor;
    private ScheduledExecutorService heartbeatScheduler;
    private Thread receiveThread;

    private MulticastSocket socket;
    private InetAddress multicastAddress;
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private volatile String cachedLocalIp = "0.0.0.0";
    private volatile int currentAudioLevel = 0;

    public WifiTalkieEngine(Context context, String deviceId, String initialCallsign) {
        this.context = context.getApplicationContext();
        this.localDeviceId = deviceId;
        this.localCallsign = initialCallsign;
        this.floorController = new FloorController(deviceId, this);
        this.beepSynthesizer = new BeepSynthesizer();
        this.audioStreamManager = new AudioStreamManager(context, this);
    }

    public void setUiListener(UiListener listener) {
        this.uiListener = listener;
        notifyUiState();
    }

    public String getLocalDeviceId() {
        return localDeviceId;
    }

    public String getLocalCallsign() {
        return localCallsign;
    }

    public void setLocalCallsign(String callsign) {
        if (callsign != null && !callsign.trim().isEmpty()) {
            this.localCallsign = callsign.trim();
            sendHelloAsync();
            notifyUiState();
        }
    }

    public int getCurrentChannel() {
        return currentChannel;
    }

    /**
     * Switches walkie-talkie channel (1..16).
     * Automatically releases local PTT if held, resets floor state, and locks into RECEIVING
     * if another discovered peer on the target channel is already talking.
     */
    public void setChannel(int newChannel) {
        int clamped = WalkiePacket.clampChannel(newChannel);
        if (clamped == this.currentChannel) {
            return;
        }
        if (userHoldingPtt.getAndSet(false)) {
            floorController.releaseLocalPtt();
            sendControlBurstAsync(WalkiePacket.TYPE_PTT_RELEASE, this.currentChannel);
        }
        this.currentChannel = clamped;
        audioStreamManager.clearPlaybackQueue();
        floorController.resetForChannelSwitch();
        beepSynthesizer.playChannelSwitchBeepAsync();

        // Check if any active peer on the new channel is already talking
        long now = System.currentTimeMillis();
        for (PeerInfo p : peersById.values()) {
            if (p.isOnline(now) && p.channel == clamped && p.isTalking()) {
                WalkiePacket synthetic = new WalkiePacket(
                        WalkiePacket.TYPE_PTT_REQUEST,
                        clamped,
                        p.state,
                        0,
                        now,
                        p.audioLevel,
                        p.deviceId,
                        p.callsign,
                        null);
                floorController.onRemoteTransmissionPacket(synthetic, now);
                break;
            }
        }

        sendHelloAsync();
        notifyUiState();
    }

    public FloorController.State getFloorState() {
        return floorController.getState();
    }

    public String getActiveSpeakerCallsign() {
        return floorController.getActiveSpeakerCallsign();
    }

    public String getLocalIpAddress() {
        return cachedLocalIp;
    }

    public BeepSynthesizer getBeepSynthesizer() {
        return beepSynthesizer;
    }

    public AudioStreamManager getAudioStreamManager() {
        return audioStreamManager;
    }

    public void addManualPeerIp(String ip) {
        if (ip != null && !ip.trim().isEmpty()) {
            manualPeerIps.add(ip.trim());
            sendHelloAsync();
        }
    }

    /**
     * Starts the local Wi-Fi UDP discovery, multicast lock, and audio playback engine.
     */
    public void start() {
        if (running.getAndSet(true)) {
            return;
        }
        acquireWifiLocks();
        cachedLocalIp = detectLocalIpv4();
        audioStreamManager.startPlaybackEngine();

        netSendExecutor = Executors.newSingleThreadExecutor();
        beepCoordinatorExecutor = Executors.newSingleThreadExecutor();
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

        receiveThread = new Thread(this::runReceiveLoop, "WifiTalkie-UdpRx");
        receiveThread.setDaemon(true);
        receiveThread.start();

        heartbeatScheduler.scheduleAtFixedRate(
                this::onPeriodicTick, 100L, 400L, TimeUnit.MILLISECONDS);
        notifyUiState();
    }

    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        userHoldingPtt.set(false);
        floorController.releaseLocalPtt();
        audioStreamManager.stopMicCapture();
        audioStreamManager.stopPlaybackEngine();

        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
        if (netSendExecutor != null) {
            netSendExecutor.shutdownNow();
            netSendExecutor = null;
        }
        if (beepCoordinatorExecutor != null) {
            beepCoordinatorExecutor.shutdownNow();
            beepCoordinatorExecutor = null;
        }
        if (socket != null) {
            try {
                if (multicastAddress != null) {
                    socket.leaveGroup(multicastAddress);
                }
            } catch (Throwable ignored) {
            }
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
            socket = null;
        }
        if (receiveThread != null) {
            receiveThread.interrupt();
            receiveThread = null;
        }
        releaseWifiLocks();
    }

    /**
     * Push-To-Talk Pressed (ACTION_DOWN).
     * Returns true if channel is free and pre-talk beep + transmission sequence begins.
     * Returns false if another user is currently talking on this channel (must wait!).
     */
    public boolean onPttPressed() {
        userHoldingPtt.set(true);
        long now = System.currentTimeMillis();
        boolean acquired = floorController.requestLocalPttStart(localCallsign, now);
        if (!acquired) {
            userHoldingPtt.set(false);
        }
        return acquired;
    }

    /**
     * Push-To-Talk Released (ACTION_UP / ACTION_CANCEL).
     */
    public void onPttReleased() {
        userHoldingPtt.set(false);
        boolean released = floorController.releaseLocalPtt();
        if (released) {
            sendControlBurstAsync(WalkiePacket.TYPE_PTT_RELEASE, currentChannel);
            sendHelloAsync();
        }
    }

    /**
     * Sends a Call Alert Beep on the current channel if the channel is idle.
     */
    public boolean sendChannelCallAlert() {
        if (floorController.getState() != FloorController.State.IDLE) {
            return false;
        }
        beepSynthesizer.playCallAlertBeepAsync();
        sendControlBurstAsync(WalkiePacket.TYPE_PING_BEEP, currentChannel);
        return true;
    }

    // --- FloorController.Listener implementation ---

    @Override
    public void onStateChanged(
            FloorController.State newState,
            String activeSpeakerId,
            String activeSpeakerCallsign) {
        if (newState == FloorController.State.IDLE) {
            currentAudioLevel = 0;
            notifyAudioLevel(0, false);
        }
        notifyUiState();
    }

    @Override
    public void onPlayTxStartBeep() {
        final int txChannel = currentChannel;
        // Announce floor claim immediately so peers lock their channel while our start beep plays
        sendControlBurstAsync(WalkiePacket.TYPE_PTT_REQUEST, txChannel);
        if (beepCoordinatorExecutor != null && !beepCoordinatorExecutor.isShutdown()) {
            beepCoordinatorExecutor.execute(() -> {
                beepSynthesizer.playTxStartBeepBlocking();
                if (userHoldingPtt.get()
                        && running.get()
                        && currentChannel == txChannel
                        && floorController.getState() == FloorController.State.TX_PRE_BEEP) {
                    floorController.confirmLocalMicLive(localCallsign);
                }
            });
        }
    }

    @Override
    public void onStartMicStreaming() {
        audioStreamManager.startMicCapture();
        sendHelloAsync();
    }

    @Override
    public void onStopMicStreaming() {
        audioStreamManager.stopMicCapture();
    }

    @Override
    public void onPlayRxStartBeep(String speakerCallsign) {
        audioStreamManager.setHoldPlaybackForBeep(true);
        if (beepCoordinatorExecutor != null && !beepCoordinatorExecutor.isShutdown()) {
            beepCoordinatorExecutor.execute(() -> {
                try {
                    beepSynthesizer.playRxStartBeepBlocking();
                } finally {
                    audioStreamManager.setHoldPlaybackForBeep(false);
                }
            });
        } else {
            audioStreamManager.setHoldPlaybackForBeep(false);
        }
    }

    @Override
    public void onPlayRogerBeep() {
        beepSynthesizer.playRogerBeepAsync();
    }

    @Override
    public void onPttDeniedChannelBusy(String currentSpeakerCallsign) {
        beepSynthesizer.playBusyDenyBeepAsync();
        mainHandler.post(() -> {
            UiListener l = uiListener;
            if (l != null) {
                l.onChannelBusyToast(currentSpeakerCallsign);
            }
        });
    }

    // --- AudioStreamManager.FrameCallback implementation ---

    @Override
    public void onMicAudioFrame(byte[] pcmBytes, int rmsLevel) {
        if (!userHoldingPtt.get()
                || floorController.getState() != FloorController.State.TRANSMITTING) {
            return;
        }
        currentAudioLevel = rmsLevel;
        notifyAudioLevel(rmsLevel, true);

        WalkiePacket audioPkt = new WalkiePacket(
                WalkiePacket.TYPE_AUDIO_PCM,
                currentChannel,
                PeerInfo.STATE_TRANSMITTING,
                packetSeq.getAndIncrement(),
                floorController.getActiveClaimTimestampMs(),
                rmsLevel,
                localDeviceId,
                localCallsign,
                pcmBytes);
        sendPacketToChannelPeersAsync(audioPkt, true);
    }

    @Override
    public void onPlaybackAudioLevel(int rmsLevel) {
        if (floorController.getState() == FloorController.State.RECEIVING) {
            currentAudioLevel = rmsLevel;
            notifyAudioLevel(rmsLevel, false);
        }
    }

    // --- Networking Internals ---

    private int tickCounter = 0;

    private void onPeriodicTick() {
        if (!running.get()) return;
        long now = System.currentTimeMillis();
        floorController.checkWatchdog(now);

        tickCounter++;
        // Every 3 ticks (1.2s), broadcast HELLO presence and prune stale peers
        if (tickCounter % 3 == 0) {
            cachedLocalIp = detectLocalIpv4();
            pruneStalePeers(now);
            sendHelloAsync();
            notifyUiState();
        }
    }

    private void pruneStalePeers(long nowMs) {
        boolean removedAny = false;
        for (Map.Entry<String, PeerInfo> entry : peersById.entrySet()) {
            if (!entry.getValue().isOnline(nowMs)) {
                peersById.remove(entry.getKey());
                removedAny = true;
            }
        }
        if (removedAny) {
            notifyUiState();
        }
    }

    private void runReceiveLoop() {
        try {
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.bind(new InetSocketAddress(UDP_PORT));

            try {
                multicastAddress = InetAddress.getByName(MULTICAST_GROUP);
                socket.joinGroup(multicastAddress);
            } catch (Throwable ignored) {
                // Router or interface may only support broadcast/unicast
            }

            byte[] buf = new byte[WalkiePacket.MAX_PACKET_SIZE];
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                DatagramPacket dgram = new DatagramPacket(buf, buf.length);
                socket.receive(dgram);
                WalkiePacket pkt = WalkiePacket.fromBytes(dgram.getData(), dgram.getLength());
                if (pkt == null || localDeviceId.equals(pkt.senderId)) {
                    continue;
                }
                String senderIp = dgram.getAddress() != null
                        ? dgram.getAddress().getHostAddress()
                        : "0.0.0.0";
                handleIncomingPacket(pkt, senderIp);
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleIncomingPacket(WalkiePacket pkt, String senderIp) {
        long now = System.currentTimeMillis();

        // Update or register peer in our multi-user registry
        PeerInfo peer = peersById.get(pkt.senderId);
        boolean peerChanged = false;
        if (peer == null) {
            peer = new PeerInfo(
                    pkt.senderId,
                    pkt.callsign,
                    senderIp,
                    pkt.channel,
                    pkt.senderState,
                    pkt.audioLevel,
                    now);
            peersById.put(pkt.senderId, peer);
            peerChanged = true;
        } else {
            if (peer.channel != pkt.channel
                    || peer.state != pkt.senderState
                    || !peer.callsign.equals(pkt.callsign)) {
                peerChanged = true;
            }
            peer.callsign = pkt.callsign;
            peer.ipAddress = senderIp;
            peer.channel = pkt.channel;
            peer.state = pkt.senderState;
            peer.audioLevel = pkt.audioLevel;
            peer.lastSeenMs = now;
        }

        // If the packet is on our current channel, process PTT floor control & audio
        if (pkt.channel == currentChannel) {
            switch (pkt.type) {
                case WalkiePacket.TYPE_PTT_REQUEST:
                    floorController.onRemoteTransmissionPacket(pkt, now);
                    peerChanged = true;
                    break;

                case WalkiePacket.TYPE_AUDIO_PCM:
                    boolean accepted = floorController.onRemoteTransmissionPacket(pkt, now);
                    if (accepted && pkt.payload != null && pkt.payload.length > 0) {
                        audioStreamManager.enqueueIncomingPcmFrame(pkt.payload);
                    }
                    break;

                case WalkiePacket.TYPE_PTT_RELEASE:
                    peer.state = PeerInfo.STATE_IDLE;
                    floorController.onRemoteReleasePacket(pkt.senderId);
                    peerChanged = true;
                    break;

                case WalkiePacket.TYPE_PING_BEEP:
                    if (floorController.getState() == FloorController.State.IDLE) {
                        beepSynthesizer.playCallAlertBeepAsync();
                        mainHandler.post(() -> {
                            UiListener l = uiListener;
                            if (l != null) {
                                l.onCallAlertReceived(pkt.callsign, pkt.channel);
                            }
                        });
                    }
                    break;

                case WalkiePacket.TYPE_HELLO:
                    if (pkt.senderState == PeerInfo.STATE_TRANSMITTING
                            || pkt.senderState == PeerInfo.STATE_PRE_BEEP) {
                        floorController.onRemoteTransmissionPacket(pkt, now);
                    } else if (pkt.senderState == PeerInfo.STATE_IDLE
                            && pkt.senderId.equals(floorController.getActiveSpeakerId())) {
                        floorController.onRemoteReleasePacket(pkt.senderId);
                    }
                    break;

                default:
                    break;
            }
        } else {
            // If this peer was our active speaker and just switched to another channel, unlock floor!
            if (pkt.senderId.equals(floorController.getActiveSpeakerId())) {
                floorController.onRemoteReleasePacket(pkt.senderId);
                peerChanged = true;
            }
        }

        if (peerChanged) {
            notifyUiState();
        }
    }

    private void sendHelloAsync() {
        if (!running.get() || netSendExecutor == null || netSendExecutor.isShutdown()) {
            return;
        }
        netSendExecutor.execute(() -> {
            WalkiePacket hello = new WalkiePacket(
                    WalkiePacket.TYPE_HELLO,
                    currentChannel,
                    floorController.getLocalWireState(),
                    packetSeq.getAndIncrement(),
                    floorController.getActiveClaimTimestampMs(),
                    currentAudioLevel,
                    localDeviceId,
                    localCallsign,
                    null);
            broadcastAndUnicastAll(hello.toBytes());
        });
    }

    private void sendControlBurstAsync(byte packetType, int channel) {
        if (!running.get() || netSendExecutor == null || netSendExecutor.isShutdown()) {
            return;
        }
        netSendExecutor.execute(() -> {
            long ts = floorController.getActiveClaimTimestampMs();
            if (ts == 0L) ts = System.currentTimeMillis();
            WalkiePacket pkt = new WalkiePacket(
                    packetType,
                    channel,
                    floorController.getLocalWireState(),
                    packetSeq.getAndIncrement(),
                    ts,
                    0,
                    localDeviceId,
                    localCallsign,
                    null);
            byte[] raw = pkt.toBytes();
            // Send 3 times spaced 12ms apart for high reliability over Wi-Fi UDP
            for (int i = 0; i < 3; i++) {
                broadcastAndUnicastAll(raw);
                if (i < 2) {
                    try {
                        Thread.sleep(12L);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
        });
    }

    private void sendPacketToChannelPeersAsync(WalkiePacket pkt, boolean isAudio) {
        if (!running.get() || netSendExecutor == null || netSendExecutor.isShutdown()) {
            return;
        }
        netSendExecutor.execute(() -> {
            byte[] raw = pkt.toBytes();
            MulticastSocket s = socket;
            if (s == null || s.isClosed()) return;

            long now = System.currentTimeMillis();
            int unicastSentCount = 0;

            // 1. Send directly via unicast to every discovered peer on the same channel
            for (PeerInfo p : peersById.values()) {
                if (p.isOnline(now) && p.channel == pkt.channel && p.ipAddress != null) {
                    try {
                        InetAddress addr = InetAddress.getByName(p.ipAddress);
                        s.send(new DatagramPacket(raw, raw.length, addr, UDP_PORT));
                        unicastSentCount++;
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 2. Also send to any manually configured peer IPs
            synchronized (manualPeerIps) {
                for (String ip : manualPeerIps) {
                    try {
                        InetAddress addr = InetAddress.getByName(ip);
                        s.send(new DatagramPacket(raw, raw.length, addr, UDP_PORT));
                        unicastSentCount++;
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 3. If no peer on this channel is unicast-known yet (or non-audio packet), also broadcast
            if (!isAudio || unicastSentCount == 0) {
                broadcastOnly(raw);
            }
        });
    }

    private void broadcastAndUnicastAll(byte[] raw) {
        MulticastSocket s = socket;
        if (s == null || s.isClosed()) return;

        broadcastOnly(raw);

        long now = System.currentTimeMillis();
        Set<String> sentIps = new HashSet<>();
        for (PeerInfo p : peersById.values()) {
            if (p.isOnline(now) && p.ipAddress != null && sentIps.add(p.ipAddress)) {
                try {
                    s.send(new DatagramPacket(
                            raw, raw.length, InetAddress.getByName(p.ipAddress), UDP_PORT));
                } catch (Throwable ignored) {
                }
            }
        }
        synchronized (manualPeerIps) {
            for (String ip : manualPeerIps) {
                if (sentIps.add(ip)) {
                    try {
                        s.send(new DatagramPacket(
                                raw, raw.length, InetAddress.getByName(ip), UDP_PORT));
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    private void broadcastOnly(byte[] raw) {
        MulticastSocket s = socket;
        if (s == null || s.isClosed()) return;

        // Multicast group
        if (multicastAddress != null) {
            try {
                s.send(new DatagramPacket(raw, raw.length, multicastAddress, UDP_PORT));
            } catch (Throwable ignored) {
            }
        }

        // Subnet broadcasts across active Wi-Fi interfaces
        for (InetAddress bcast : getBroadcastAddresses()) {
            try {
                s.send(new DatagramPacket(raw, raw.length, bcast, UDP_PORT));
            } catch (Throwable ignored) {
            }
        }
    }

    public List<PeerInfo> getSnapshotPeers() {
        long now = System.currentTimeMillis();
        List<PeerInfo> list = new ArrayList<>();
        for (PeerInfo p : peersById.values()) {
            if (p.isOnline(now)) {
                list.add(p);
            }
        }
        Collections.sort(list, Comparator.comparing((PeerInfo a) -> a.callsign));
        return list;
    }

    public int[] getChannelUserCounts() {
        int[] counts = new int[WalkiePacket.MAX_CHANNEL + 1];
        long now = System.currentTimeMillis();
        for (PeerInfo p : peersById.values()) {
            if (p.isOnline(now)
                    && p.channel >= WalkiePacket.MIN_CHANNEL
                    && p.channel <= WalkiePacket.MAX_CHANNEL) {
                counts[p.channel]++;
            }
        }
        return counts;
    }

    private void notifyUiState() {
        final UiListener l = uiListener;
        if (l == null) return;
        final FloorController.State st = floorController.getState();
        final int ch = currentChannel;
        final String speaker = floorController.getActiveSpeakerCallsign();
        final String ip = cachedLocalIp;
        final List<PeerInfo> peers = getSnapshotPeers();
        final int[] counts = getChannelUserCounts();

        mainHandler.post(() -> l.onStateUpdated(st, ch, speaker, ip, peers, counts));
    }

    private void notifyAudioLevel(int level, boolean isTx) {
        final UiListener l = uiListener;
        if (l == null) return;
        mainHandler.post(() -> l.onAudioLevelUpdated(level, isTx));
    }

    @SuppressWarnings("deprecation")
    private void acquireWifiLocks() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("WifiTalkie:MulticastLock");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();

                int lockMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                        ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(lockMode, "WifiTalkie:LowLatencyWifiLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseWifiLocks() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) {
                multicastLock.release();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Throwable ignored) {
        }
    }

    private String detectLocalIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String host = addr.getHostAddress();
                        if (host != null && !host.startsWith("169.254.")) {
                            return host;
                        }
                    }
                }
            }
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo info = wm.getConnectionInfo();
                if (info != null) {
                    int ip = info.getIpAddress();
                    if (ip != 0) {
                        return String.format(
                                java.util.Locale.US,
                                "%d.%d.%d.%d",
                                (ip & 0xff),
                                (ip >> 8 & 0xff),
                                (ip >> 16 & 0xff),
                                (ip >> 24 & 0xff));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "0.0.0.0";
    }

    private List<InetAddress> getBroadcastAddresses() {
        List<InetAddress> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    InetAddress bcast = ia.getBroadcast();
                    if (bcast != null) {
                        list.add(bcast);
                    }
                }
            }
            list.add(InetAddress.getByName("255.255.255.255"));
        } catch (Throwable ignored) {
        }
        return list;
    }
}
