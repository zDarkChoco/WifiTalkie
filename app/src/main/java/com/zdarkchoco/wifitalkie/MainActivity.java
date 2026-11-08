package com.zdarkchoco.wifitalkie;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.zdarkchoco.wifitalkie.net.FloorController;
import com.zdarkchoco.wifitalkie.net.PeerInfo;
import com.zdarkchoco.wifitalkie.net.WalkiePacket;
import com.zdarkchoco.wifitalkie.net.WifiTalkieEngine;
import com.zdarkchoco.wifitalkie.service.WalkieTalkieService;
import com.zdarkchoco.wifitalkie.ui.LiquidGlassDrawable;
import com.zdarkchoco.wifitalkie.ui.LiquidPttOrbView;
import com.zdarkchoco.wifitalkie.ui.MaterialOledPalette;
import com.zdarkchoco.wifitalkie.ui.WaveformVisualizerView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Main Walkie-Talkie Activity featuring:
 * - Pure OLED Black (#000000) Dark-Mode-Only Layout for maximum battery life
 * - Liquid Glass Specular Cards & Floating Button Dock with Google Material 3 Dynamic Colors
 * - Multi-User Wi-Fi Peer Radar & 16-Channel Selector
 * - Strict Half-Duplex Push-To-Talk with Pre-Talk & Pre-Hear Beeps
 */
public final class MainActivity extends Activity implements WifiTalkieEngine.UiListener {

    private static final int REQ_PERMISSIONS = 2001;
    private static final String PREFS_NAME = "wifitalkie_prefs";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_CALLSIGN = "callsign";
    private static final String KEY_LAST_CHANNEL = "last_channel";

    private float density;
    private SharedPreferences prefs;
    private WifiTalkieEngine engine;
    private Vibrator vibrator;

    // UI Elements
    private TextView networkSubtitleView;
    private TextView callsignChipView;
    private TextView speakerChipView;
    private TextView beepChipView;
    private LinearLayout channelStripContainer;
    private final TextView[] channelPillViews = new TextView[WalkiePacket.MAX_CHANNEL + 1];
    private final LiquidGlassDrawable[] channelPillDrawables =
            new LiquidGlassDrawable[WalkiePacket.MAX_CHANNEL + 1];

    private TextView peersHeaderCountView;
    private LinearLayout peersListContainer;
    private WaveformVisualizerView waveformVisualizerView;
    private TextView statusBannerView;
    private LiquidGlassDrawable statusBannerGlass;
    private LiquidPttOrbView pttOrbView;

    private List<PeerInfo> latestPeers = new ArrayList<>();
    private int[] latestChannelCounts = new int[WalkiePacket.MAX_CHANNEL + 1];
    private boolean volumeKeyHeldPtt = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureOledDarkWindow();

        this.density = getResources().getDisplayMetrics().density;
        this.prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        this.vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        String deviceId = getOrCreateDeviceId();
        String callsign = getOrCreateCallsign();
        int initialChannel = WalkiePacket.clampChannel(prefs.getInt(KEY_LAST_CHANNEL, 1));

        engine = new WifiTalkieEngine(this, deviceId, callsign);
        engine.setChannel(initialChannel);

        setContentView(buildRootLayout());

        engine.setUiListener(this);
        requestRequiredPermissionsIfNeeded();
        startWalkieServiceSafe();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (engine != null) {
            engine.start();
        }
    }

    @Override
    protected void onStop() {
        if (engine != null) {
            engine.onPttReleased();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (engine != null) {
            engine.stop();
        }
        try {
            stopService(new Intent(this, WalkieTalkieService.class));
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void configureOledDarkWindow() {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = getWindow();
        if (w != null) {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            w.setStatusBarColor(MaterialOledPalette.OLED_BLACK);
            w.setNavigationBarColor(MaterialOledPalette.OLED_BLACK);
            w.getDecorView().setBackgroundColor(MaterialOledPalette.OLED_BLACK);
        }
    }

    private View buildRootLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(MaterialOledPalette.OLED_BLACK);

        LinearLayout mainColumn = new LinearLayout(this);
        mainColumn.setOrientation(LinearLayout.VERTICAL);
        mainColumn.setPadding(dp(16), dp(14), dp(16), dp(16));

        // 1. Floating Top Liquid Glass Header Bar
        mainColumn.addView(
                buildTopFloatingBar(),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        // 2. Floating Channel Selector Strip + All Channels Matrix Button
        LinearLayout.LayoutParams chParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        chParams.topMargin = dp(12);
        mainColumn.addView(buildChannelSelectorSection(), chParams);

        // 3. Floating Detected Wi-Fi Users Radar Card
        LinearLayout.LayoutParams peersParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        peersParams.topMargin = dp(12);
        mainColumn.addView(buildDetectedPeersCard(), peersParams);

        // 4. Floating Channel Frequency & 28-Bar Audio Spectrum Visualizer Card
        waveformVisualizerView = new WaveformVisualizerView(this);
        LinearLayout.LayoutParams waveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(132));
        waveParams.topMargin = dp(12);
        mainColumn.addView(waveformVisualizerView, waveParams);

        // 5. Floating Status / Half-Duplex Instruction Banner
        statusBannerView = new TextView(this);
        statusBannerView.setGravity(Gravity.CENTER);
        statusBannerView.setPadding(dp(16), dp(9), dp(16), dp(9));
        statusBannerView.setTextColor(MaterialOledPalette.TEXT_PRIMARY);
        statusBannerView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusBannerView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        statusBannerView.setText("READY • HOLD FLOATING PTT BUTTON TO TALK (BEEP PRECEDES VOICE)");
        statusBannerGlass = new LiquidGlassDrawable(
                dp(20),
                dp(1.2f),
                MaterialOledPalette.STATE_IDLE_CYAN,
                false);
        statusBannerView.setBackground(statusBannerGlass);
        LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bannerParams.topMargin = dp(10);
        mainColumn.addView(statusBannerView, bannerParams);

        // 6. Centerpiece Floating Push-To-Talk (PTT) Liquid Glass Orb
        FrameLayout orbContainer = new FrameLayout(this);
        pttOrbView = new LiquidPttOrbView(this);
        pttOrbView.setPttGestureListener(new LiquidPttOrbView.PttGestureListener() {
            @Override
            public void onPttHoldStart() {
                handlePttHoldStart();
            }

            @Override
            public void onPttHoldRelease() {
                handlePttHoldRelease();
            }
        });
        FrameLayout.LayoutParams orbParams = new FrameLayout.LayoutParams(dp(236), dp(236));
        orbParams.gravity = Gravity.CENTER;
        orbContainer.addView(pttOrbView, orbParams);

        LinearLayout.LayoutParams orbWrapParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.0f);
        mainColumn.addView(orbContainer, orbWrapParams);

        // 7. Bottom Floating Action Dock (CH-, Call Alert Beep, Scan Active Channel, CH+)
        LinearLayout.LayoutParams dockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        dockParams.topMargin = dp(6);
        mainColumn.addView(buildBottomFloatingDock(), dockParams);

        root.addView(
                mainColumn,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private View buildTopFloatingBar() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setBackground(new LiquidGlassDrawable(
                dp(24),
                dp(1.3f),
                MaterialOledPalette.M3_PRIMARY_BLUE,
                false));

        // Top row: Brand Title + Quick Floating Action Pills
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText("WifiTalkie");
        titleView.setTextColor(MaterialOledPalette.TEXT_PRIMARY);
        titleView.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        titleView.setLetterSpacing(0.03f);
        titleCol.addView(titleView);

        networkSubtitleView = new TextView(this);
        networkSubtitleView.setText("LOCAL WI-FI • INITIALIZING...");
        networkSubtitleView.setTextColor(MaterialOledPalette.M3_PRIMARY_AQUA);
        networkSubtitleView.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        networkSubtitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        titleCol.addView(networkSubtitleView);

        topRow.addView(
                titleCol,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        // Callsign floating chip
        callsignChipView = createFloatingActionPill(
                "ID: " + engine.getLocalCallsign(),
                MaterialOledPalette.M3_TERTIARY_VIOLET,
                v -> showEditCallsignDialog());
        topRow.addView(callsignChipView);

        // Speakerphone vs Earpiece floating chip
        speakerChipView = createFloatingActionPill(
                "SPK ON",
                MaterialOledPalette.M3_PRIMARY_AQUA,
                v -> toggleSpeakerphone());
        LinearLayout.LayoutParams spkParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        spkParams.leftMargin = dp(6);
        topRow.addView(speakerChipView, spkParams);

        // Beep toggle floating chip
        beepChipView = createFloatingActionPill(
                "BEEP ON",
                MaterialOledPalette.STATE_TX_EMERALD,
                v -> toggleBeepEnabled());
        LinearLayout.LayoutParams beepParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        beepParams.leftMargin = dp(6);
        topRow.addView(beepChipView, beepParams);

        card.addView(topRow);
        return card;
    }

    private View buildChannelSelectorSection() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);

        channelStripContainer = new LinearLayout(this);
        channelStripContainer.setOrientation(LinearLayout.HORIZONTAL);
        channelStripContainer.setGravity(Gravity.CENTER_VERTICAL);

        for (int ch = WalkiePacket.MIN_CHANNEL; ch <= WalkiePacket.MAX_CHANNEL; ch++) {
            final int targetCh = ch;
            TextView pill = new TextView(this);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(14), dp(9), dp(14), dp(9));
            pill.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            pill.setText(WalkiePacket.getChannelCode(ch));

            boolean selected = (ch == engine.getCurrentChannel());
            LiquidGlassDrawable drawable = new LiquidGlassDrawable(
                    dp(18),
                    dp(selected ? 1.8f : 1.1f),
                    selected
                            ? MaterialOledPalette.M3_PRIMARY_AQUA
                            : MaterialOledPalette.M3_SECONDARY_SLATE,
                    selected);
            pill.setBackground(drawable);
            pill.setTextColor(
                    selected
                            ? MaterialOledPalette.TEXT_PRIMARY
                            : MaterialOledPalette.TEXT_SECONDARY);

            pill.setOnClickListener(v -> selectChannelWithFeedback(targetCh));

            channelPillViews[ch] = pill;
            channelPillDrawables[ch] = drawable;

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (ch > WalkiePacket.MIN_CHANNEL) {
                lp.leftMargin = dp(7);
            }
            channelStripContainer.addView(pill, lp);
        }

        scroll.addView(channelStripContainer);
        row.addView(
                scroll,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        // Floating "16 CH" Matrix button
        TextView matrixBtn = createFloatingActionPill(
                "ALL 16 CH",
                MaterialOledPalette.M3_PRIMARY_BLUE,
                v -> showChannelMatrixDialog());
        LinearLayout.LayoutParams matrixLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        matrixLp.leftMargin = dp(8);
        row.addView(matrixBtn, matrixLp);

        return row;
    }

    private View buildDetectedPeersCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        card.setBackground(new LiquidGlassDrawable(
                dp(22),
                dp(1.2f),
                MaterialOledPalette.M3_TERTIARY_VIOLET,
                false));

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);

        peersHeaderCountView = new TextView(this);
        peersHeaderCountView.setText("DETECTED WIFI USERS (0 ONLINE) • TAP USER TO JOIN CH");
        peersHeaderCountView.setTextColor(MaterialOledPalette.TEXT_SECONDARY);
        peersHeaderCountView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        peersHeaderCountView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        headerRow.addView(
                peersHeaderCountView,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView addIpBtn = createFloatingActionPill(
                "+ DIRECT IP",
                MaterialOledPalette.M3_PRIMARY_BLUE,
                v -> showAddDirectIpDialog());
        headerRow.addView(addIpBtn);

        card.addView(headerRow);

        HorizontalScrollView peerScroll = new HorizontalScrollView(this);
        peerScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollLp.topMargin = dp(8);

        peersListContainer = new LinearLayout(this);
        peersListContainer.setOrientation(LinearLayout.HORIZONTAL);
        peersListContainer.setGravity(Gravity.CENTER_VERTICAL);
        peerScroll.addView(peersListContainer);

        card.addView(peerScroll, scrollLp);
        populatePeersContainer(new ArrayList<>(), engine.getCurrentChannel());
        return card;
    }

    private View buildBottomFloatingDock() {
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);

        // 1. Floating CH - Button
        TextView chPrevBtn = createDockFloatingButton(
                "CH -",
                MaterialOledPalette.M3_PRIMARY_BLUE,
                v -> {
                    int prev = engine.getCurrentChannel() - 1;
                    if (prev < WalkiePacket.MIN_CHANNEL) prev = WalkiePacket.MAX_CHANNEL;
                    selectChannelWithFeedback(prev);
                });
        dock.addView(
                chPrevBtn,
                new LinearLayout.LayoutParams(0, dp(48), 1.0f));

        // 2. Floating CALL BEEP Button
        TextView callToneBtn = createDockFloatingButton(
                "CALL BEEP",
                MaterialOledPalette.M3_TERTIARY_VIOLET,
                v -> {
                    vibrateShortClick();
                    boolean sent = engine.sendChannelCallAlert();
                    if (!sent) {
                        Toast.makeText(
                                        this,
                                        "Channel Busy — Wait for transmission to finish",
                                        Toast.LENGTH_SHORT)
                                .show();
                    } else {
                        Toast.makeText(
                                        this,
                                        "Sent Call Beep on "
                                                + WalkiePacket.getChannelCode(
                                                        engine.getCurrentChannel()),
                                        Toast.LENGTH_SHORT)
                                .show();
                    }
                });
        LinearLayout.LayoutParams callLp = new LinearLayout.LayoutParams(0, dp(48), 1.25f);
        callLp.leftMargin = dp(8);
        dock.addView(callToneBtn, callLp);

        // 3. Floating SCAN ACTIVE USER Button
        TextView scanBtn = createDockFloatingButton(
                "SCAN PEERS",
                MaterialOledPalette.STATE_TX_EMERALD,
                v -> scanAndJumpToActivePeerChannel());
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(0, dp(48), 1.25f);
        scanLp.leftMargin = dp(8);
        dock.addView(scanBtn, scanLp);

        // 4. Floating CH + Button
        TextView chNextBtn = createDockFloatingButton(
                "CH +",
                MaterialOledPalette.M3_PRIMARY_BLUE,
                v -> {
                    int next = engine.getCurrentChannel() + 1;
                    if (next > WalkiePacket.MAX_CHANNEL) next = WalkiePacket.MIN_CHANNEL;
                    selectChannelWithFeedback(next);
                });
        LinearLayout.LayoutParams nextLp = new LinearLayout.LayoutParams(0, dp(48), 1.0f);
        nextLp.leftMargin = dp(8);
        dock.addView(chNextBtn, nextLp);

        return dock;
    }

    private TextView createFloatingActionPill(
            String text,
            int accentColor,
            View.OnClickListener onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(11), dp(6), dp(11), dp(6));
        tv.setTextColor(MaterialOledPalette.TEXT_PRIMARY);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setBackground(new LiquidGlassDrawable(dp(16), dp(1.2f), accentColor, true));
        tv.setOnClickListener(onClick);
        return tv;
    }

    private TextView createDockFloatingButton(
            String text,
            int accentColor,
            View.OnClickListener onClick) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(MaterialOledPalette.TEXT_PRIMARY);
        tv.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setLetterSpacing(0.04f);
        tv.setBackground(new LiquidGlassDrawable(dp(24), dp(1.4f), accentColor, true));
        tv.setOnClickListener(onClick);
        return tv;
    }

    private void handlePttHoldStart() {
        if (!hasRecordAudioPermission()) {
            requestRequiredPermissionsIfNeeded();
            Toast.makeText(
                            this,
                            "Grant Microphone Permission to use Push-To-Talk",
                            Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        boolean acquired = engine.onPttPressed();
        if (acquired) {
            vibratePttEngage();
        } else {
            vibrateBusyError();
            if (pttOrbView != null) {
                pttOrbView.triggerBusyShakeAnimation();
            }
        }
    }

    private void handlePttHoldRelease() {
        engine.onPttReleased();
        vibrateShortClick();
    }

    private void selectChannelWithFeedback(int ch) {
        int clamped = WalkiePacket.clampChannel(ch);
        vibrateShortClick();
        prefs.edit().putInt(KEY_LAST_CHANNEL, clamped).apply();
        engine.setChannel(clamped);
    }

    private void scanAndJumpToActivePeerChannel() {
        vibrateShortClick();
        if (latestPeers == null || latestPeers.isEmpty()) {
            Toast.makeText(
                            this,
                            "Scanning local Wi-Fi... No other WifiTalkie users detected yet",
                            Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        int currentCh = engine.getCurrentChannel();
        // First look for a peer currently talking, else any peer on another channel, else first peer
        PeerInfo chosen = null;
        for (PeerInfo p : latestPeers) {
            if (p.isTalking()) {
                chosen = p;
                break;
            }
        }
        if (chosen == null) {
            for (PeerInfo p : latestPeers) {
                if (p.channel != currentCh) {
                    chosen = p;
                    break;
                }
            }
        }
        if (chosen == null) {
            chosen = latestPeers.get(0);
        }
        selectChannelWithFeedback(chosen.channel);
        Toast.makeText(
                        this,
                        "Tuned to "
                                + WalkiePacket.getChannelCode(chosen.channel)
                                + " ("
                                + chosen.callsign
                                + ")",
                        Toast.LENGTH_SHORT)
                .show();
    }

    private void toggleSpeakerphone() {
        boolean next = !engine.getAudioStreamManager().isSpeakerphoneEnabled();
        engine.getAudioStreamManager().setSpeakerphoneEnabled(next);
        vibrateShortClick();
        speakerChipView.setText(next ? "SPK ON" : "EARPIECE");
        speakerChipView.setBackground(new LiquidGlassDrawable(
                dp(16),
                dp(1.2f),
                next ? MaterialOledPalette.M3_PRIMARY_AQUA : MaterialOledPalette.M3_SECONDARY_SLATE,
                next));
    }

    private void toggleBeepEnabled() {
        boolean next = !engine.getBeepSynthesizer().isBeepEnabled();
        engine.getBeepSynthesizer().setBeepEnabled(next);
        vibrateShortClick();
        beepChipView.setText(next ? "BEEP ON" : "BEEP MUTE");
        beepChipView.setBackground(new LiquidGlassDrawable(
                dp(16),
                dp(1.2f),
                next ? MaterialOledPalette.STATE_TX_EMERALD : MaterialOledPalette.STATE_BUSY_CORAL,
                next));
        if (next) {
            engine.getBeepSynthesizer().playChannelSwitchBeepAsync();
        }
    }

    // --- WifiTalkieEngine.UiListener callbacks ---

    @Override
    public void onStateUpdated(
            FloorController.State floorState,
            int currentChannel,
            String activeSpeakerCallsign,
            String localIp,
            List<PeerInfo> allPeers,
            int[] channelCounts) {
        this.latestPeers = allPeers != null ? allPeers : new ArrayList<>();
        this.latestChannelCounts =
                channelCounts != null ? channelCounts : new int[WalkiePacket.MAX_CHANNEL + 1];

        // 1. Update Top Network Subtitle & Callsign
        int totalPeers = this.latestPeers.size();
        networkSubtitleView.setText(String.format(
                Locale.US,
                "WIFI IP %s • UDP %d • %d PEER%s",
                localIp,
                WifiTalkieEngine.UDP_PORT,
                totalPeers,
                totalPeers == 1 ? "" : "S"));
        callsignChipView.setText("ID: " + engine.getLocalCallsign());

        // 2. Update 16 Channel Pills
        for (int ch = WalkiePacket.MIN_CHANNEL; ch <= WalkiePacket.MAX_CHANNEL; ch++) {
            TextView pill = channelPillViews[ch];
            LiquidGlassDrawable drawable = channelPillDrawables[ch];
            if (pill == null || drawable == null) continue;

            int count = (ch < this.latestChannelCounts.length) ? this.latestChannelCounts[ch] : 0;
            boolean selected = (ch == currentChannel);
            String label = WalkiePacket.getChannelCode(ch);
            if (count > 0) {
                label += " • " + count;
            }
            pill.setText(label);

            int accent = selected
                    ? MaterialOledPalette.M3_PRIMARY_AQUA
                    : (count > 0
                            ? MaterialOledPalette.STATE_TX_EMERALD
                            : MaterialOledPalette.M3_SECONDARY_SLATE);
            drawable.setAccentColor(accent, selected || count > 0);
            pill.setTextColor(
                    (selected || count > 0)
                            ? MaterialOledPalette.TEXT_PRIMARY
                            : MaterialOledPalette.TEXT_SECONDARY);
        }

        // 3. Update Detected Wi-Fi Peers Radar List
        peersHeaderCountView.setText(String.format(
                Locale.US,
                "DETECTED WIFI USERS (%d ONLINE) • TAP USER TO SELECT CHANNEL",
                totalPeers));
        populatePeersContainer(this.latestPeers, currentChannel);

        // 4. Update Waveform Visualizer & Status Banner & PTT Orb
        int peersOnCurrent = (currentChannel < this.latestChannelCounts.length)
                ? this.latestChannelCounts[currentChannel]
                : 0;
        waveformVisualizerView.updateState(
                currentChannel, floorState, activeSpeakerCallsign, peersOnCurrent);
        pttOrbView.setFloorState(floorState, activeSpeakerCallsign);

        switch (floorState) {
            case TX_PRE_BEEP:
                statusBannerView.setText("BEEP SOUND PLAYING • PREPARE TO SPEAK...");
                statusBannerView.setTextColor(MaterialOledPalette.STATE_PREBEEP_GOLD);
                statusBannerGlass.setAccentColor(MaterialOledPalette.STATE_PREBEEP_GOLD, true);
                break;

            case TRANSMITTING:
                statusBannerView.setText(
                        "TRANSMITTING ON "
                                + WalkiePacket.getChannelCode(currentChannel)
                                + " • RELEASE PTT WHEN DONE");
                statusBannerView.setTextColor(MaterialOledPalette.STATE_TX_EMERALD);
                statusBannerGlass.setAccentColor(MaterialOledPalette.STATE_TX_EMERALD, true);
                break;

            case RECEIVING:
                String spk = (activeSpeakerCallsign != null && !activeSpeakerCallsign.isEmpty())
                        ? activeSpeakerCallsign
                        : "Another User";
                statusBannerView.setText(
                        "CHANNEL LOCKED • WAIT FOR "
                                + spk.toUpperCase(Locale.US)
                                + " TO FINISH TALKING");
                statusBannerView.setTextColor(MaterialOledPalette.STATE_RX_AMBER);
                statusBannerGlass.setAccentColor(MaterialOledPalette.STATE_RX_AMBER, true);
                break;

            default:
                statusBannerView.setText(
                        "CHANNEL CLEAR • HOLD FLOATING PTT ORB TO TALK");
                statusBannerView.setTextColor(MaterialOledPalette.TEXT_PRIMARY);
                statusBannerGlass.setAccentColor(MaterialOledPalette.STATE_IDLE_CYAN, false);
                break;
        }
    }

    @Override
    public void onAudioLevelUpdated(int level0To1000, boolean isTransmitting) {
        if (waveformVisualizerView != null) {
            waveformVisualizerView.setAudioLevel(level0To1000);
        }
        if (pttOrbView != null) {
            pttOrbView.setAudioLevel(level0To1000);
        }
    }

    @Override
    public void onChannelBusyToast(String activeSpeakerCallsign) {
        String spk = (activeSpeakerCallsign != null && !activeSpeakerCallsign.isEmpty())
                ? activeSpeakerCallsign
                : "other user";
        if (pttOrbView != null) {
            pttOrbView.triggerBusyShakeAnimation();
        }
        Toast.makeText(
                        this,
                        "Channel Busy! Wait for " + spk + " to finish talking.",
                        Toast.LENGTH_SHORT)
                .show();
    }

    @Override
    public void onCallAlertReceived(String fromCallsign, int channel) {
        vibratePttEngage();
        Toast.makeText(
                        this,
                        "Incoming Call Alert from "
                                + fromCallsign
                                + " on "
                                + WalkiePacket.getChannelCode(channel),
                        Toast.LENGTH_SHORT)
                .show();
    }

    private void populatePeersContainer(List<PeerInfo> peers, int currentChannel) {
        peersListContainer.removeAllViews();
        if (peers == null || peers.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(
                    "Waiting for other WifiTalkie users on local Wi-Fi (Auto-discovery active)...");
            empty.setTextColor(MaterialOledPalette.TEXT_MUTED);
            empty.setTypeface(Typeface.create("sans-serif", Typeface.ITALIC));
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            empty.setPadding(dp(4), dp(6), dp(4), dp(6));
            peersListContainer.addView(empty);
            return;
        }

        for (int i = 0; i < peers.size(); i++) {
            final PeerInfo p = peers.get(i);
            boolean sameChannel = (p.channel == currentChannel);
            boolean talking = p.isTalking();

            int chipAccent = talking
                    ? MaterialOledPalette.STATE_RX_AMBER
                    : (sameChannel
                            ? MaterialOledPalette.STATE_TX_EMERALD
                            : MaterialOledPalette.M3_PRIMARY_BLUE);

            TextView chip = new TextView(this);
            chip.setPadding(dp(12), dp(7), dp(12), dp(7));
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            chip.setTextColor(MaterialOledPalette.TEXT_PRIMARY);

            String stateTag = talking ? "TALKING" : (sameChannel ? "SAME CH" : "TAP TO JOIN");
            chip.setText(String.format(
                    Locale.US,
                    "● %s [%s • %s]",
                    p.callsign,
                    WalkiePacket.getChannelCode(p.channel),
                    stateTag));
            chip.setBackground(new LiquidGlassDrawable(dp(16), dp(1.3f), chipAccent, true));
            chip.setOnClickListener(v -> {
                selectChannelWithFeedback(p.channel);
                Toast.makeText(
                                MainActivity.this,
                                "Switched to "
                                        + WalkiePacket.getChannelCode(p.channel)
                                        + " with "
                                        + p.callsign,
                                Toast.LENGTH_SHORT)
                        .show();
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                lp.leftMargin = dp(8);
            }
            peersListContainer.addView(chip, lp);
        }
    }

    // --- Dialogs (Channel Matrix, Callsign Edit, Manual IP) ---

    private void showChannelMatrixDialog() {
        vibrateShortClick();
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(12), dp(16), dp(12));
        list.setBackgroundColor(MaterialOledPalette.OLED_OBSIDIAN);

        AlertDialog.Builder builder =
                new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("Select Walkie-Talkie Channel (1 - 16)");

        final AlertDialog[] dialogRef = new AlertDialog[1];

        for (int ch = WalkiePacket.MIN_CHANNEL; ch <= WalkiePacket.MAX_CHANNEL; ch++) {
            final int targetCh = ch;
            boolean isCurrent = (ch == engine.getCurrentChannel());
            int count = (ch < latestChannelCounts.length) ? latestChannelCounts[ch] : 0;

            StringBuilder peersNames = new StringBuilder();
            for (PeerInfo p : latestPeers) {
                if (p.channel == ch) {
                    if (peersNames.length() > 0) peersNames.append(", ");
                    peersNames.append(p.callsign);
                    if (p.isTalking()) peersNames.append(" (TALKING)");
                }
            }

            TextView item = new TextView(this);
            item.setPadding(dp(14), dp(11), dp(14), dp(11));
            item.setTextColor(MaterialOledPalette.TEXT_PRIMARY);
            item.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);

            String line = WalkiePacket.getChannelCode(ch)
                    + " • "
                    + WalkiePacket.getChannelName(ch)
                    + " ("
                    + WalkiePacket.getChannelFrequencyMhz(ch)
                    + ")";
            if (count > 0) {
                line += "\n   Active Users (" + count + "): " + peersNames;
            } else {
                line += "\n   Clear • 0 Users";
            }

            item.setText(line);
            int accent = isCurrent
                    ? MaterialOledPalette.M3_PRIMARY_AQUA
                    : (count > 0
                            ? MaterialOledPalette.STATE_TX_EMERALD
                            : MaterialOledPalette.M3_SECONDARY_SLATE);
            item.setBackground(new LiquidGlassDrawable(
                    dp(16), dp(1.3f), accent, isCurrent || count > 0));
            item.setOnClickListener(v -> {
                selectChannelWithFeedback(targetCh);
                if (dialogRef[0] != null) {
                    dialogRef[0].dismiss();
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            list.addView(item, lp);
        }

        scroll.addView(list);
        builder.setView(scroll);
        builder.setNegativeButton("Close", null);
        dialogRef[0] = builder.create();
        dialogRef[0].show();
    }

    private void showEditCallsignDialog() {
        vibrateShortClick();
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(engine.getLocalCallsign());
        input.setTextColor(MaterialOledPalette.TEXT_PRIMARY);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(16)});
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Set Walkie-Talkie Callsign")
                .setMessage("Visible to other WifiTalkie users on the same local Wi-Fi:")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String val = input.getText().toString().trim();
                    if (!val.isEmpty()) {
                        prefs.edit().putString(KEY_CALLSIGN, val).apply();
                        engine.setLocalCallsign(val);
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAddDirectIpDialog() {
        vibrateShortClick();
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("e.g. 192.168.1.45");
        input.setInputType(InputType.TYPE_CLASS_PHONE);
        input.setTextColor(MaterialOledPalette.TEXT_PRIMARY);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Add Peer IP (Optional)")
                .setMessage(
                        "WifiTalkie automatically discovers users on the same Wi-Fi via Multicast & Broadcast.\n\n"
                                + "If your router isolates broadcast packets, enter the other user's Wi-Fi IP:")
                .setView(input)
                .setPositiveButton("Connect Peer", (d, w) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.isEmpty()) {
                        engine.addManualPeerIp(ip);
                        Toast.makeText(this, "Added direct peer " + ip, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // --- Hardware Volume Down PTT Key Support ---

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!volumeKeyHeldPtt && event.getRepeatCount() == 0) {
                volumeKeyHeldPtt = true;
                handlePttHoldStart();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (volumeKeyHeldPtt) {
                volumeKeyHeldPtt = false;
                handlePttHoldRelease();
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    // --- Permissions, Haptics & Helpers ---

    private boolean hasRecordAudioPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRequiredPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    private void startWalkieServiceSafe() {
        try {
            Intent svc = new Intent(this, WalkieTalkieService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
        } catch (Throwable ignored) {
        }
    }

    private String getOrCreateDeviceId() {
        String existing = prefs.getString(KEY_DEVICE_ID, null);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String androidId = null;
        try {
            androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
        }
        String generated = (androidId != null && androidId.length() >= 6)
                ? "WT-" + androidId.substring(0, 6).toUpperCase(Locale.US)
                : "WT-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.US);
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply();
        return generated;
    }

    private String getOrCreateCallsign() {
        String existing = prefs.getString(KEY_CALLSIGN, null);
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String model = Build.MODEL != null ? Build.MODEL.replaceAll("[^A-Za-z0-9]", "") : "UNIT";
        if (model.length() > 6) {
            model = model.substring(0, 6).toUpperCase(Locale.US);
        }
        int suffix = (int) (Math.abs(System.currentTimeMillis()) % 90 + 10);
        String callsign = model + "-" + suffix;
        prefs.edit().putString(KEY_CALLSIGN, callsign).apply();
        return callsign;
    }

    private void vibrateShortClick() {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(18L, 120));
                } else {
                    vibrator.vibrate(18L);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void vibratePttEngage() {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(35L, 200));
                } else {
                    vibrator.vibrate(35L);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void vibrateBusyError() {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                            VibrationEffect.createWaveform(new long[] {0, 45, 50, 65}, -1));
                } else {
                    vibrator.vibrate(90L);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private int dp(float val) {
        return Math.round(val * density);
    }
}
