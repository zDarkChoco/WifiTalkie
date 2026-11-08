package com.zdarkchoco.wifitalkie.ui;

import android.graphics.Color;

/**
 * Google Material 3 Dark Dynamic Color tokens paired with Pure OLED Black (#000000)
 * and Liquid Glass specular highlight tokens.
 */
public final class MaterialOledPalette {
    private MaterialOledPalette() {}

    // Pure OLED Black & Deep Obsidian (>65% of screen area stays pure black for battery life)
    public static final int OLED_BLACK = 0xFF000000;
    public static final int OLED_OBSIDIAN = 0xFF05070D;
    public static final int OLED_CARD_BASE = 0xCC0D131F;

    // Google Material 3 Dark Tone 80 Accents
    public static final int M3_PRIMARY_BLUE = 0xFFA8C7FA;
    public static final int M3_PRIMARY_AQUA = 0xFF5CE1E6;
    public static final int M3_SECONDARY_SLATE = 0xFFBEC6DC;
    public static final int M3_TERTIARY_VIOLET = 0xFFD0BCFF;

    // Semantic Walkie-Talkie State Accents (Material 3 Dark)
    public static final int STATE_IDLE_CYAN = 0xFF7FCFFF;
    public static final int STATE_PREBEEP_GOLD = 0xFFFFDF9E;
    public static final int STATE_TX_EMERALD = 0xFF6DD58C;
    public static final int STATE_RX_AMBER = 0xFFFFB784;
    public static final int STATE_BUSY_CORAL = 0xFFFF897D;

    // Typography
    public static final int TEXT_PRIMARY = 0xFFF2F6FC;
    public static final int TEXT_SECONDARY = 0xFF9BA9BE;
    public static final int TEXT_MUTED = 0xFF5E6D82;

    public static int withAlpha(int rgb, int alpha0To255) {
        int a = Math.max(0, Math.min(255, alpha0To255));
        return (rgb & 0x00FFFFFF) | (a << 24);
    }

    public static int blend(int colorA, int colorB, float ratioB) {
        float r = Math.max(0f, Math.min(1f, ratioB));
        float inv = 1f - r;
        int a = (int) (Color.alpha(colorA) * inv + Color.alpha(colorB) * r);
        int red = (int) (Color.red(colorA) * inv + Color.red(colorB) * r);
        int green = (int) (Color.green(colorA) * inv + Color.green(colorB) * r);
        int blue = (int) (Color.blue(colorA) * inv + Color.blue(colorB) * r);
        return Color.argb(a, red, green, blue);
    }
}
