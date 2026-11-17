package com.example.mideditor.document;

import java.awt.BorderLayout;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.swing.BorderFactory;
import javax.swing.Timer;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.domain.GridSettings;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.PitchRange;
import com.example.mideditor.domain.Track;
import com.example.mideditor.domain.TrackPalette;
import com.example.mideditor.engine.PlaybackEngine;
import com.example.mideditor.engine.PlaybackPlan;
import com.example.mideditor.ui.pianoroll.PianoRollCanvas;
import com.example.mideditor.ui.pianoroll.PianoRollView;
import com.example.mideditor.ui.transport.TransportBar;
import com.example.mideditor.util.Log;

/**
 * 多音轨 MIDI 文档视图（MDI 子窗口内容）：
 * 传输条 + 多轨钢琴卷帘 + 文档私有的编辑事务与撤销栈。
 */
public final class MidiDocumentView extends DocumentView {

    /** 拖拽时“贴上相邻平行线”的吸附半径（像素）。 */
    private static final int EDGE_SNAP_PIXELS = 8;
    /** 休止符实体化与锁定贴齐的收敛轮数上限（正常 1~2 轮就稳定，上限只防病态数据）。 */
    private static final int MAX_SETTLE_ROUNDS = 4;

    private final GridSettings grid = new GridSettings();
    private final PianoRollView pianoRoll;
    private final TransportBar transport;
    private final Timer uiTimer;
    private final Map<UUID, long[]> selectionOrigin = new HashMap<>();
    /** 本大拖拽事务中「用户真正选中的」平行线（不含因收尾锁定被纳入的同伴）。 */
    private final java.util.Set<UUID> primarySelection = new java.util.HashSet<>();
    /** 拖拽休止符时，它所在音轨所有音的原始起点（用于把后面的音整体平移）。 */
    private final Map<UUID, Long> restRippleStarts = new HashMap<>();

    private Track activeTrack;
    private long playheadTick;
    private boolean followPlayhead = true;
    private boolean metronome;
    private boolean loopOn;
    private long loopStart;
    private long loopEnd;
    private int uiTickCounter;

    private List<Track> editTracks;
    private Map<UUID, List<Note>> editSnapshots;

    private long pendingAddTick;
    private int pendingAddPitch = 60;
    private long pendingSplitTick;

    public MidiDocumentView(AppContext app, MidiDocument document) {
        super(app, document);
        setLayout(new BorderLayout());
        activeTrack = document.tracks().isEmpty() ? null : document.tracks().get(0);
        pianoRoll = new PianoRollView(this);
        transport = new TransportBar(this);
        // 规范要求：顶部时间标尺（列头），底部播放控制条
        add(pianoRoll, BorderLayout.CENTER);
        add(transport, BorderLayout.SOUTH);
        setBorder(BorderFactory.createEmptyBorder());

        loopStart = 0;
        loopEnd = Math.max(document.ppq() * 16L, 1);
        selection().addListener(this::onSelectionChanged);
        uiTimer = new Timer(16, e -> onUiTick());
        uiTimer.start();
        // 打开 / 导入已存在的文件时，空隙也要立刻实体化成休止符（否则要等第一次编辑才刷出来）
        reconcileRests(document.tracks());
        refresh();
    }

    // ---------------------------------------------------------------- 组件访问

    public GridSettings grid() {
        return grid;
    }

    public PianoRollView pianoRoll() {
        return pianoRoll;
    }

    @Override
    public Track activeTrack() {
        return activeTrack;
    }

    @Override
    public void setActiveTrack(Track track) {
        if (track != null && !document().tracks().contains(track)) {
            return;
        }
        if (activeTrack == track) {
            return;
        }
        activeTrack = track;
        int index = document().tracks().indexOf(track);
        if (index >= 0) {
            pianoRoll.scrollToLane(index);
        }
        pianoRoll.refreshPitchLabels();
        pianoRoll.repaint();
        app().refreshApplicationState();
    }

    public boolean keyboardRulerVisible() {
        return pianoRoll.keyboardRulerVisible();
    }

    public void setKeyboardRulerVisible(boolean visible) {
        pianoRoll.setKeyboardRulerVisible(visible);
        app().refreshApplicationState();
    }

    public void toggleKeyboardRuler() {
        pianoRoll.toggleKeyboardRuler();
        app().refreshApplicationState();
    }

    /** 是否高亮 Do（C）行。 */
    public boolean cHighlightVisible() {
        return pianoRoll.cHighlightVisible();
    }

    public void toggleCHighlight() {
        pianoRoll.toggleCHighlight();
        app().refreshApplicationState();
    }

    // ---------------------------------------------------------------- 频率轴（音域）

    /** 当前活动音轨可见的半音数（频率轴缩放级别）。 */
    public int activePitchRows() {
        return activeTrack == null ? 0 : activeTrack.pitchRows();
    }

    /** 放大音域：显示更少半音，每行更高。 */
    public void pitchZoomIn() {
        zoomPitchOfActiveTrack(true);
    }

    /** 缩小音域：显示更多半音（最多 128 = 全音域）。 */
    public void pitchZoomOut() {
        zoomPitchOfActiveTrack(false);
    }

    /** 重置为默认 30 个半音（全部音轨）。 */
    public void pitchZoomReset() {
        applyPitchRowsToAllTracks(Track.VISIBLE_ROWS);
    }

    /** 显示全部 128 个音高（全部音轨）。 */
    public void pitchShowAll() {
        applyPitchRowsToAllTracks(Track.MAX_PITCH_ROWS);
    }

    /** 音域适配本音轨已有平行线，保证全部可见。 */
    public void pitchFitToNotes() {
        if (activeTrack == null) {
            return;
        }
        pianoRoll.fitPitchToNotes(activeTrack);
        app().refreshApplicationState();
    }

    /** 设置音域行数（仅活动音轨）。 */
    public void setPitchRows(int rows) {
        if (activeTrack == null) {
            return;
        }
        pianoRoll.setPitchRows(activeTrack, rows);
        app().refreshApplicationState();
    }

    /** 设置音域行数（所有音轨）。 */
    public void applyPitchRowsToAllTracks(int rows) {
        for (Track track : document().tracks()) {
            track.setPitchRows(rows);
        }
        pianoRoll.refreshPitchLabels();
        pianoRoll.repaint();
        app().refreshApplicationState();
    }

    private void zoomPitchOfActiveTrack(boolean in) {
        if (activeTrack == null) {
            return;
        }
        pianoRoll.zoomPitch(activeTrack, in);
        app().refreshApplicationState();
    }

    public long playheadTick() {
        return playheadTick;
    }

    public boolean followPlayhead() {
        return followPlayhead;
    }

    public void setFollowPlayhead(boolean follow) {
        this.followPlayhead = follow;
    }

    public boolean metronome() {
        return metronome;
    }

    public boolean loopEnabled() {
        return loopOn;
    }

    public long loopStart() {
        return loopStart;
    }

