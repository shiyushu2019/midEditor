package com.example.mideditor.domain;

import java.util.UUID;

/**
 * 领域模型中的发声事件。UI 文案统一称「平行线」。
 * 时间单位一律为 tick。
 */
public final class Note {

    private final UUID id;
    private int pitch;
    private long startTick;
    private long durationTicks;
    private int velocity;

    public Note(int pitch, long startTick, long durationTicks, int velocity) {
        this(UUID.randomUUID(), pitch, startTick, durationTicks, velocity);
    }

    public Note(UUID id, int pitch, long startTick, long durationTicks, int velocity) {
        this.id = id;
        this.pitch = clamp(pitch, 0, 127);
        this.startTick = Math.max(0, startTick);
        this.durationTicks = Math.max(1, durationTicks);
        this.velocity = clamp(velocity, 1, 127);
    }

    public UUID id() {
        return id;
    }

    public int pitch() {
        return pitch;
    }

    public void setPitch(int pitch) {
        this.pitch = clamp(pitch, 0, 127);
    }

    public long startTick() {
        return startTick;
    }

    public void setStartTick(long startTick) {
        this.startTick = Math.max(0, startTick);
    }

    public long durationTicks() {
        return durationTicks;
    }

    public void setDurationTicks(long durationTicks) {
        this.durationTicks = Math.max(1, durationTicks);
    }

    public int velocity() {
        return velocity;
    }

    public void setVelocity(int velocity) {
        this.velocity = clamp(velocity, 1, 127);
    }

    public long endTick() {
        return startTick + durationTicks;
    }

    public Note copy() {
        return new Note(id, pitch, startTick, durationTicks, velocity);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    @Override
    public String toString() {
        return "Note[p" + pitch + " @" + startTick + "+" + durationTicks + " v" + velocity + "]";
    }
}
