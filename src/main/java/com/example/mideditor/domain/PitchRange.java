package com.example.mideditor.domain;

/**
 * 音轨可见音高窗口（「频率轴」）：low 为最低可见半音，high 为最高可见半音 + 1。
 *
 * <p>
 * 行数（rows = 可见半音个数）即音域缩放级别：行数越少 → 每行越高（放大），
 * 行数越多 → 每行越矮（可缩放到全音域 0-127）。泳道像素高度固定，
 * 因此音域缩放只改变行高，不影响各音轨的纵向排列。
 * </p>
 */
public record PitchRange(int low, int high) {

    /** 最少可见半音数（最大放大：每行约 20px）。 */
    public static final int MIN_ROWS = 12;
    /** 最多可见半音数（全音域 0-127）。 */
    public static final int MAX_ROWS = 128;

    public PitchRange {
        low = Math.max(0, Math.min(low, 127));
        high = Math.max(low + 1, Math.min(high, 128));
    }

    public int rows() {
        return high - low;
    }

    public boolean contains(int pitch) {
        return pitch >= low && pitch < high;
    }

    /** 上下滑动音域窗口（不改变行数）。 */
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

    /** 以当前窗口中心为锚点改变行数（音域放大 / 缩小）。 */
    public PitchRange withRows(int rows) {
        int target = Math.max(MIN_ROWS, Math.min(MAX_ROWS, rows));
        if (target == rows()) {
            return this;
        }
        return centered((low + high) / 2, target);
    }

    /** 覆盖 [minPitch, maxPitch] 的最小窗口，行数不少于 minRows。 */
    public static PitchRange fitting(int minPitch, int maxPitch, int minRows) {
        int span = Math.max(1, maxPitch - minPitch + 1);
        int rows = Math.max(minRows, span + 2);
        return centered((minPitch + maxPitch) / 2, rows);
    }

    public static PitchRange centered(int pitch, int rows) {
        int safeRows = Math.max(MIN_ROWS, Math.min(MAX_ROWS, rows));
        int low = pitch - safeRows / 2;
        low = Math.max(0, Math.min(low, 128 - safeRows));
        return new PitchRange(low, low + safeRows);
    }

    /**
     * 用给定行数与「窗口顶部音高」（可带小数，通常由鼠标位置反算）构造窗口，并钳位到 0-128。
     * 频率轴缩放时用它把鼠标处的音高钉在同一像素位置上。
     */
    public static PitchRange aligned(int rows, double desiredHigh) {
        int safeRows = Math.max(MIN_ROWS, Math.min(MAX_ROWS, rows));
        int high = (int) Math.round(desiredHigh);
        int low = Math.max(0, Math.min(high - safeRows, 128 - safeRows));
        return new PitchRange(low, low + safeRows);
    }

    /** 全部 128 个半音。 */
    public static PitchRange full() {
        return new PitchRange(0, 128);
    }
}