    public long loopEnd() {
        return loopEnd;
    }

    public boolean isNoteSounding(Note note) {
        PlaybackEngine engine = app().playback();
        return engine.isPlaying() && engine.owner() == this
                && playheadTick >= note.startTick() && playheadTick < note.endTick();
    }

    // ---------------------------------------------------------------- 播放头

    public void seekTick(long tick) {
        long target = Math.max(0, tick);
        long previous = playheadTick;
        playheadTick = target;
        PlaybackEngine engine = app().playback();
        if (engine.owner() == this && engine.plan() != null && engine.state() != PlaybackEngine.State.STOPPED) {
            engine.seek(target);
        }
        pianoRoll.repaintPlayhead(previous, target);
        transport.refresh();
        app().refreshApplicationState();
    }

    private void onUiTick() {
        PlaybackEngine engine = app().playback();
        if (!engine.isPlaying() || engine.owner() != this) {
            return;
        }
        long tick = engine.currentTick();
        if (tick != playheadTick) {
            long previous = playheadTick;
            playheadTick = tick;
            pianoRoll.repaintPlayhead(previous, tick);
            if (followPlayhead) {
                pianoRoll.scrollToTick(tick);
            }
        }
        transport.refresh();
        if (uiTickCounter++ % 6 == 0) {
            app().refreshApplicationState();
        }
    }

    // ---------------------------------------------------------------- 播放

    public PlaybackPlan buildPlan() {
        return PlaybackPlan.build(document(), metronome);
    }

    public void togglePlayPause() {
        PlaybackEngine engine = app().playback();
        if (!engine.state().equals(PlaybackEngine.State.STOPPED) && engine.owner() != this) {
            engine.stop();
        }
        if (engine.isPlaying() && engine.owner() == this) {
            engine.pause();
            playheadTick = engine.currentTick();
        } else if (engine.state() == PlaybackEngine.State.PAUSED && engine.owner() == this) {
            engine.play(this, buildPlan(), playheadTick, loopRequested(), loopRange());
        } else {
            app().playback().play(this, buildPlan(), playheadTick, loopRequested(), loopRange());
        }
        refresh();
        app().refreshApplicationState();
    }

    public void stopTransport() {
        PlaybackEngine engine = app().playback();
        if (engine.owner() == this) {
            engine.stop();
            playheadTick = 0;
        }
        refresh();
        app().refreshApplicationState();
    }

    public void rewind() {
        seekTick(0);
    }

    public void toggleLoop() {
        setLoopEnabled(!loopOn);
    }

    /** 开关循环播放；开启时保留已设置的循环域。 */
    public void setLoopEnabled(boolean enabled) {
        loopOn = enabled;
        if (enabled) {
            ensureLoopRange();
        }
        syncLoopToEngine();
        refresh();
        app().refreshApplicationState();
    }

    /** 显式设置循环域（自动开启循环，起止自动交换、最短 1 tick）。 */
    public void setLoopRange(long startTick, long endTick) {
        long low = Math.max(0, Math.min(startTick, endTick));
        long high = Math.max(low + 1, Math.max(startTick, endTick));
        loopStart = low;
        loopEnd = high;
        loopOn = true;
        syncLoopToEngine();
        refresh();
        app().refreshApplicationState();
    }

    /** 循环域设为当前选区；无选区时取整篇文档。 */
    public void loopToSelection() {
        if (!selection().isEmpty()) {
            setLoopRange(selection().minStartTick(document()), selection().maxEndTick(document()));
        } else {
            setLoopRange(0, Math.max(document().ppq() * 16L, document().endTick()));
        }
    }

    public void toggleMetronome() {
        metronome = !metronome;
        if (app().playback().owner() == this && app().playback().plan() != null) {
            app().playback().updatePlan(buildPlan());
        }
        refresh();
    }

    private boolean loopRequested() {
        return loopOn;
    }

    private PlaybackEngine.LoopRange loopRange() {
        ensureLoopRange();
        return new PlaybackEngine.LoopRange(loopStart, loopEnd);
    }

    private void syncLoopToEngine() {
        PlaybackEngine engine = app().playback();
        if (engine.owner() == this) {
            engine.setLoopEnabled(loopOn, loopRange());
        }
    }

    /** 循环域未设置（或起止反了）时回退到整篇文档，避免跑出一个空区间。 */
    private void ensureLoopRange() {
        if (loopEnd <= loopStart) {
            loopStart = 0;
            loopEnd = Math.max(document().ppq() * 16L, document().endTick());
        }
    }

    @Override
    public void stopPlayback() {
        PlaybackEngine engine = app().playback();
        if (engine.owner() == this) {
            engine.stop();
        }
        uiTimer.stop();
    }

    @Override
    public void onActivated() {
        pianoRoll.repaint();
        transport.refresh();
        // 切换文档后把键盘焦点交给卷帘，方向键 / Ctrl+←→ 才能立刻用（否则焦点可能停在别的控件上）
        javax.swing.SwingUtilities.invokeLater(this::requestFocusCanvas);
    }

    @Override
    public void disposeView() {
        super.disposeView();
        pianoRoll.removeAll();
    }

    // ---------------------------------------------------------------- 编辑基础

    public void pushEdit(DocumentEdit edit) {
        editStack().push(edit);
        afterEdit(true);
    }

    /** 音色 / 音量 / 声像等音频参数变化后重建播放计划。 */
    public void audioSettingsChanged() {
        PlaybackEngine engine = app().playback();
        if (engine.owner() == this && engine.plan() != null) {
            engine.updatePlan(buildPlan());
        }
        app().refreshApplicationState();
    }

    public void afterEdit(boolean structural) {
        document().setDirty(true);
        // 休止符 = 空隙：每次编辑后重新实体化，保证它和平行线一样可选/可拖/可锁/可存
        reconcileRests(document().tracks());
        if (structural) {
            ensureActiveTrack();
            pianoRoll.refreshTrackStructure();
        } else {
            pianoRoll.repaint();
        }
        PlaybackEngine engine = app().playback();
        if (engine.owner() == this && engine.plan() != null) {
            engine.updatePlan(buildPlan());
        }
        transport.refresh();
        app().refreshApplicationState();
    }

    private void ensureActiveTrack() {
        if (activeTrack == null || !document().tracks().contains(activeTrack)) {
            activeTrack = document().tracks().isEmpty() ? null : document().tracks().get(0);
        }
    }

    private void onSelectionChanged() {
        pianoRoll.repaint();
        app().refreshApplicationState();
    }

    private void edit(Track track, String label, Runnable body) {
        if (track == null) {
            return;
        }
        Transaction transaction = new Transaction(track, editStack());
        body.run();
        transaction.commit(label);
        afterEdit(false);
    }

    // ---------------------------------------------------------------- 平行线编辑

    public void setPendingAdd(long tick, int pitch) {
        this.pendingAddTick = Math.max(0, tick);
        this.pendingAddPitch = Math.max(0, Math.min(127, pitch));
    }

