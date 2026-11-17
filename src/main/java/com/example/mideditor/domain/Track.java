package com.example.mideditor.domain;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一个音轨：共享文档时间轴，纵向音高 0-127 可独立滚动。
 */
public final class Track {

    public static final Comparator<Note> BY_START = Comparator
            .comparingLong(Note::startTick)
            .thenComparingInt(Note::pitch);

    private final java.util.UUID id;
    private String name;
    private int channel;
    private int program;
    private Color color;
    private boolean mute;
    private boolean solo;
    private int volume = 100;
    private int pan = 64;
    private boolean drumKit;
    private PitchRange yRange = PitchRange.centered(60, VISIBLE_ROWS);
    private final List<Note> notes = new ArrayList<>();

    /** 每个音轨可见的音高行数（独立垂直滚动窗口）。 */
    public static final int VISIBLE_ROWS = 30;

    public Track(String name, int channel, int program, Color color) {
        this.id = java.util.UUID.randomUUID();
        this.name = name;
        this.channel = channel;
        this.program = program;
        this.color = color;
    }

    public java.util.UUID id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int channel() {
        return channel;
    }

    public void setChannel(int channel) {
        this.channel = channel & 0x0F;
    }

    public int program() {
        return program;
    }

    public void setProgram(int program) {
        this.program = Math.max(0, Math.min(127, program));
    }

    public Color color() {
        return color;
    }

    public void setColor(Color color) {
        this.color = color;
    }

    public boolean mute() {
        return mute;
    }

    public void setMute(boolean mute) {
        this.mute = mute;
    }

    public boolean solo() {
        return solo;
    }

    public void setSolo(boolean solo) {
        this.solo = solo;
    }

    public int volume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = Math.max(0, Math.min(127, volume));
    }

    public int pan() {
        return pan;
    }

    public void setPan(int pan) {
        this.pan = Math.max(0, Math.min(127, pan));
    }

    public boolean drumKit() {
        return drumKit;
    }

    public void setDrumKit(boolean drumKit) {
        this.drumKit = drumKit;
    }

    public PitchRange yRange() {
        return yRange;
    }

    public void setYRange(PitchRange yRange) {
        this.yRange = yRange;
    }

    public List<Note> notes() {
        return notes;
    }

    public void addNote(Note note) {
        int index = notes.size();
        for (int i = 0; i < notes.size(); i++) {
            if (BY_START.compare(notes.get(i), note) > 0) {
                index = i;
                break;
            }
        }
        notes.add(index, note);
    }

    /** 按 pitch 升序、再按起始 tick 升序排序，便于渲染与命中测试。 */
    public void sortNotes() {
        notes.sort(BY_START);
    }

    public List<Note> notesCopy() {
        List<Note> copy = new ArrayList<>(notes.size());
        for (Note n : notes) {
            copy.add(n.copy());
        }
        return copy;
    }

    public void restoreNotes(List<Note> snapshot) {
        notes.clear();
        for (Note n : snapshot) {
            notes.add(n.copy());
        }
    }

    public Note noteById(java.util.UUID id) {
        for (Note n : notes) {
            if (n.id().equals(id)) {
                return n;
            }
        }
        return null;
    }

    /** 根据已有平行线自动定位可见音域（导入 / 新建后调用）。 */
    public void autoRangeFromNotes() {
        if (notes.isEmpty()) {
            yRange = PitchRange.centered(60, VISIBLE_ROWS);
            return;
        }
        int min = 127;
        int max = 0;
        for (Note note : notes) {
            min = Math.min(min, note.pitch());
            max = Math.max(max, note.pitch());
        }
        yRange = PitchRange.centered((min + max) / 2, VISIBLE_ROWS);
    }
}
