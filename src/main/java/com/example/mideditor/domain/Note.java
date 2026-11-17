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
    /** 休止符：与平行线同地位（可选中/拖动/锁定），但不发声，界面上画成红色线段。 */
    private boolean rest;
    /** 收尾锁定：本条的头锁在上一条（该 id）的尾上，移动其一整组一起动。 */
    private UUID lockLeaderId;

    public Note(int pitch, long startTick, long durationTicks, int velocity) {
        this(UUID.randomUUID(), pitch, startTick, durationTicks, velocity, false);
    }

    public Note(int pitch, long startTick, long durationTicks, int velocity, boolean rest) {
        this(UUID.randomUUID(), pitch, startTick, durationTicks, velocity, rest);
    }

    public Note(UUID id, int pitch, long startTick, long durationTicks, int velocity) {
        this(id, pitch, startTick, durationTicks, velocity, false);
    }

    public Note(UUID id, int pitch, long startTick, long durationTicks, int velocity, boolean rest) {
        this.id = id;
        this.pitch = clamp(pitch, 0, 127);
        this.startTick = Math.max(0, startTick);
        this.durationTicks = Math.max(1, durationTicks);
        this.velocity = clamp(velocity, 1, 127);
        this.rest = rest;
    }

    /** 新建一条休止符（不发声的平行线）。 */
    public static Note rest(int pitch, long startTick, long durationTicks) {
        return new Note(pitch, startTick, durationTicks, 64, true);
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

    /** 是否休止符（静音占位）。 */
    public boolean isRest() {
        return rest;
    }

    public UUID lockLeaderId() {
        return lockLeaderId;
    }

    /** 设置收尾锁定：本条的头锁到 leader 的尾（null = 解除）。 */
    public void setLockLeaderId(UUID lockLeaderId) {
        this.lockLeaderId = lockLeaderId;
    }

    public boolean locked() {
        return lockLeaderId != null;
    }

    public Note copy() {
        Note copy = new Note(id, pitch, startTick, durationTicks, velocity, rest);
        copy.lockLeaderId = lockLeaderId;
        return copy;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    @Override
    public String toString() {
        return (rest ? "Rest" : "Note") + "[p" + pitch + " @" + startTick + "+" + durationTicks
                + " v" + velocity + (locked() ? " locked" : "") + "]";
    }
}