    public void setPendingSplit(long tick) {
        this.pendingSplitTick = Math.max(0, tick);
    }

    public long pendingAddTick() {
        return pendingAddTick;
    }

    public int pendingAddPitch() {
        return pendingAddPitch;
    }

    public long pendingSplitTick() {
        return pendingSplitTick;
    }

    public void addNoteAt(Track track, long startTick, int pitch) {
        if (track == null) {
            return;
        }
        long duration = defaultNoteDuration();
        edit(track, "添加平行线", () -> {
            Note note = new Note(pitch, startTick, duration, 100);
            track.addNote(note);
            selection().setSingle(note);
        });
        pianoRoll.scrollToTick(startTick);
    }

    // ---------------------------------------------------------------- 收尾锁定

    /**
     * 收尾锁定：选中多条时按时间顺序首尾相连；只选一条时与同轨后面最近的一条相连。
     * 连上后下一条的头部会对齐到上一条的尾部，之后移动任意一条整组一起动。
     */
    public void lockSelection() {
        List<Track> tracks = selection().tracksOf(document());
        if (tracks.isEmpty()) {
            return;
        }
        beginMultiEdit(tracks);
        int linked = 0;
        for (Track track : tracks) {
            List<Note> picked = new ArrayList<>();
            for (Note note : track.notes()) {
                if (selection().contains(note)) {
                    picked.add(note);
                }
            }
            picked.sort(Track.BY_START);
            if (picked.size() >= 2) {
                for (int i = 1; i < picked.size(); i++) {
                    if (link(track, picked.get(i - 1), picked.get(i))) {
                        linked++;
                    }
                }
            } else if (picked.size() == 1) {
                Note leader = picked.get(0);
                Note follower = nextAfter(track, leader);
                if (follower != null && link(track, leader, follower)) {
                    linked++;
                }
            }
        }
        if (linked == 0) {
            cancelEditingSilently();
            app().refreshApplicationState();
            return;
        }
        commitMultiEdit("收尾锁定");
        app().refreshApplicationState();
    }

    /** 解除选中平行线的收尾锁定（自己锁别人的、以及别人锁自己的都解开）。 */
    public void unlockSelection() {
        List<Track> tracks = selection().tracksOf(document());
        if (tracks.isEmpty()) {
            return;
        }
        beginMultiEdit(tracks);
        java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
        for (Note note : selection().resolve(document())) {
            ids.add(note.id());
        }
        int removed = 0;
        for (Track track : tracks) {
            for (Note note : track.notes()) {
                if (note.lockLeaderId() == null) {
                    continue;
                }
                if (ids.contains(note.id()) || ids.contains(note.lockLeaderId())) {
                    note.setLockLeaderId(null);
                    removed++;
                }
            }
        }
        if (removed == 0) {
            cancelEditingSilently();
            return;
        }
        commitMultiEdit("解除锁定");
        app().refreshApplicationState();
    }

    private boolean link(Track track, Note leader, Note follower) {
        if (leader == follower || leader.id().equals(follower.lockLeaderId())) {
            return false;
        }
        if (chainContains(track, leader, follower.id())) {
            // 避免 A 锁 B 同时又 B 锁 A（成环会让整组移动 / 贴合变成死循环）
            return false;
        }
        follower.setStartTick(leader.endTick());
        follower.setLockLeaderId(leader.id());
        track.sortNotes();
        return true;
    }

    /** 沿 lockLeaderId 向上追，看链里有没有 targetId。 */
    private boolean chainContains(Track track, Note start, java.util.UUID targetId) {
        Note current = start;
        for (int guard = 0; current != null && guard < 512; guard++) {
            if (current.id().equals(targetId)) {
                return true;
            }
            current = current.lockLeaderId() == null ? null : track.noteById(current.lockLeaderId());
        }
        return false;
    }

    /** 同轨中起点在 leader 之后（含贴合）且最靠前的一条。 */
    private Note nextAfter(Track track, Note leader) {
        Note best = null;
        for (Note note : track.notes()) {
            if (note == leader || selection().contains(note)) {
                continue;
            }
            if (note.startTick() >= leader.endTick() - 1
                    && (best == null || note.startTick() < best.startTick())) {
                best = note;
            }
        }
        return best;
    }

    /** 放弃当前多轨事务（不改任何东西，也不把文档标脏）。 */
    private void cancelEditingSilently() {
        editTracks = null;
        editSnapshots = null;
        pianoRoll.repaint();
    }

    /** 新建平行线的默认长度：支持「跟随网格 / 固定拍值 / 1 小节 / 自定义 tick」。 */
    public long defaultNoteDuration() {
        if (grid.noteLength() == GridSettings.NoteLength.BAR) {
            return Math.max(1, TimeMath.barTicks(document(), pendingAddTick));
        }
        return grid.newNoteDuration(document().ppq());
    }

    /** 单击平行线试听：立即用该音轨的音色发一声，播放中不叠加。 */
    public void auditionNote(Track track, Note note) {
        if (track == null || note == null || note.isRest() || !app().synth().available()) {
            return;
        }
        PlaybackEngine engine = app().playback();
        if (engine.isPlaying()) {
            // 任何文档正在播放时不再叠加试听，避免干扰正在使用的通道 / 音色
            return;
        }
        long micros = document().tempoMap().microsAtTick(note.endTick())
                - document().tempoMap().microsAtTick(note.startTick());
        int millis = (int) Math.max(120, Math.min(1500, micros / 1000));
        int channel = track.drumKit() ? 9 : track.channel();
        app().synth().audition(channel, track.program(), note.pitch(), note.velocity(),
                track.volume(), track.pan(), millis);
    }

    public void deleteSelection() {
        List<Track> tracks = selection().tracksOf(document());
        if (tracks.isEmpty()) {
            return;
        }
        List<Note> selected = selection().resolve(document());
        beginMultiEdit(tracks);
        // 选中休止符 = 想把这段静音填上：把后面的音整体往前提
        List<Note> rests = new ArrayList<>();
        for (Note note : selected) {
            if (note.isRest()) {
                rests.add(note);
            }
        }
        rests.sort(Track.BY_START);
        for (Track track : tracks) {
            for (Note rest : rests) {
                if (!track.notes().contains(rest)) {
                    continue;
                }
                long from = rest.endTick();
                long gap = rest.durationTicks();
                for (Note note : track.notes()) {
                    if (!note.isRest() && note.startTick() >= from) {
                        note.setStartTick(Math.max(0, note.startTick() - gap));
                    }
                }
                track.notes().remove(rest);
                track.sortNotes();
            }
        }
        for (Track track : tracks) {
            track.notes().removeAll(selected);
            // 删掉的前一条如果被锁定引用，就一并清掉，免得留下悬空指向
            java.util.Set<java.util.UUID> gone = new java.util.HashSet<>();
            for (Note note : selected) {
                gone.add(note.id());
            }
            for (Note note : track.notes()) {
                if (note.lockLeaderId() != null && gone.contains(note.lockLeaderId())) {
                    note.setLockLeaderId(null);
                }
            }
        }
        selection().clear();
        commitMultiEdit("删除平行线");
    }

