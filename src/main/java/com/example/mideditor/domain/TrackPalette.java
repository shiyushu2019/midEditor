package com.example.mideditor.domain;

import java.awt.Color;

/** 音轨配色板。 */
public final class TrackPalette {

    private static final Color[] COLORS = {
            new Color(0x2F6FED), new Color(0x1F9E6B), new Color(0xC9772A),
            new Color(0x8E44AD), new Color(0xB03A48), new Color(0x2C8FA8),
            new Color(0x7A8B12), new Color(0xA2477F), new Color(0x3D5A80),
            new Color(0x9C5B12)
    };

    private TrackPalette() {
    }

    public static Color at(int index) {
        return COLORS[Math.floorMod(index, COLORS.length)];
    }

    public static Color[] all() {
        return COLORS.clone();
    }
}
