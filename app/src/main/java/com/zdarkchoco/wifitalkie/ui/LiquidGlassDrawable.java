package com.zdarkchoco.wifitalkie.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Custom Drawable rendering a multi-layered "Liquid Glass" surface over OLED Black:
 * 1. Translucent dark glass base with subtle Material 3 accent tint
 * 2. Top-edge curved liquid lens sheen (white specular reflection)
 * 3. Diagonal refraction border (bright top-left specular rim -> subtle mid -> accent bottom-right)
 */
public final class LiquidGlassDrawable extends Drawable {
    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF sheenRect = new RectF();
    private final Path clipPath = new Path();

    private final float cornerRadiusPx;
    private final float strokeWidthPx;
    private int accentColor;
    private boolean isHighlighted;
    private int glassAlpha = 210;

    public LiquidGlassDrawable(
            float cornerRadiusPx,
            float strokeWidthPx,
            int accentColor,
            boolean isHighlighted) {
        this.cornerRadiusPx = cornerRadiusPx;
        this.strokeWidthPx = strokeWidthPx;
        this.accentColor = accentColor;
        this.isHighlighted = isHighlighted;
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(strokeWidthPx);
        updateShaders(getBounds());
    }

    public void setAccentColor(int accentColor, boolean highlighted) {
        if (this.accentColor != accentColor || this.isHighlighted != highlighted) {
            this.accentColor = accentColor;
            this.isHighlighted = highlighted;
            updateShaders(getBounds());
            invalidateSelf();
        }
    }

    public void setGlassAlpha(int alpha) {
        this.glassAlpha = Math.max(40, Math.min(255, alpha));
        updateShaders(getBounds());
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        updateShaders(bounds);
    }

    private void updateShaders(Rect b) {
        if (b == null || b.width() <= 0 || b.height() <= 0) {
            return;
        }
        float inset = strokeWidthPx * 0.6f;
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);

        float w = rect.width();
        float h = rect.height();
        float r = Math.min(cornerRadiusPx, Math.min(w, h) * 0.5f);

        clipPath.reset();
        clipPath.addRoundRect(rect, r, r, Path.Direction.CW);

        int topFill = isHighlighted
                ? MaterialOledPalette.blend(0xFF121A2B, accentColor, 0.24f)
                : MaterialOledPalette.blend(0xFF0E1422, accentColor, 0.08f);
        int bottomFill = isHighlighted
                ? MaterialOledPalette.blend(0xFF080C15, accentColor, 0.14f)
                : 0xFF070A12;

        basePaint.setShader(new LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                MaterialOledPalette.withAlpha(topFill, glassAlpha),
                MaterialOledPalette.withAlpha(bottomFill, glassAlpha),
                Shader.TileMode.CLAMP));

        // Top liquid glass specular sheen
        sheenRect.set(rect.left, rect.top, rect.right, rect.top + h * 0.48f);
        sheenPaint.setShader(new LinearGradient(
                sheenRect.left,
                sheenRect.top,
                sheenRect.left,
                sheenRect.bottom,
                isHighlighted ? 0x36FFFFFF : 0x22FFFFFF,
                0x00FFFFFF,
                Shader.TileMode.CLAMP));

        // Ambient corner glow inside glass
        glowPaint.setShader(new RadialGradient(
                rect.left + w * 0.2f,
                rect.top + h * 0.15f,
                Math.max(w, h) * 0.85f,
                MaterialOledPalette.withAlpha(accentColor, isHighlighted ? 68 : 26),
                0x00000000,
                Shader.TileMode.CLAMP));

        // Diagonal specular glass rim (Top-Left white highlight -> Mid translucent -> Bottom-Right accent)
        int rimTopLeft = isHighlighted ? 0xB8FFFFFF : 0x6EFFFFFF;
        int rimCenter = MaterialOledPalette.withAlpha(accentColor, isHighlighted ? 110 : 38);
        int rimBottomRight = MaterialOledPalette.withAlpha(accentColor, isHighlighted ? 210 : 95);

        borderPaint.setShader(new LinearGradient(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom,
                new int[] {rimTopLeft, rimCenter, rimBottomRight},
                new float[] {0f, 0.48f, 1f},
                Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        if (rect.isEmpty()) return;
        float r = Math.min(cornerRadiusPx, Math.min(rect.width(), rect.height()) * 0.5f);

        int save = canvas.save();
        canvas.clipPath(clipPath);
        canvas.drawRoundRect(rect, r, r, basePaint);
        canvas.drawRect( rect, glowPaint);
        canvas.drawRect(sheenRect, sheenPaint);
        canvas.restoreToCount(save);

        canvas.drawRoundRect(rect, r, r, borderPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        basePaint.setAlpha(alpha);
        borderPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        basePaint.setColorFilter(colorFilter);
        borderPaint.setColorFilter(colorFilter);
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
