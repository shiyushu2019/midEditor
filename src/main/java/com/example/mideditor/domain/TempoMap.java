package com.example.mideditor.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 速度映射：tick 与微秒的双向换算。内部只用 tick，微秒仅用于播放调度与显示。
 */
public final class TempoMap {

    public record Segment(long tick, long microsPerQuarter) {
    }

    public static final long DEFAULT_MICROS_PER_QUARTER = 1_000_000L; // 60 BPM

    private final int ppq;
    private final List<Segment> segments = new ArrayList<>();

    public TempoMap(int ppq) {
        this.ppq = Math.max(1, ppq);
        segments.add(new Segment(0, DEFAULT_MICROS_PER_QUARTER));
    }

    private TempoMap(int ppq, List<Segment> source) {
        this.ppq = ppq;
        segments.addAll(source);
    }

    public int ppq() {
        return ppq;
    }

    public List<Segment> segments() {
        return segments;
    }

    public TempoMap copy() {
        return new TempoMap(ppq, segments);
    }

    /** 在指定 tick 设置速度；同一 tick 只保留一条。 */
    public void setTempo(long tick, long microsPerQuarter) {
        long value = Math.max(1, microsPerQuarter);
        long at = Math.max(0, tick);
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            if (s.tick() == at) {
                segments.set(i, new Segment(at, value));
                return;
            }
            if (s.tick() > at) {
                segments.add(i, new Segment(at, value));
                return;
            }
        }
        segments.add(new Segment(at, value));
    }

    public void setBpm(long tick, double bpm) {
        setTempo(tick, Math.max(1, Math.round(60_000_000d / Math.max(1d, bpm))));
    }

    public double bpmAt(long tick) {
        return 60_000_000d / microsPerQuarterAt(tick);
    }

    public long microsPerQuarterAt(long tick) {
        long value = segments.get(0).microsPerQuarter();
        for (Segment s : segments) {
            if (s.tick() <= tick) {
                value = s.microsPerQuarter();
            } else {
                break;
            }
        }
        return value;
    }

    /** tick -> 从 0 起的微秒数。 */
    public long microsAtTick(long tick) {
        if (tick <= 0) {
            return 0;
        }
        double micros = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            long start = s.tick();
            if (start >= tick) {
                break;
            }
            long end = (i + 1 < segments.size()) ? segments.get(i + 1).tick() : Long.MAX_VALUE;
            long span = Math.min(tick, end) - start;
            micros += (double) span * s.microsPerQuarter() / ppq;
        }
        return Math.round(micros);
    }

    /** 微秒 -> tick（microsAtTick 的逆运算）。 */
    public long tickAtMicros(long micros) {
        if (micros <= 0) {
            return 0;
        }
        double remaining = micros;
        for (int i = 0; i < segments.size(); i++) {
            Segment s = segments.get(i);
            long start = s.tick();
            long end = (i + 1 < segments.size()) ? segments.get(i + 1).tick() : Long.MAX_VALUE;
            double spanMicros = (end == Long.MAX_VALUE)
                    ? Double.MAX_VALUE
                    : (double) (end - start) * s.microsPerQuarter() / ppq;
            if (remaining < spanMicros) {
                return start + Math.round(remaining * ppq / s.microsPerQuarter());
            }
            remaining -= spanMicros;
        }
        return 0;
    }

    public void replaceSegments(List<Segment> newSegments) {
        segments.clear();
        segments.addAll(newSegments);
        if (segments.isEmpty() || segments.get(0).tick() != 0) {
            segments.add(0, new Segment(0, DEFAULT_MICROS_PER_QUARTER));
        }
    }
}
