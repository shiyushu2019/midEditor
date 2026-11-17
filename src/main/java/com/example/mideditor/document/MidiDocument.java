package com.example.mideditor.document;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.example.mideditor.domain.GmProgram;
import com.example.mideditor.domain.TempoMap;
import com.example.mideditor.domain.TimeSignature;
import com.example.mideditor.domain.Track;

/**
 * 文档领域模型：一个多音轨 MIDI 工程。不含任何 UI 依赖，不持有撤销栈。
 */
public final class MidiDocument {

    private final UUID id;
    private final int ppq;
    private final TempoMap tempoMap;
    private final List<TimeSignature> timeSignatures = new ArrayList<>();
    private final List<Track> tracks = new ArrayList<>();
    private Path filePath;
    private String name;
    private boolean dirty;

    public MidiDocument(int ppq) {
        this.id = UUID.randomUUID();
        this.ppq = Math.max(1, ppq);
        this.tempoMap = new TempoMap(this.ppq);
        this.timeSignatures.add(TimeSignature.defaultSignature());
    }

    public UUID id() {
        return id;
    }

    public int ppq() {
        return ppq;
    }

    public TempoMap tempoMap() {
        return tempoMap;
    }

    public List<TimeSignature> timeSignatures() {
        return timeSignatures;
    }

    public List<Track> tracks() {
        return tracks;
    }

    public Path filePath() {
        return filePath;
    }

    public void setFilePath(Path filePath) {
        this.filePath = filePath;
        if (filePath != null && (name == null || name.isBlank())) {
            name = filePath.getFileName().toString();
        }
    }

    public String name() {
        return name == null ? "未命名" : name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String displayName() {
        if (filePath != null) {
            return filePath.getFileName().toString();
        }
        return name();
    }

    public boolean dirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public void addTrack(Track track) {
        tracks.add(track);
    }

    public void addTrack(int index, Track track) {
        tracks.add(Math.max(0, Math.min(index, tracks.size())), track);
    }

    public boolean removeTrack(Track track) {
        return tracks.remove(track);
    }

    public void moveTrack(int from, int to) {
        if (from < 0 || from >= tracks.size()) {
            return;
        }
        Track t = tracks.remove(from);
        tracks.add(Math.max(0, Math.min(to, tracks.size())), t);
    }

    /** 分配未占用的 MIDI 通道；鼓组固定 9。 */
    public int allocateChannel(boolean drum) {
        if (drum) {
            return GmProgram.DRUM_CHANNEL;
        }
        boolean[] used = new boolean[16];
        for (Track t : tracks) {
            used[t.channel()] = true;
        }
        for (int ch = 0; ch < 16; ch++) {
            if (ch != GmProgram.DRUM_CHANNEL && !used[ch]) {
                return ch;
            }
        }
        return 0;
    }

    public long endTick() {
        long end = 0;
        for (Track t : tracks) {
            for (var n : t.notes()) {
                end = Math.max(end, n.endTick());
            }
        }
        return end;
    }

    public int totalNoteCount() {
        int count = 0;
        for (Track t : tracks) {
            count += t.notes().size();
        }
        return count;
    }
}
