package com.example.mideditor.ui.pianoroll;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 共享水平时间轴（缩放），所有音轨与标尺共用。 */
public final class TimelineModel {

    public static final double DEFAULT_PX_PER_QUARTER = 96;
    private static final double MIN_PX_PER_QUARTER = 12;
    private static final double MAX_PX_PER_QUARTER = 960;

    private double pxPerQuarter = DEFAULT_PX_PER_QUARTER;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public double pxPerQuarter() {
        return pxPerQuarter;
    }

    public void setPxPerQuarter(double value) {
        double clamped = Math.max(MIN_PX_PER_QUARTER, Math.min(MAX_PX_PER_QUARTER, value));
        if (Math.abs(clamped - pxPerQuarter) < 0.001) {
            return;
        }
        pxPerQuarter = clamped;
        fireChanged();
    }

    public double zoom() {
        return pxPerQuarter / DEFAULT_PX_PER_QUARTER;
    }

    public void setZoom(double zoom) {
        setPxPerQuarter(DEFAULT_PX_PER_QUARTER * Math.max(0.125, Math.min(8, zoom)));
    }

    public void zoomIn() {
        setPxPerQuarter(pxPerQuarter * 1.25);
    }

    public void zoomOut() {
        setPxPerQuarter(pxPerQuarter / 1.25);
    }

    public void reset() {
        setPxPerQuarter(DEFAULT_PX_PER_QUARTER);
    }

    public int xAtTick(long tick, int ppq) {
        return (int) Math.floor(tick * pxPerQuarter / Math.max(1, ppq));
    }

    public long tickAtX(int x, int ppq) {
        return Math.max(0, Math.round(x * (double) Math.max(1, ppq) / pxPerQuarter));
    }

    public int widthOf(long ticks, int ppq) {
        return (int) Math.ceil(ticks * pxPerQuarter / Math.max(1, ppq));
    }

    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    private void fireChanged() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }
}
