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
    private final List<RawChannelEvent> rawEvents = new ArrayList<>();

    /** 每个音轨默认可见的音高行数（可用滚轮 / 视图菜单缩放）。 */
    public static final int VISIBLE_ROWS = 30;
    public static final int MIN_PITCH_ROWS = PitchRange.MIN_ROWS;
    public static final int MAX_PITCH_ROWS = PitchRange.MAX_ROWS;

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
        this.yRange = yRange == null ? PitchRange.centered(60, VISIBLE_ROWS) : yRange;
    }

    /** 当前可见半音数（频率轴缩放级别）。 */
    public int pitchRows() {
        return yRange.rows();
    }

    /** 设置可见半音数（以当前音域中心为锚点），返回实际生效的行数。 */
    public int setPitchRows(int rows) {
        yRange = yRange.withRows(rows);
        return yRange.rows();
    }

    /** 音域上下滑动（滑动时行数不变）。 */
    public void shiftPitchRange(int semitones) {
        if (semitones != 0) {
            yRange = yRange.shifted(semitones);
        }
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

    /** 非建模通道事件（弯音、RPN 调音、延音踏板等），读写 .mid 时原样保留。 */
    public List<RawChannelEvent> rawEvents() {
        return rawEvents;
    }

    public void addRawEvent(RawChannelEvent event) {
        rawEvents.add(event);
    }

    public List<RawChannelEvent> rawEventsCopy() {
        return new ArrayList<>(rawEvents);
    }

    public Note noteById(java.util.UUID id) {
        for (Note n : notes) {
            if (n.id().equals(id)) {
                return n;
            }
        }
        return null;
    }

    /**
     * 根据已有平行线自动定位可见音域（导入 / 新建 / 「音域适配」后调用）。
     * 音域跨度超过默认行数时会自动放大窗口，保证所有平行线都可见。
     */
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
        yRange = PitchRange.fitting(min, max, VISIBLE_ROWS);
    }
}