    public void splitSelectedAt(long tick) {
        List<Track> tracks = selection().tracksOf(document());
        if (tracks.isEmpty()) {
            return;
        }
        long ppq = document().ppq();
        long splitTick = grid.snapEnabled() ? grid.snap(tick, (int) ppq) : tick;
        beginMultiEdit(tracks);
        List<Note> created = new ArrayList<>();
        for (Track track : tracks) {
            List<Note> copies = new ArrayList<>(track.notes());
            // 拆分只改长度、不改锁定关系：指向被拆对象的引用要挪到尾段上
            Map<UUID, UUID> retarget = new LinkedHashMap<>();
            for (Note note : copies) {
                if (!selection().contains(note)) {
                    continue;
                }
                long start = note.startTick();
                long end = note.endTick();
                if (splitTick <= start || splitTick >= end) {
                    continue;
                }
                track.notes().remove(note);
                Note left = note.copy();
                left.setDurationTicks(splitTick - start);
                Note right = note.copy();
                right.setStartTick(splitTick);
                right.setDurationTicks(end - splitTick);
                // 尾段的“上一条”就是左段（只做位置关系，不额外扩散锁定）
                right.setLockLeaderId(null);
                track.addNote(left);
                track.addNote(right);
                created.add(left);
                created.add(right);
                retarget.put(note.id(), right.id());
            }
            if (!retarget.isEmpty()) {
                for (Note note : track.notes()) {
                    UUID leader = note.lockLeaderId();
                    if (leader != null && retarget.containsKey(leader)) {
                        note.setLockLeaderId(retarget.get(leader));
                    }
                }
            }
            track.sortNotes();
        }
        commitMultiEdit("拆分平行线");
        selection().setAll(created);
    }

    public void moveSelection(long deltaTicks, int deltaPitch) {
        if (selection().isEmpty()) {
            return;
        }
        List<Track> tracks = selection().tracksOf(document());
        beginMultiEdit(tracks);
        if (rippleSelection(tracks, deltaTicks)) {
            commitMultiEdit("移动休止符");
            return;
        }
        for (Track track : tracks) {
            java.util.Set<java.util.UUID> moving = movingIds(track);
            for (Note note : track.notes()) {
                if (!moving.contains(note.id())) {
                    continue;
                }
                note.setStartTick(Math.max(0, note.startTick() + deltaTicks));
                if (deltaPitch != 0) {
                    note.setPitch(note.pitch() + deltaPitch);
                }
            }
            track.sortNotes();
        }
        commitMultiEdit("移动平行线");
    }

    /** 选中项 + 通过收尾锁定连在一起的整组（用于方向键 / 整组移动）。 */
    private java.util.Set<java.util.UUID> movingIds(Track track) {
        java.util.Set<java.util.UUID> ids = new java.util.HashSet<>();
        for (Note note : track.notes()) {
            if (selection().contains(note)) {
                ids.add(note.id());
            }
        }
        boolean added = true;
        while (added) {
            added = false;
            for (Note note : track.notes()) {
                if (ids.contains(note.id())) {
                    continue;
                }
                boolean linked = note.lockLeaderId() != null && ids.contains(note.lockLeaderId());
                if (!linked) {
                    for (Note other : track.notes()) {
                        if (ids.contains(other.id()) && note.id().equals(other.lockLeaderId())) {
                            linked = true;
                            break;
                        }
                    }
                }
                if (linked) {
                    ids.add(note.id());
                    added = true;
                }
            }
        }
        return ids;
    }

    public void resizeSelection(long deltaTicks) {
        if (selection().isEmpty()) {
            return;
        }
        List<Track> tracks = selection().tracksOf(document());
        beginMultiEdit(tracks);
        if (rippleSelection(tracks, deltaTicks)) {
            commitMultiEdit("调整休止符长度");
            return;
        }
        for (Track track : tracks) {
            for (Note note : track.notes()) {
                if (!selection().contains(note)) {
                    continue;
                }
                note.setDurationTicks(Math.max(1, note.durationTicks() + deltaTicks));
            }
        }
        // 只改了被选中的长度，锁住的同伴靠“移位置”贴回来
        reflowLocks(tracks);
        commitMultiEdit("调整平行线长度");
    }

    public void adjustVelocity(int delta) {
        if (selection().isEmpty()) {
            return;
        }
        List<Track> tracks = selection().tracksOf(document());
        beginMultiEdit(tracks);
        for (Track track : tracks) {
            for (Note note : track.notes()) {
                if (selection().contains(note)) {
                    note.setVelocity(note.velocity() + delta);
                }
            }
        }
        commitMultiEdit("调整平行线力度");
    }

    /** 将选中平行线的力度（粗细）设为指定值。 */
    public void setSelectionVelocity(int velocity) {
        int value = Math.max(1, Math.min(127, velocity));
        if (selection().isEmpty()) {
            return;
        }
        List<Track> tracks = selection().tracksOf(document());
        beginMultiEdit(tracks);
        for (Track track : tracks) {
            for (Note note : track.notes()) {
                if (selection().contains(note)) {
                    note.setVelocity(value);
                }
            }
        }
        commitMultiEdit("调整平行线力度");
    }

    public void quantizeSelection() {
        if (selection().isEmpty()) {
            return;
        }
        int ppq = document().ppq();
        List<Track> tracks = selection().tracksOf(document());
        beginMultiEdit(tracks);
        for (Track track : tracks) {
            for (Note note : track.notes()) {
                if (selection().contains(note)) {
                    note.setStartTick(grid.resolution().snap(note.startTick(), ppq));
                }
            }
            track.sortNotes();
        }
        // 量化只动被选中的；被锁住的关系靠移位置重新贴齐（锁点优先于网格）
        reflowLocks(tracks);
        commitMultiEdit("量化平行线");
    }

    public void insertRest(long tick, long length) {
        Track track = activeTrack();
        if (track == null || length <= 0) {
            return;
        }
        edit(track, "插入休止", () -> {
            for (Note note : track.notes()) {
                if (note.startTick() >= tick) {
                    note.setStartTick(note.startTick() + length);
                }
            }
            track.sortNotes();
            reflowLocks(List.of(track));
        });
    }

    public void deleteRest(long tick, long length) {
        Track track = activeTrack();
        if (track == null || length <= 0) {
            return;
        }
        edit(track, "删除休止", () -> {
            track.notes().removeIf(note -> note.startTick() >= tick && note.endTick() <= tick + length);
            // 被删掉的可能是别人的锁点，清掉悬空引用
            java.util.Set<java.util.UUID> alive = new java.util.HashSet<>();
            for (Note note : track.notes()) {
                alive.add(note.id());
            }
            for (Note note : track.notes()) {
                if (note.lockLeaderId() != null && !alive.contains(note.lockLeaderId())) {
                    note.setLockLeaderId(null);
                }
            }
            for (Note note : track.notes()) {
                if (note.startTick() >= tick + length) {
                    note.setStartTick(Math.max(0, note.startTick() - length));
                }
            }
            track.sortNotes();
            reflowLocks(List.of(track));
        });
    }

