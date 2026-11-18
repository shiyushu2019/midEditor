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
    private final List<RawMetaEvent> rawMetaEvents = new ArrayList<>();

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
        int size = notes.size();
        // 顺序追加（导入 / 生成音符时的典型顺序）走 O(1) 分支：
        // 以前每次都从头线性扫描找插入点，长文件导入是平方级（5 万音符要 1.9 秒）
        if (size == 0 || BY_START.compare(notes.get(size - 1), note) <= 0) {
            notes.add(note);
            return;
        }
        int low = 0;
        int high = size;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (BY_START.compare(notes.get(mid), note) <= 0) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        notes.add(low, note);
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

    /** 我们不建模但必须原样带回的 meta（版权、调号、标记、乐器名……）。 */
    public List<RawMetaEvent> rawMetaEvents() {
        return rawMetaEvents;
    }

    public void addRawMetaEvent(RawMetaEvent event) {
        rawMetaEvents.add(event);
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
     * 把「空隙」实体化成休止符：无声的时间段就是休止符，和平行线同地位（可选、可拖、可锁、可存盘）。
     *
     * <p>
     * 已经存在且与某段空隙重叠的休止符会被**就地改写**（保留 id，所以锁定关系不会断），
     * 落在空隙之外的休止符会被删掉，剩下的空隙补上新的休止符。
     * </p>
     *
     * @return 是否改变了音符列表
     */
    public boolean reconcileRests() {
        java.util.List<GapRests.Span> gaps = GapRests.of(this);
        List<Note> rests = new ArrayList<>();
        for (Note note : notes) {
            if (note.isRest()) {
                rests.add(note);
            }
        }
        if (gaps.isEmpty() && rests.isEmpty()) {
            return false;
        }
        boolean changed = false;
        boolean[] claimed = new boolean[gaps.size()];
        List<Note> obsolete = null;
        rests.sort(BY_START);
        // 空隙按时间升序且互不重叠，休止符也按时间升序 —— 双指针扫一遍即可，O(rests + gaps)。
        // 旧实现是「每条休止符扫全部空隙」（平方级）：2 万音符时单次 reconcile 要 200ms 以上，
        // 而每次编辑 / 拖拽都会调它，长文件因此卡顿。
        int gapCursor = 0;
        for (Note rest : rests) {
            while (gapCursor < gaps.size() && gaps.get(gapCursor).endTick() <= rest.startTick()) {
                gapCursor++;
            }
            int best = -1;
            long bestOverlap = 0;
            // 空隙互不重叠，所以一条休止符最多同时压住两条空隙（跨过一段发声区）
            for (int i = gapCursor; i < gaps.size() && i <= gapCursor + 1; i++) {
                if (claimed[i]) {
                    continue;
                }
                GapRests.Span gap = gaps.get(i);
                long overlap = Math.min(rest.endTick(), gap.endTick())
                        - Math.max(rest.startTick(), gap.startTick());
                if (overlap > bestOverlap) {
                    bestOverlap = overlap;
                    best = i;
                }
            }
            if (best < 0) {
                if (obsolete == null) {
                    obsolete = new ArrayList<>();
                }
                obsolete.add(rest); // 这段已经不是空隙了
                changed = true;
                continue;
            }
            claimed[best] = true;
            GapRests.Span gap = gaps.get(best);
            if (rest.pitch() != gap.pitch() || rest.startTick() != gap.startTick()
                    || rest.durationTicks() != Math.max(1, gap.lengthTicks())) {
                rest.setPitch(gap.pitch());
                rest.setStartTick(gap.startTick());
                rest.setDurationTicks(Math.max(1, gap.lengthTicks()));
                changed = true;
            }
        }
        if (obsolete != null) {
            // removeAll(Collection) 在 ArrayList 上是 O(n × k)，长文件同样会爆；用身份集合 + removeIf 保持线性
            java.util.Set<Note> gone = java.util.Collections
                    .newSetFromMap(new java.util.IdentityHashMap<>());
            gone.addAll(obsolete);
            notes.removeIf(gone::contains);
        }
        for (int i = 0; i < gaps.size(); i++) {
            if (claimed[i]) {
                continue;
            }
            GapRests.Span gap = gaps.get(i);
            // 先追加再统一排序：addNote(index) 是线性插入，缺口多时会退化成平方级
            notes.add(Note.rest(gap.pitch(), gap.startTick(), Math.max(1, gap.lengthTicks())));
            changed = true;
        }
        if (changed) {
            sortNotes();
        }
        return changed;
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
