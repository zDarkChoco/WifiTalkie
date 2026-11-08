package com.zdarkchoco.wifitalkie.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import com.zdarkchoco.wifitalkie.net.FloorController;

/**
 * Centerpiece Floating Liquid Glass Push-To-Talk (PTT) Orb View.
 *
 * Features:
 * - Strictly Push-To-Talk (hold ACTION_DOWN to talk, release ACTION_UP to listen)
 * - Liquid Glass 3D sphere with rotating specular rim, curved lens highlight, and internal
 *   real-time voice-reactive liquid wave
 * - Visually locks out when another peer is talking ("WAIT FOR PEER TO FINISH")
 */
public final class LiquidPttOrbView extends View {

    public interface PttGestureListener {
        void onPttHoldStart();
        void onPttHoldRelease();
    }

    private final float density;
    private final Paint sonarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outerHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint orbBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePrimaryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waveSecondaryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lensSheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subtitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path orbClipPath = new Path();
    private final Path wavePath1 = new Path();
    private final Path wavePath2 = new Path();
    private final RectF iconRect = new RectF();
    private final Matrix sweepMatrix = new Matrix();
    private SweepGradient rimSweepGradient;

    private PttGestureListener pttListener;
    private FloorController.State floorState = FloorController.State.IDLE;
    private String activeSpeakerCallsign = null;
    private boolean pointerDown = false;

    private float pressScale = 1.0f;
    private float shakeOffsetX = 0f;
    private float audioLevel = 0f;
    private float smoothedLevel = 0f;
    private float wavePhase = 0f;
    private float rimRotationDeg = 0f;
    private float sonarProgress = 0f;

    public LiquidPttOrbView(Context context) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;

        rimStrokePaint.setStyle(Paint.Style.STROKE);
        rimStrokePaint.setStrokeWidth(3.2f * density);

        innerRimPaint.setStyle(Paint.Style.STROKE);
        innerRimPaint.setStrokeWidth(1.2f * density);
        innerRimPaint.setColor(0x38FFFFFF);

