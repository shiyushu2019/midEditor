package com.example.mideditor.domain;

/**
 * 吸附网格设置。tick 单位，隐藏 0 表示关闭吸附。
 */
public final class GridSettings {

    public enum Resolution {
        OFF("关闭", 0),
        QUARTER("1/4", 1),
        EIGHTH("1/8", 2),
        SIXTEENTH("1/16", 4),
        THIRTY_SECOND("1/32", 8),
        EIGHTH_TRIPLET("1/8 三连音", 3),
        SIXTEENTH_TRIPLET("1/16 三连音", 6);

        private final String label;
        private final int divisions;

        Resolution(String label, int divisions) {
            this.label = label;
            this.divisions = divisions;
        }

        public String label() {
            return label;
        }

        public long ticksPerGrid(int ppq) {
            return switch (this) {
                case OFF -> 0;
                case EIGHTH_TRIPLET -> Math.max(1, ppq * 2L / 3L);
                case SIXTEENTH_TRIPLET -> Math.max(1, ppq / 3L);
                default -> Math.max(1, ppq / divisions);
            };
        }

        public long snap(long tick, int ppq) {
            long step = ticksPerGrid(ppq);
            if (step <= 0) {
                return Math.max(0, tick);
            }
            long snapped = Math.round((double) tick / step) * step;
            return Math.max(0, snapped);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private Resolution resolution = Resolution.SIXTEENTH;
    private boolean visible = true;
    private boolean snapEnabled = true;

    public Resolution resolution() {
        return resolution;
    }

    public void setResolution(Resolution resolution) {
        this.resolution = resolution == null ? Resolution.OFF : resolution;
    }

    public boolean visible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    public boolean snapEnabled() {
        return snapEnabled;
    }

    public void setSnapEnabled(boolean snapEnabled) {
        this.snapEnabled = snapEnabled;
    }

    public long step(int ppq) {
        return resolution.ticksPerGrid(ppq);
    }

    /** 将 tick 吸附到网格；关闭吸附或关闭网格时原样返回。 */
    public long snap(long tick, int ppq) {
        if (!snapEnabled || resolution == Resolution.OFF) {
            return Math.max(0, tick);
        }
        return resolution.snap(tick, ppq);
    }
}
