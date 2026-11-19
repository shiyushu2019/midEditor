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
                // 三连音：三个音占两个的时间。1/8 三连音 = 三个八分占一个四分音符 → ppq/3；
                // 1/16 三连音 = 三个十六分占一个八分音符 → ppq/6。（以前写成了 ppq*2/3 与 ppq/3，整整差一倍）
                case EIGHTH_TRIPLET -> Math.max(1, ppq / 3L);
                case SIXTEENTH_TRIPLET -> Math.max(1, ppq / 6L);
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
    private NoteLength noteLength = NoteLength.FOLLOW_GRID;
    private long customNoteTicks = 240;

    /** 新建平行线时使用的默认长度。 */
    public enum NoteLength {
        FOLLOW_GRID("跟随网格"),
        QUARTER("1/4"),
        EIGHTH("1/8"),
        SIXTEENTH("1/16"),
        THIRTY_SECOND("1/32"),
        BAR("1 小节"),
        CUSTOM("自定义 tick");

        private final String label;

        NoteLength(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public NoteLength noteLength() {
        return noteLength;
    }

    public void setNoteLength(NoteLength noteLength) {
        this.noteLength = noteLength == null ? NoteLength.FOLLOW_GRID : noteLength;
    }

    public long customNoteTicks() {
        return customNoteTicks;
    }

    public void setCustomNoteTicks(long ticks) {
        this.customNoteTicks = Math.max(1, ticks);
    }

    /** 新建平行线的默认长度（1 小节按 4/4 估算，具体小节长度由文档视图决定）。 */
    public long newNoteDuration(int ppq) {
        return switch (noteLength) {
            case FOLLOW_GRID -> {
                long step = step(ppq);
                yield step > 0 ? step : Math.max(1, ppq / 4);
            }
            case QUARTER -> Math.max(1, ppq);
            case EIGHTH -> Math.max(1, ppq / 2);
            case SIXTEENTH -> Math.max(1, ppq / 4);
            case THIRTY_SECOND -> Math.max(1, ppq / 8);
            case BAR -> Math.max(1, ppq * 4L);
            case CUSTOM -> Math.max(1, customNoteTicks);
        };
    }

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