    public void selectAll() {
        Track track = activeTrack();
        if (track == null) {
            return;
        }
        selection().setAll(new ArrayList<>(track.notes()));
    }

    public void cutSelection() {
        copySelection();
        deleteSelection();
    }

    public void copySelection() {
        List<Note> notes = selection().resolve(document());
        if (notes.isEmpty()) {
            return;
        }
        app().clipboard().copy(notes);
        Log.info("已复制 " + notes.size() + " 条平行线");
        app().refreshApplicationState();
    }

    public void paste() {
        Track track = activeTrack();
        if (track == null || app().clipboard().isEmpty()) {
            return;
        }
        long start = grid.snap(playheadTick, document().ppq());
        List<Note> created = new ArrayList<>();
        edit(track, "粘贴平行线", () -> {
            for (NoteClipboard.Item item : app().clipboard().items()) {
                Note note = new Note(item.pitch(), start + item.startOffset(), item.duration(), item.velocity(),
                        item.rest());
                track.addNote(note);
                created.add(note);
            }
            track.sortNotes();
            selection().setAll(created);
        });
    }

    // ---------------------------------------------------------------- 拖拽事务

    public void beginSelectionEdit() {
        List<Track> tracks = selection().tracksOf(document());
        beginMultiEdit(tracks);
        selectionOrigin.clear();
        primarySelection.clear();
        restRippleStarts.clear();
        for (Track track : tracks) {
            for (Note note : track.notes()) {
                if (selection().contains(note)) {
                    primarySelection.add(note.id());
                    recordSelectionOrigin(note);
                }
            }
            // 收尾锁定：整条链一起动（否则锁住的关系会被拖开）
            boolean added = true;
            while (added) {
                added = false;
                for (Note note : track.notes()) {
                    if (selectionOrigin.containsKey(note.id())) {
                        continue;
                    }
                    if (linkedToMoving(track, note)) {
                        recordSelectionOrigin(note);
                        added = true;
                    }
                }
            }
        }
        recordRestRipple();
    }

    private void recordSelectionOrigin(Note note) {
        selectionOrigin.put(note.id(),
                new long[] { note.startTick(), note.pitch(), note.durationTicks() });
    }

    /**
     * 选中了休止符时，记下它所在音轨所有音的起点：
     * 拖动/拉长休止符 = 把这段空隙后面的音整体挪开或挪近（自身位置由空隙决定）。
     */
    private void recordRestRipple() {
        restRippleStarts.clear();
        if (!onlyRestsSelected(primarySelection)) {
            return;
        }
        for (Track track : document().tracks()) {
            long from = Long.MAX_VALUE;
            for (Note note : track.notes()) {
                if (note.isRest() && primarySelection.contains(note.id())) {
                    from = Math.min(from, note.endTick());
                }
            }
            if (from == Long.MAX_VALUE) {
                continue;
            }
            for (Note note : track.notes()) {
                if (note.startTick() >= from) {
                    restRippleStarts.put(note.id(), note.startTick());
                }
            }
        }
    }

    /** 按最初记录的位置整体平移“休止符之后的音”（绝对位移，多次拖拽不累积误差）。 */
    private boolean applyRestRipple(long deltaTicks) {
        if (restRippleStarts.isEmpty()) {
            return false;
        }
        for (Track track : document().tracks()) {
            boolean changed = false;
            for (Note note : track.notes()) {
                Long base = restRippleStarts.get(note.id());
                if (base == null || (note.isRest() && primarySelection.contains(note.id()))) {
                    continue;
                }
                long target = Math.max(0, base + deltaTicks);
                if (note.startTick() != target) {
                    note.setStartTick(target);
                    changed = true;
                }
            }
            if (changed) {
                track.sortNotes();
            }
        }
        reconcileRests(document().tracks());
        return true;
    }

