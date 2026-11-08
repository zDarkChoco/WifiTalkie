package com.zdarkchoco.wifitalkie.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import com.zdarkchoco.wifitalkie.net.FloorController;
import com.zdarkchoco.wifitalkie.net.WalkiePacket;

/**
 * Floating Liquid Glass Channel Readout + Real-Time 28-Bar Audio Spectrum Monitor.
 */
public final class WaveformVisualizerView extends View {
    private static final int BAR_COUNT = 28;

    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint channelCodePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint channelMetaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint statusTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scanlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF barRect = new RectF();
    private final RectF badgeRect = new RectF();
    private final float[] barHeights = new float[BAR_COUNT];
    private final float density;

    private final LiquidGlassDrawable glassBackground;

    private int channel = 1;
    private FloorController.State floorState = FloorController.State.IDLE;
    private String activeSpeaker = null;
    private int currentPeerCountOnChannel = 0;
    private float targetAmplitude = 0f;
    private float smoothedAmplitude = 0f;
    private float phase = 0f;

    public WaveformVisualizerView(Context context) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;

        this.glassBackground = new LiquidGlassDrawable(
                28f * density,
                1.4f * density,
                MaterialOledPalette.STATE_IDLE_CYAN,
                false);
        setBackground(glassBackground);

        channelCodePaint.setColor(MaterialOledPalette.TEXT_PRIMARY);
        channelCodePaint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        channelCodePaint.setTextSize(26f * density);
        channelCodePaint.setLetterSpacing(0.04f);

        channelMetaPaint.setColor(MaterialOledPalette.TEXT_SECONDARY);
        channelMetaPaint.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        channelMetaPaint.setTextSize(11.5f * density);

        statusTextPaint.setColor(MaterialOledPalette.OLED_BLACK);
        statusTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        statusTextPaint.setTextSize(10.5f * density);
        statusTextPaint.setTextAlign(Paint.Align.CENTER);