        sonarPaint.setStyle(Paint.Style.STROKE);
        sonarPaint.setStrokeWidth(2.0f * density);

        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(2.6f * density);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);

        titlePaint.setColor(MaterialOledPalette.TEXT_PRIMARY);
        titlePaint.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        titlePaint.setTextSize(17.5f * density);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        titlePaint.setLetterSpacing(0.06f);

        subtitlePaint.setColor(MaterialOledPalette.TEXT_SECONDARY);
        subtitlePaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subtitlePaint.setTextSize(11.5f * density);
        subtitlePaint.setTextAlign(Paint.Align.CENTER);
        subtitlePaint.setLetterSpacing(0.05f);

        setClickable(true);
    }

    public void setPttGestureListener(PttGestureListener listener) {
        this.pttListener = listener;
    }

    public void setFloorState(FloorController.State state, String speakerCallsign) {
        this.floorState = state != null ? state : FloorController.State.IDLE;
        this.activeSpeakerCallsign = speakerCallsign;
        invalidate();
    }

    public void setAudioLevel(int level0To1000) {
        this.audioLevel = Math.max(0f, Math.min(1f, level0To1000 / 1000f));
        invalidate();
    }

    public void triggerBusyShakeAnimation() {
        ValueAnimator shake = ValueAnimator.ofFloat(0f, -14f, 14f, -10f, 10f, -5f, 5f, 0f);
        shake.setDuration(360L);
        shake.addUpdateListener(anim -> {
            shakeOffsetX = (float) anim.getAnimatedValue() * density;
            invalidate();
        });
        shake.start();
    }

    private void animatePressScale(float target) {
        ValueAnimator anim = ValueAnimator.ofFloat(pressScale, target);
        anim.setDuration(140L);
        anim.setInterpolator(new DecelerateInterpolator());
        anim.addUpdateListener(a -> {
            pressScale = (float) a.getAnimatedValue();
            invalidate();
        });
        anim.start();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                pointerDown = true;
                animatePressScale(0.93f);
                if (pttListener != null) {
                    pttListener.onPttHoldStart();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pointerDown) {
                    pointerDown = false;
                    animatePressScale(1.0f);
                    if (pttListener != null) {
                        pttListener.onPttHoldRelease();
                    }
                }
                return true;

            default:
                return super.onTouchEvent(event);
        }
    }

    private int getPrimaryAccent() {
        switch (floorState) {
            case TX_PRE_BEEP:
                return MaterialOledPalette.STATE_PREBEEP_GOLD;
            case TRANSMITTING:
                return MaterialOledPalette.STATE_TX_EMERALD;
            case RECEIVING:
                return MaterialOledPalette.STATE_RX_AMBER;
            default:
                return MaterialOledPalette.M3_PRIMARY_AQUA;
        }
    }

    private int getSecondaryAccent() {
        switch (floorState) {
            case TX_PRE_BEEP:
                return MaterialOledPalette.STATE_IDLE_CYAN;
            case TRANSMITTING:
                return MaterialOledPalette.M3_PRIMARY_AQUA;
            case RECEIVING:
                return MaterialOledPalette.STATE_BUSY_CORAL;
            default:
                return MaterialOledPalette.M3_TERTIARY_VIOLET;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        float cx = w * 0.5f + shakeOffsetX;
        float cy = h * 0.5f;
        float maxRadius = Math.min(w, h) * 0.5f;
        float baseOrbRadius = maxRadius * 0.78f;
        float orbRadius = baseOrbRadius * pressScale;

        int accent1 = getPrimaryAccent();
        int accent2 = getSecondaryAccent();

        boolean isActive = floorState != FloorController.State.IDLE;
        float targetAmp = isActive ? Math.max(0.22f, audioLevel) : 0.06f;
        smoothedLevel += (targetAmp - smoothedLevel) * 0.22f;

        wavePhase += isActive ? 0.14f : 0.045f;
        rimRotationDeg = (rimRotationDeg + (isActive ? 2.4f : 0.7f)) % 360f;
        sonarProgress = (sonarProgress + (isActive ? 0.022f : 0.008f)) % 1.0f;

        // 1. Ambient OLED Radial Glow behind the floating orb
        outerHaloPaint.setShader(new RadialGradient(
                cx,
                cy,
                maxRadius * 0.98f,
                new int[] {
                    MaterialOledPalette.withAlpha(accent1, isActive ? 88 : 42),
                    MaterialOledPalette.withAlpha(accent2, isActive ? 36 : 14),
                    0x00000000
                },
                new float[] {0.45f, 0.78f, 1.0f},
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, maxRadius * 0.98f, outerHaloPaint);

        // 2. Concentric Liquid Sonar Rings when active
        for (int rIdx = 0; rIdx < 2; rIdx++) {
            float p = (sonarProgress + rIdx * 0.5f) % 1.0f;
            float ringR = orbRadius + p * (maxRadius - orbRadius);
            int alpha = (int) ((1.0f - p) * (isActive ? 140 : 48));
            sonarPaint.setColor(MaterialOledPalette.withAlpha(accent1, alpha));
            canvas.drawCircle(cx, cy, ringR, sonarPaint);
        }

        // 3. Clip inside the Liquid Glass Sphere
        orbClipPath.reset();
        orbClipPath.addCircle(cx, cy, orbRadius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(orbClipPath);

        // 3a. Deep Obsidian + Frosted Glass Sphere Background
        orbBodyPaint.setShader(new RadialGradient(
                cx - orbRadius * 0.28f,
                cy - orbRadius * 0.32f,
                orbRadius * 1.35f,
                MaterialOledPalette.blend(0xFF192338, accent1, isActive ? 0.28f : 0.12f),
                0xFF060911,
                Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, orbRadius, orbBodyPaint);

        // 3b. Dual Animated Liquid Waves inside the Orb
        float waterBaseY = cy + orbRadius * (0.48f - smoothedLevel * 0.55f);
        float waveAmp1 = (6f + smoothedLevel * 18f) * density;
        float waveAmp2 = (5f + smoothedLevel * 14f) * density;

        buildWavePath(
                wavePath1,
                cx - orbRadius,
                cx + orbRadius,
                cy + orbRadius,
                waterBaseY,
                waveAmp1,
                orbRadius * 1.4f,
                wavePhase);
        buildWavePath(
                wavePath2,
                cx - orbRadius,
                cx + orbRadius,
                cy + orbRadius,
                waterBaseY + 6f * density,
                waveAmp2,
                orbRadius * 1.2f,
                -wavePhase * 1.25f + 1.4f);

        waveSecondaryPaint.setShader(new LinearGradient(
                cx,
                waterBaseY - waveAmp2,
                cx,
                cy + orbRadius,
                MaterialOledPalette.withAlpha(accent2, isActive ? 120 : 65),
                MaterialOledPalette.withAlpha(accent1, 25),
                Shader.TileMode.CLAMP));
        canvas.drawPath(wavePath2, waveSecondaryPaint);

        wavePrimaryPaint.setShader(new LinearGradient(
                cx,
                waterBaseY - waveAmp1,
                cx,
                cy + orbRadius,
                MaterialOledPalette.withAlpha(accent1, isActive ? 165 : 85),
                MaterialOledPalette.withAlpha(accent2, isActive ? 95 : 40),
                Shader.TileMode.CLAMP));
        canvas.drawPath(wavePath1, wavePrimaryPaint);

        // 3c. Top-Left Curved Liquid Glass Lens Reflection Highlight
        lensSheenPaint.setShader(new LinearGradient(
                cx - orbRadius * 0.5f,
                cy - orbRadius,
                cx + orbRadius * 0.2f,
                cy - orbRadius * 0.05f,
                0x48FFFFFF,
                0x00FFFFFF,
                Shader.TileMode.CLAMP));
        canvas.drawCircle(
                cx - orbRadius * 0.12f,
                cy - orbRadius * 0.38f,
                orbRadius * 0.78f,
                lensSheenPaint);

        canvas.restoreToCount(save);

        // 4. Outer Specular Liquid Glass Rim with Rotating Sweep Highlight
        if (rimSweepGradient == null) {
            rimSweepGradient = new SweepGradient(
                    0f,
                    0f,
                    new int[] {
                        0xD8FFFFFF,
                        MaterialOledPalette.withAlpha(accent1, 180),
                        0x30FFFFFF,
                        MaterialOledPalette.withAlpha(accent2, 210),
                        0xD8FFFFFF
                    },
                    new float[] {0f, 0.25f, 0.5f, 0.78f, 1f});
        }
        sweepMatrix.reset();
        sweepMatrix.postRotate(rimRotationDeg);
        sweepMatrix.postTranslate(cx, cy);
        rimSweepGradient.setLocalMatrix(sweepMatrix);
        rimStrokePaint.setShader(rimSweepGradient);
        canvas.drawCircle(cx, cy, orbRadius, rimStrokePaint);
        canvas.drawCircle(cx, cy, orbRadius - 5.5f * density, innerRimPaint);

        // 5. Center Vector Icon + State Typography
        drawCenterIcon(canvas, cx, cy - 22f * density, accent1);

        String title;
        String subtitle;
        switch (floorState) {
            case TX_PRE_BEEP:
                title = "BEEP...";
                subtitle = "WAIT FOR TONE";
                break;
            case TRANSMITTING:
                title = "TALKING";
                subtitle = "RELEASE TO FINISH";
                break;
            case RECEIVING:
                title = "CHANNEL BUSY";
                String peer = (activeSpeakerCallsign != null && !activeSpeakerCallsign.isEmpty())
                        ? activeSpeakerCallsign.toUpperCase(java.util.Locale.US)
                        : "PEER";
                subtitle = "WAIT FOR " + peer;
                break;
            default:
                title = "HOLD TO TALK";
                subtitle = "PUSH & HOLD PTT";
                break;
        }

        titlePaint.setColor(
                floorState == FloorController.State.RECEIVING
                        ? MaterialOledPalette.STATE_RX_AMBER
                        : MaterialOledPalette.TEXT_PRIMARY);
        subtitlePaint.setColor(MaterialOledPalette.withAlpha(accent1, 230));

        canvas.drawText(title, cx, cy + 24f * density, titlePaint);
        canvas.drawText(subtitle, cx, cy + 44f * density, subtitlePaint);

        postInvalidateOnAnimation();
    }

    private void drawCenterIcon(Canvas canvas, float cx, float cy, int accentColor) {
        iconPaint.setColor(MaterialOledPalette.TEXT_PRIMARY);
        float s = 14f * density;

        if (floorState == FloorController.State.RECEIVING) {
            // Draw Lock / Busy Shield Icon when channel is locked by remote speaker
            iconPaint.setColor(MaterialOledPalette.STATE_RX_AMBER);
            iconRect.set(cx - s * 0.75f, cy - s * 0.1f, cx + s * 0.75f, cy + s * 1.05f);
            canvas.drawRoundRect(iconRect, 4f * density, 4f * density, iconPaint);
            iconRect.set(cx - s * 0.45f, cy - s * 0.85f, cx + s * 0.45f, cy + s * 0.1f);
            canvas.drawArc(iconRect, 180f, 180f, false, iconPaint);
        } else {
            // Draw Walkie-Talkie Microphone Capsule Icon
            iconPaint.setColor(
                    floorState == FloorController.State.TRANSMITTING
                            ? MaterialOledPalette.STATE_TX_EMERALD
                            : accentColor);
            iconRect.set(cx - s * 0.42f, cy - s * 0.95f, cx + s * 0.42f, cy + s * 0.25f);
            canvas.drawRoundRect(iconRect, s * 0.42f, s * 0.42f, iconPaint);

            iconRect.set(cx - s * 0.78f, cy - s * 0.55f, cx + s * 0.78f, cy + s * 0.62f);
            canvas.drawArc(iconRect, 0f, 180f, false, iconPaint);
            canvas.drawLine(cx, cy + s * 0.62f, cx, cy + s * 1.05f, iconPaint);
            canvas.drawLine(cx - s * 0.45f, cy + s * 1.05f, cx + s * 0.45f, cy + s * 1.05f, iconPaint);
        }
    }

    private static void buildWavePath(
            Path out,
            float left,
            float right,
            float bottom,
            float baseY,
            float amplitude,
            float wavelength,
            float phase) {
        out.reset();
        out.moveTo(left, bottom);
        int steps = 28;
        float stepX = (right - left) / steps;
        for (int i = 0; i <= steps; i++) {
            float x = left + i * stepX;
            float angle = (float) ((x / wavelength) * 2.0 * Math.PI + phase);
            float y = baseY + (float) Math.sin(angle) * amplitude;
            out.lineTo(x, y);
        }
        out.lineTo(right, bottom);
        out.close();
    }
}