    /**
     * 让「休止符 = 空隙」保持一致（实体化，参与选中/拖动/锁定/存盘），并让收尾锁定重新贴齐。
     *
     * <p>
     * 两件事互相影响：贴齐锁定会移动音符（空隙随之改变），而休止符的几何又完全由空隙推导，
     * 所以要反复做到「这一轮什么都没改」为止。
     * </p>
     */
    private void reconcileRests(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return;
        }
        for (int round = 0; round < MAX_SETTLE_ROUNDS; round++) {
            boolean changed = false;
            for (Track track : tracks) {
                changed |= track.reconcileRests();
            }
            changed |= reflowLocks(tracks);
            if (!changed) {
                break;
            }
        }
    }

    /**
     * 选中项里有休止符时，方向键移动 / Shift 调长度都按「拉长或缩短这段静音」处理：
     * 把它后面的音整体平移，休止符自身的几何交给 reconcileRests 重算。
     *
     * @return true 表示这次操作走的是休止符分支
     */
    private boolean rippleSelection(List<Track> tracks, long deltaTicks) {
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (Note note : selection().resolve(document())) {
            ids.add(note.id());
        }
        if (!onlyRestsSelected(ids)) {
            return false;
        }
        boolean hasRest = false;
        long from = Long.MAX_VALUE;
        for (Track track : tracks) {
            for (Note note : track.notes()) {
                if (note.isRest() && selection().contains(note)) {
                    hasRest = true;
                    from = Math.min(from, note.endTick());
                }
            }
        }
        if (!hasRest) {
            return false;
        }
        for (Track track : tracks) {
            boolean changed = false;
            for (Note note : track.notes()) {
                if (note.isRest() || note.startTick() < from) {
                    continue;
                }
                note.setStartTick(Math.max(0, note.startTick() + deltaTicks));
                changed = true;
            }
            if (changed) {
                track.sortNotes();
            }
        }
        reconcileRests(tracks);
        return true;
    }

    /** 选中的全部是休止符（且不为空）时才算“在调空隙”，混选时按普通平行线处理。 */
    private boolean onlyRestsSelected(java.util.Set<UUID> ids) {
        boolean any = false;
        for (Track track : document().tracks()) {
            for (Note note : track.notes()) {
                if (!ids.contains(note.id())) {
                    continue;
                }
                if (!note.isRest()) {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }

    // ----------------------------------------------------------------
    // 外边界延长（Ctrl+←/→）

    /** Ctrl+←/→ 用的步长（与方向键移动、Shift 调长度一致）。 */
    private long gridStepTicks() {
        long step = grid.step(document().ppq());
        return step > 0 ? step : Math.max(1, document().ppq() / 4);
    }

    /**
     * Ctrl+→ / Ctrl+←：只把选区「外边界」往外延长一格，内侧接缝不动。
     *
     * <ul>
     * <li>多条选中时逐组执行；</li>
     * <li>首尾相接 / 重叠的几条算一组，当成整体处理：只动最外侧那一条的外边界，
     * 不会出现「前一条的尾巴插进后一条里面」；</li>
     * <li>延长到相邻的未选中平行线就停住，不制造重叠；</li>
     * <li>往左延长时，若最外侧那条的头部被收尾锁定钉在别人尾巴上（它是跟随者），整组跳过；</li>
     * <li>跟随者会随锁点一起平移（由收尾锁定负责），所以锁住的相邻对也能正确整体伸长；</li>
     * <li>选中项全是休止符时，按「这段静音变长 / 变短」处理（后面的音整体平移）。</li>
     * </ul>
     */
    public void extendSelectionEdge(boolean rightward) {
        if (selection().isEmpty()) {
            return;
        }
        List<Track> tracks = selection().tracksOf(document());
        if (tracks.isEmpty()) {
            return;
        }
        long step = gridStepTicks();
        if (rippleSelection(tracks, rightward ? step : -step)) {
            return; // 选的是休止符：等价于调整这段空隙的长度
        }
        beginMultiEdit(tracks);
        boolean changed = false;
        for (Track track : tracks) {
            List<Note> picked = new ArrayList<>();
            for (Note note : track.notes()) {
                if (selection().contains(note)) {
                    picked.add(note);
                }
            }
            picked.sort(Track.BY_START);
            for (List<Note> cluster : clusters(picked)) {
                changed |= rightward ? extendClusterRight(track, cluster, step)
                        : extendClusterLeft(track, cluster, step);
            }
        }
        if (!changed) {
            // 没地方可延（紧邻其它平行线 / 已到 0）：不产生编辑、不弄脏文档，但要给用户一个提示，
            // 否则按键看起来像“完全没反应”
            cancelEditingSilently();
            if (app().mainFrame() != null) {
                app().mainFrame().statusBar().setMessage(rightward
                        ? "无法向右延长：右侧已紧邻其它平行线"
                        : "无法向左延长：左侧已紧邻其它平行线或已到 0");
            }
            return;
        }
        commitMultiEdit(rightward ? "右端延长" : "左端延长");
        app().refreshApplicationState();
    }

    /** 把「首尾相接 / 重叠」的选中项分成若干组（无空隙即同组）。 */
    private static List<List<Note>> clusters(List<Note> sortedByStart) {
        List<List<Note>> groups = new ArrayList<>();
        List<Note> current = new ArrayList<>();
        long covered = Long.MIN_VALUE;
        for (Note note : sortedByStart) {
            if (!current.isEmpty() && note.startTick() > covered) {
                groups.add(current);
                current = new ArrayList<>();
                covered = Long.MIN_VALUE;
            }
            current.add(note);
            covered = Math.max(covered, note.endTick());
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private boolean extendClusterRight(Track track, List<Note> cluster, long step) {
        Note last = cluster.get(0);
        for (Note note : cluster) {
            if (note.endTick() > last.endTick()) {
                last = note;
            }
        }
        if (last.isRest()) {
            return false; // 休止符的几何由空隙推导，单独拉它没有意义
        }
        long limit = nextUnselectedStart(track, last, cluster);
        long target = Math.min(last.endTick() + step, limit);
        long delta = target - last.endTick();
        if (delta <= 0) {
            return false;
        }
        last.setDurationTicks(last.durationTicks() + delta);
        track.sortNotes();
        return true;
    }

    private boolean extendClusterLeft(Track track, List<Note> cluster, long step) {
        Note first = cluster.get(0);
        for (Note note : cluster) {
            if (note.startTick() < first.startTick()) {
                first = note;
            }
        }
        // 休止符由空隙决定；跟随者的头部被锁点钉住，自己左移会被贴齐逻辑撤销
        if (first.isRest() || first.lockLeaderId() != null) {
            return false;
        }
        long limit = previousUnselectedEnd(track, first, cluster);
        long target = Math.max(0, Math.max(limit, first.startTick() - step));
        long delta = first.startTick() - target;
        if (delta <= 0) {
            return false;
        }
        first.setStartTick(target);
        first.setDurationTicks(first.durationTicks() + delta);
        track.sortNotes();
        return true;
    }

    /**
     * Shift+→ / Shift+←：把选区「外边界」往里收一格（缩短），内侧接缝不动。
     *
     * <ul>
     * <li>Shift+→：左端向右收（右端不动）；Shift+←：右端向左收（左端不动）。</li>
     * <li>多条选中时按组处理，只动最外侧那一条；最短保留 1 tick。</li>
     * <li>左端要被锁点钉住时（它是跟随者）跳过；休止符跳过（几何由空隙推导）。</li>
     * </ul>
     */
    public void shrinkSelectionEdge(boolean fromLeft) {
        if (selection().isEmpty()) {
            return;
        }
        List<Track> tracks = selection().tracksOf(document());
        if (tracks.isEmpty()) {
            return;
        }
        long step = gridStepTicks();
        if (rippleSelection(tracks, fromLeft ? step : -step)) {
            return; // 选的是休止符：等价于把这段静音缩短
        }
        beginMultiEdit(tracks);
        boolean changed = false;
        for (Track track : tracks) {
            List<Note> picked = new ArrayList<>();
            for (Note note : track.notes()) {
                if (selection().contains(note)) {
                    picked.add(note);
                }
            }
            picked.sort(Track.BY_START);
            for (List<Note> cluster : clusters(picked)) {
                changed |= fromLeft ? shrinkClusterFromLeft(track, cluster, step)
                        : shrinkClusterFromRight(track, cluster, step);
            }
        }
        if (!changed) {
            cancelEditingSilently();
            if (app().mainFrame() != null) {
                app().mainFrame().statusBar().setMessage("无法再缩短：已经只剩一格");
            }
            return;
        }
        commitMultiEdit(fromLeft ? "左端右移" : "右端左移");
        app().refreshApplicationState();
    }

    private boolean shrinkClusterFromLeft(Track track, List<Note> cluster, long step) {
        Note first = cluster.get(0);
        for (Note note : cluster) {
            if (note.startTick() < first.startTick()) {
                first = note;
            }
        }
        // 跟随者的头被锁点钉住，不能自己右移
        if (first.isRest() || first.lockLeaderId() != null) {
            return false;
        }
        long target = Math.min(first.endTick() - 1, first.startTick() + step);
        long delta = target - first.startTick();
        if (delta <= 0) {
            return false;
        }
        first.setStartTick(target);
        first.setDurationTicks(first.durationTicks() - delta);
        track.sortNotes();
        return true;
    }

    private boolean shrinkClusterFromRight(Track track, List<Note> cluster, long step) {
        Note last = cluster.get(0);
        for (Note note : cluster) {
            if (note.endTick() > last.endTick()) {
                last = note;
            }
        }
        if (last.isRest()) {
            return false;
        }
        long target = Math.max(last.startTick() + 1, last.endTick() - step);
        long delta = last.endTick() - target;
        if (delta <= 0) {
            return false;
        }
        last.setDurationTicks(last.durationTicks() - delta);
        track.sortNotes();
        return true;
    }

    /**
     * 右侧最近一条「不该挡住我们」的平行线起点，用作右延上限。
     * 跟着我们走的锁定跟随者（它锁在本组某条上）不挡路。
     */
    private long nextUnselectedStart(Track track, Note from, List<Note> cluster) {
        long best = Long.MAX_VALUE;
        for (Note note : track.notes()) {
            // 休止符是派生的几何，不算障碍（延长后它会重新计算）
            if (note.isRest() || cluster.contains(note) || note.startTick() < from.endTick()) {
                continue;
            }
            if (followsCluster(note, cluster)) {
                continue; // 锁在我们尾巴上的跟随者会一起右移
            }
            best = Math.min(best, note.startTick());
        }
        return best;
    }

    /** 左侧最近一条「不该挡住我们」的平行线终点，用作左延上限。 */
    private long previousUnselectedEnd(Track track, Note from, List<Note> cluster) {
        long best = 0;
        for (Note note : track.notes()) {
            if (note.isRest() || cluster.contains(note) || note.endTick() > from.startTick()) {
                continue;
            }
            if (followsCluster(note, cluster)) {
                continue;
            }
            best = Math.max(best, note.endTick());
        }
        return best;
    }

    /** note 是否是本组某一员的锁定跟随者（会随锁点一起移动）。 */
    private static boolean followsCluster(Note note, List<Note> cluster) {
        if (note.lockLeaderId() == null) {
            return false;
        }
        for (Note member : cluster) {
            if (member.id().equals(note.lockLeaderId())) {
                return true;
            }
        }
        return false;
    }

    /** 该平行线是否与正在移动的平行线有锁定关系（双向）。 */
    private boolean linkedToMoving(Track track, Note note) {
        if (note.lockLeaderId() != null && selectionOrigin.containsKey(note.lockLeaderId())) {
            return true;
        }
        for (Note other : track.notes()) {
            if (selectionOrigin.containsKey(other.id()) && note.id().equals(other.lockLeaderId())) {
                return true;
            }
        }
        return false;
    }

    public void applySelectionMove(long deltaTicks, int deltaPitch) {
        int ppq = document().ppq();
        long adjustedDelta = deltaTicks;
        // 纯上下拖动（只改音高）时不碰横坐标；横向拖动才做吸附
        if (grid.snapEnabled() && deltaTicks != 0) {
            // 用“第一个被拖动的平行线”当锚点算吸附量，整体平移，保持多选时的相对位置
            long tolerance = edgeSnapTolerance(ppq);
            Note anchor = firstMovingNote();
            Track anchorTrack = anchor == null ? null : trackOfNote(anchor);
            long[] origin = anchor == null ? null : selectionOrigin.get(anchor.id());
            if (anchor != null && anchorTrack != null && origin != null) {
                long target = origin[0] + deltaTicks;
                long snapped = snapToNeighbour(anchorTrack, anchor, target, origin[2], tolerance, ppq);
                adjustedDelta += snapped - target;
            }
        }
        if (applyRestRipple(adjustedDelta)) {
            return; // 拖的是休止符：后面那一段音跟着挪，几何由 reconcileRests 重新算
        }
        for (Track track : document().tracks()) {
            boolean changed = false;
            for (Note note : track.notes()) {
                long[] origin = selectionOrigin.get(note.id());
                if (origin == null) {
                    continue;
                }
                long targetStart = origin[0] + adjustedDelta;
                note.setStartTick(Math.max(0, targetStart));
                note.setPitch((int) Math.max(0, Math.min(127, origin[1] + deltaPitch)));
                changed = true;
            }
            if (changed) {
                track.sortNotes();
            }
        }
        reconcileRests(document().tracks());
    }

    /** 拖拽吸附的距离阈值（按像素换算成 tick，缩放后手感一致）。 */
    private long edgeSnapTolerance(int ppq) {
        double pxPerQuarter = Math.max(1, pianoRoll.timeline().pxPerQuarter());
        return Math.max(1, Math.round(EDGE_SNAP_PIXELS * ppq / pxPerQuarter));
    }

    private Note firstMovingNote() {
        for (Track track : document().tracks()) {
            for (Note note : track.notes()) {
                if (selectionOrigin.containsKey(note.id())) {
                    return note;
                }
            }
        }
        return null;
    }

    private Track trackOfNote(Note note) {
        for (Track track : document().tracks()) {
            if (track.notes().contains(note)) {
                return track;
            }
        }
        return null;
    }

    /**
     * 移动吸附：先看能不能贴上同泳道里别的平行线的两端（接在上一条后面 / 让出下一条），
     * 贴不上再退回网格吸附。
     */
    private long snapToNeighbour(Track track, Note moving, long targetStart, long duration,
            long tolerance, int ppq) {
        long length = Math.max(1, duration);
        long best = Long.MIN_VALUE;
        long bestDistance = tolerance + 1;
        for (Note other : track.notes()) {
            if (other == moving || selectionOrigin.containsKey(other.id())) {
                continue;
            }
            // 自己的起点贴到别人的终点（紧接上一个平行线）
            long startCandidate = other.endTick();
            long distance = Math.abs(startCandidate - targetStart);
            if (distance <= tolerance && distance < bestDistance) {
                bestDistance = distance;
                best = startCandidate;
            }
            // 自己的终点贴到别人的起点（正好接上下一个平行线）
            long endCandidate = other.startTick() - length;
            distance = Math.abs(endCandidate - targetStart);
            if (distance <= tolerance && distance < bestDistance) {
                bestDistance = distance;
                best = endCandidate;
            }
        }
        if (best != Long.MIN_VALUE) {
            return Math.max(0, best);
        }
        return grid.snap(targetStart, ppq);
    }

    public void applySelectionResize(long deltaTicks) {
        if (applyRestRipple(deltaTicks)) {
            return; // 拉的是休止符：等于拉长/缩短这段静音，后面的音被推开/拉回
        }
        List<Track> touched = new ArrayList<>();
        for (Track track : document().tracks()) {
            boolean changed = false;
            for (Note note : track.notes()) {
                long[] origin = selectionOrigin.get(note.id());
                // 只有用户真正选中的才改长度：锁住的同伴不许“跟着变长/变短”
                if (origin == null || !primarySelection.contains(note.id())) {
                    continue;
                }
                note.setDurationTicks(Math.max(1, origin[2] + deltaTicks));
                changed = true;
            }
            if (changed) {
                touched.add(track);
            }
        }
        // 长度变了之后，锁定关系只靠“移动”对齐（锁点跟着上一条的尾巴走），不改对方长度
        reflowLocks(touched);
    }

    /**
     * 让收尾锁定重新贴齐：后一条的头部 = 前一条的尾巴（只移位置、不改长度），沿着链一直传下去。
     *
     * @return 是否真的移动过什么（调用方可能需要再跑一轮实体化）
     */
    private boolean reflowLocks(List<Track> tracks) {
        boolean touched = false;
        for (Track track : tracks) {
            int guard = 0;
            boolean changed = true;
            while (changed && guard++ < 512) {
                changed = false;
                List<Note> snapshot = new ArrayList<>(track.notes());
                for (Note note : snapshot) {
                    if (note.lockLeaderId() == null) {
                        continue;
                    }
                    Note leader = track.noteById(note.lockLeaderId());
                    if (leader == null || note.startTick() == leader.endTick()) {
                        continue;
                    }
                    note.setStartTick(leader.endTick());
                    changed = true;
                }
                if (changed) {
                    track.sortNotes();
                    touched = true;
                }
            }
        }
        return touched;
    }

    public void commitSelectionEdit(String label) {
        selectionOrigin.clear();
        primarySelection.clear();
        restRippleStarts.clear();
        commitMultiEdit(label);
    }

    public void cancelSelectionEdit() {
        selectionOrigin.clear();
        primarySelection.clear();
        restRippleStarts.clear();
        if (editTracks != null && editSnapshots != null) {
            for (Track track : editTracks) {
                List<Note> before = editSnapshots.get(track.id());
                if (before != null) {
                    track.restoreNotes(before);
                }
            }
        }
        editTracks = null;
        editSnapshots = null;
        afterEdit(false);
    }

    private void beginMultiEdit(List<Track> tracks) {
        editTracks = new ArrayList<>(tracks);
        editSnapshots = new LinkedHashMap<>();
        for (Track track : tracks) {
            editSnapshots.put(track.id(), track.notesCopy());
        }
    }

    private void commitMultiEdit(String label) {
        if (editTracks == null || editSnapshots == null) {
            return;
        }
        // 先把休止符同步成实际空隙，让撤销/重做拍到的快照前后一致
        reconcileRests(editTracks);
        CompositeEdit composite = new CompositeEdit(label);
        for (Track track : editTracks) {
            List<Note> before = editSnapshots.get(track.id());
            List<Note> after = track.notesCopy();
            if (before != null && !notesEqual(before, after)) {
                composite.add(new TrackSnapshotEdit(track, before, after, label));
            }
        }
        editTracks = null;
        editSnapshots = null;
        if (!composite.isEmpty()) {
            editStack().push(composite);
        }
        afterEdit(false);
    }

    private static boolean notesEqual(List<Note> a, List<Note> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Note x = a.get(i);
            Note y = b.get(i);
            if (!x.id().equals(y.id()) || x.pitch() != y.pitch()
                    || x.startTick() != y.startTick() || x.durationTicks() != y.durationTicks()
                    || x.velocity() != y.velocity() || x.isRest() != y.isRest()
                    || !java.util.Objects.equals(x.lockLeaderId(), y.lockLeaderId())) {
                return false;
            }
        }
        return true;
    }

    public void selectInRect(Rectangle rect) {
        if (rect == null) {
            return;
        }
        int ppq = document().ppq();
        List<Note> result = new ArrayList<>();
        for (int i = 0; i < document().tracks().size(); i++) {
            Track track = document().tracks().get(i);
            int laneTop = pianoRoll.laneTop(i);
            int laneBottom = laneTop + pianoRoll.laneHeight();
            if (laneBottom < rect.y || laneTop > rect.y + rect.height) {
                continue;
            }
            for (Note note : track.notes()) {
                int x1 = pianoRoll.timeline().xAtTick(note.startTick(), ppq);
                int x2 = pianoRoll.timeline().xAtTick(note.endTick(), ppq);
                int y = pianoRoll.rowTop(track, i, note.pitch());
                int rowPixels = Math.max(1, pianoRoll.rowBottom(track, i, note.pitch()) - y);
                Rectangle noteRect = new Rectangle(x1, y, Math.max(3, x2 - x1), rowPixels);
                if (rect.intersects(noteRect)) {
                    result.add(note);
                }
            }
        }
        selection().setAll(result);
    }

    // ---------------------------------------------------------------- 速度 / 音轨

    public void setTempo(double bpm) {
        double oldBpm = document().tempoMap().bpmAt(0);
        if (Math.abs(oldBpm - bpm) < 0.01) {
            return;
        }
        document().tempoMap().setBpm(0, bpm);
        pushEdit(SimpleEdit.value("修改速度",
                () -> document().tempoMap().setBpm(0, bpm),
                () -> document().tempoMap().setBpm(0, oldBpm)));
        transport.refresh();
    }

    public void addTrack() {
        int channel = document().allocateChannel(false);
        int index = document().tracks().size();
        Track track = new Track("音轨 " + (index + 1), channel, 0,
                TrackPalette.at(document().tracks().size()));
        track.setYRange(PitchRange.centered(60, com.example.mideditor.ui.theme.Theme.LANE_ROWS));
        document().addTrack(track);
        pushEdit(SimpleEdit.value("添加音轨",
                () -> document().addTrack(track),
                () -> document().removeTrack(track)));
        setActiveTrack(track);
        afterEdit(true);
        pianoRoll.scrollToLane(document().tracks().indexOf(track));
    }

    public void removeTrack(Track track) {
        if (track == null || document().tracks().size() <= 1) {
            return;
        }
        int index = document().tracks().indexOf(track);
        document().removeTrack(track);
        pushEdit(SimpleEdit.value("删除音轨",
                () -> document().removeTrack(track),
                () -> document().addTrack(index, track)));
        afterEdit(true);
    }

    public void moveTrack(Track track, int delta) {
        int index = document().tracks().indexOf(track);
        int target = index + delta;
        if (index < 0 || target < 0 || target >= document().tracks().size()) {
            return;
        }
        document().moveTrack(index, target);
        pushEdit(SimpleEdit.value("调整音轨顺序",
                () -> document().moveTrack(index, target),
                () -> document().moveTrack(target, index)));
        afterEdit(true);
        pianoRoll.scrollToLane(target);
    }

    public void toggleMute(Track track) {
        boolean value = !track.mute();
        boolean old = track.mute();
        track.setMute(value);
        pushEdit(SimpleEdit.value("静音音轨", () -> track.setMute(value), () -> track.setMute(old)));
        audioSettingsChanged();
        pianoRoll.refreshPitchLabels();
    }

    public void toggleSolo(Track track) {
        boolean value = !track.solo();
        boolean old = track.solo();
        track.setSolo(value);
        pushEdit(SimpleEdit.value("独奏音轨", () -> track.setSolo(value), () -> track.setSolo(old)));
        audioSettingsChanged();
        pianoRoll.refreshPitchLabels();
    }

    /** 由命令 / 面板触发统一刷新。 */
    public void refresh() {
        transport.refresh();
        pianoRoll.repaint();
    }

    public void requestFocusCanvas() {
        PianoRollCanvas canvas = pianoRoll.canvas();
        canvas.requestFocusInWindow();
    }
}