        scanlinePaint.setColor(0x14FFFFFF);
        scanlinePaint.setStrokeWidth(1f * density);
    }

    public void updateState(
            int channel,
            FloorController.State state,
            String activeSpeaker,
            int peersOnChannel) {
        this.channel = WalkiePacket.clampChannel(channel);
        this.floorState = state != null ? state : FloorController.State.IDLE;
        this.activeSpeaker = activeSpeaker;
        this.currentPeerCountOnChannel = peersOnChannel;

        int accent = getAccentForState(this.floorState);
        boolean active = this.floorState != FloorController.State.IDLE;
        glassBackground.setAccentColor(accent, active);
        invalidate();
    }

    public void setAudioLevel(int level0To1000) {
        this.targetAmplitude = Math.max(0f, Math.min(1f, level0To1000 / 1000f));
        invalidate();
    }

    private int getAccentForState(FloorController.State st) {
        switch (st) {
            case TX_PRE_BEEP:
                return MaterialOledPalette.STATE_PREBEEP_GOLD;
            case TRANSMITTING:
                return MaterialOledPalette.STATE_TX_EMERALD;
            case RECEIVING:
                return MaterialOledPalette.STATE_RX_AMBER;
            default:
                return MaterialOledPalette.STATE_IDLE_CYAN;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float padH = 20f * density;
        float topY = 26f * density;

        // 1. Channel Code ("CH 01") & Name ("MAIN NET • 446.0063 MHz")
        String chCode = WalkiePacket.getChannelCode(channel);
        String chName = WalkiePacket.getChannelName(channel);
        String freq = WalkiePacket.getChannelFrequencyMhz(channel);
        String peerSuffix = currentPeerCountOnChannel == 1
                ? " • 1 PEER ON CH"
                : (currentPeerCountOnChannel > 1
                        ? " • " + currentPeerCountOnChannel + " PEERS ON CH"
                        : " • CLEAR");

        canvas.drawText(chCode + "  " + chName, padH, topY + 8f * density, channelCodePaint);
        canvas.drawText(freq + peerSuffix, padH, topY + 27f * density, channelMetaPaint);

        // 2. Live Status Pill on top right
        int accent = getAccentForState(floorState);
        String badgeLabel;
        switch (floorState) {
            case TX_PRE_BEEP:
                badgeLabel = "BEEP • WAIT";
                break;
            case TRANSMITTING:
                badgeLabel = "TX • ON AIR";
                break;
            case RECEIVING:
                String spk = (activeSpeaker != null && !activeSpeaker.isEmpty())
                        ? activeSpeaker.toUpperCase(java.util.Locale.US)
                        : "PEER";
                if (spk.length() > 9) spk = spk.substring(0, 9);
                badgeLabel = "RX • " + spk;
                break;
            default:
                badgeLabel = "PTT READY";
                break;
        }

        float badgeW = Math.max(88f * density, statusTextPaint.measureText(badgeLabel) + 22f * density);
        float badgeH = 24f * density;
        float badgeRight = w - padH;
        float badgeTop = 16f * density;
        badgeRect.set(badgeRight - badgeW, badgeTop, badgeRight, badgeTop + badgeH);

        statusBadgePaint.setColor(accent);
        canvas.drawRoundRect(badgeRect, badgeH * 0.5f, badgeH * 0.5f, statusBadgePaint);
        Paint.FontMetrics fm = statusTextPaint.getFontMetrics();
        float textY = badgeRect.centerY() - (fm.ascent + fm.descent) * 0.5f;
        canvas.drawText(badgeLabel, badgeRect.centerX(), textY, statusTextPaint);

        // 3. Divider line
        float divY = 64f * density;
        canvas.drawLine(padH, divY, w - padH, divY, scanlinePaint);

        // 4. 28-Bar Symmetrical Liquid Audio Spectrum Visualizer
        float waveTop = divY + 10f * density;
        float waveBottom = h - 14f * density;
        float waveCenterY = (waveTop + waveBottom) * 0.5f;
        float maxHalfHeight = Math.max(8f * density, (waveBottom - waveTop) * 0.48f);

        boolean activeAudio = floorState == FloorController.State.TRANSMITTING
                || floorState == FloorController.State.RECEIVING
                || floorState == FloorController.State.TX_PRE_BEEP;

        float desiredAmp = activeAudio ? Math.max(0.18f, targetAmplitude) : 0.04f;
        smoothedAmplitude += (desiredAmp - smoothedAmplitude) * 0.28f;
        phase += activeAudio ? 0.26f : 0.07f;

        float totalWaveWidth = w - padH * 2f;
        float slotWidth = totalWaveWidth / BAR_COUNT;
        float barWidth = Math.max(3f * density, slotWidth * 0.56f);

        barPaint.setShader(new LinearGradient(
                0f,
                waveTop,
                0f,
                waveBottom,
                accent,
                MaterialOledPalette.withAlpha(MaterialOledPalette.M3_TERTIARY_VIOLET, 190),
                Shader.TileMode.CLAMP));

        float centerIdx = (BAR_COUNT - 1) * 0.5f;
        for (int i = 0; i < BAR_COUNT; i++) {
            float distFromCenter = Math.abs(i - centerIdx) / centerIdx;
            float envelope = (float) Math.cos(distFromCenter * Math.PI * 0.46);
            float harmonic = 0.55f + 0.45f * (float) Math.sin(phase + i * 0.55f)
                    * (float) Math.cos(phase * 0.7f - i * 0.3f);

            float targetBar = Math.max(0.06f, smoothedAmplitude * envelope * (0.4f + 0.85f * harmonic));
            barHeights[i] += (targetBar - barHeights[i]) * 0.35f;

            float halfH = Math.max(2.5f * density, Math.min(maxHalfHeight, barHeights[i] * maxHalfHeight));
            float left = padH + i * slotWidth + (slotWidth - barWidth) * 0.5f;
            barRect.set(left, waveCenterY - halfH, left + barWidth, waveCenterY + halfH);
            canvas.drawRoundRect(barRect, barWidth * 0.5f, barWidth * 0.5f, barPaint);
        }

        if (activeAudio || smoothedAmplitude > 0.06f) {
            postInvalidateOnAnimation();
        }
    }
}
