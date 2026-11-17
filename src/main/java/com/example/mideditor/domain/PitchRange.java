package com.example.mideditor.domain;

/** 音轨可见音高窗口（独立垂直滚动）。 */
public record PitchRange(int low, int high) {

    public PitchRange {
        low = Math.max(0, Math.min(low, 127));
        high = Math.max(low + 1, Math.min(high, 128));
    }

    public int rows() {
        return high - low;
    }

    public PitchRange shifted(int delta) {
        int newLow = low + delta;
        if (newLow < 0) {
            newLow = 0;
        }
        if (newLow + rows() > 128) {
            newLow = 128 - rows();
        }
        return new PitchRange(newLow, newLow + rows());
    }

    public static PitchRange centered(int pitch, int rows) {
        int low = pitch - rows / 2;
        low = Math.max(0, Math.min(low, 128 - rows));
        return new PitchRange(low, low + rows);
    }
}
