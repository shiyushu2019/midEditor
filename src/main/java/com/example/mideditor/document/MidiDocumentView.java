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

    private final GridSettings grid = new GridSettings();
    private final PianoRollView pianoRoll;
    private final TransportBar transport;
    private final Timer uiTimer;
    private final Map<UUID, long[]> selectionOrigin = new HashMap<>();

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
        refresh();
    }

    // ---------------------------------------------------------------- 组件访问

    public GridSettings grid() {
        return grid;
    }

    public PianoRollView pianoRoll() {
        return pianoRoll;
    }

    public Track activeTrack() {
        return activeTrack;
    }

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
        loopOn = !loopOn;
        updateLoopFromSelectionOrDocument();
        PlaybackEngine engine = app().playback();
        if (engine.owner() == this) {
            engine.setLoopEnabled(loopOn, loopRange());
        }
        refresh();
        app().refreshApplicationState();
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
        updateLoopFromSelectionOrDocument();
        return new PlaybackEngine.LoopRange(loopStart, loopEnd);
    }

    private void updateLoopFromSelectionOrDocument() {
        if (!selection().isEmpty()) {
            loopStart = selection().minStartTick(document());
            loopEnd = Math.max(loopStart + 1, selection().maxEndTick(document()));
        } else {
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

    /** 新建平行线的默认长度：支持「跟随网格 / 固定拍值 / 1 小节 / 自定义 tick」。 */
    public long defaultNoteDuration() {
        if (grid.noteLength() == GridSettings.NoteLength.BAR) {
            return Math.max(1, TimeMath.barTicks(document(), pendingAddTick));
        }
        return grid.newNoteDuration(document().ppq());
    }

    /** 单击平行线试听：立即用该音轨的音色发一声，播放中不叠加。 */
    public void auditionNote(Track track, Note note) {
        if (track == null || note == null || !app().synth().available()) {
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
        for (Track track : tracks) {
            track.notes().removeAll(selected);
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
                track.addNote(left);
                track.addNote(right);
                created.add(left);
                created.add(right);
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
        for (Track track : tracks) {
            for (Note note : track.notes()) {
                if (!selection().contains(note)) {
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

    public void resizeSelection(long deltaTicks) {
        if (selection().isEmpty()) {
            return;
        }
        List<Track> tracks = selection().tracksOf(document());
        beginMultiEdit(tracks);
        for (Track track : tracks) {
            for (Note note : track.notes()) {
                if (!selection().contains(note)) {
                    continue;
                }
                note.setDurationTicks(Math.max(1, note.durationTicks() + deltaTicks));
            }
        }
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
        });
    }

    public void deleteRest(long tick, long length) {
        Track track = activeTrack();
        if (track == null || length <= 0) {
            return;
        }
        edit(track, "删除休止", () -> {
            track.notes().removeIf(note -> note.startTick() >= tick && note.endTick() <= tick + length);
            for (Note note : track.notes()) {
                if (note.startTick() >= tick + length) {
                    note.setStartTick(Math.max(0, note.startTick() - length));
                }
            }
            track.sortNotes();
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
                Note note = new Note(item.pitch(), start + item.startOffset(), item.duration(), item.velocity());
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
        for (Track track : tracks) {
            for (Note note : track.notes()) {
                if (selection().contains(note)) {
                    selectionOrigin.put(note.id(),
                            new long[] { note.startTick(), note.pitch(), note.durationTicks() });
                }
            }
        }
    }

    public void applySelectionMove(long deltaTicks, int deltaPitch) {
        int ppq = document().ppq();
        for (Track track : document().tracks()) {
            boolean changed = false;
            for (Note note : track.notes()) {
                long[] origin = selectionOrigin.get(note.id());
                if (origin == null) {
                    continue;
                }
                long targetStart = origin[0] + deltaTicks;
                if (grid.snapEnabled()) {
                    targetStart = grid.snap(targetStart, ppq);
                }
                note.setStartTick(Math.max(0, targetStart));
                note.setPitch((int) Math.max(0, Math.min(127, origin[1] + deltaPitch)));
                changed = true;
            }
            if (changed) {
                track.sortNotes();
            }
        }
    }

    public void applySelectionResize(long deltaTicks) {
        for (Track track : document().tracks()) {
            for (Note note : track.notes()) {
                long[] origin = selectionOrigin.get(note.id());
                if (origin == null) {
                    continue;
                }
                note.setDurationTicks(Math.max(1, origin[2] + deltaTicks));
            }
        }
    }

    public void commitSelectionEdit(String label) {
        selectionOrigin.clear();
        commitMultiEdit(label);
    }

    public void cancelSelectionEdit() {
        selectionOrigin.clear();
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
                    || x.velocity() != y.velocity()) {
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
                Rectangle noteRect = new Rectangle(x1, y, Math.max(3, x2 - x1), PitchRowHeight());
                if (rect.intersects(noteRect)) {
                    result.add(note);
                }
            }
        }
        selection().setAll(result);
    }

    private static int PitchRowHeight() {
        return com.example.mideditor.ui.theme.Theme.ROW_HEIGHT;
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
