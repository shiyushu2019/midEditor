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
    /** 指挥轨（第 0 轨）里我们不建模、但要原样写回的 meta：版权、调号、标记…… */
    private final List<com.example.mideditor.domain.RawMetaEvent> conductorMeta = new ArrayList<>();
    private Path filePath;
    private String name;
    private boolean dirty;
    /** 调号（主音音级 0..11，0 = C）：当前用于输入模式的小键盘简谱与音名浮牌，后续可接 UI / 存盘。 */
    private int keyTonic;
    /** 调式：false = 大调（默认）、true = 小调（自然小调）。 */
    private boolean keyMinor;

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

    /** 调号的主音音级（0 = C、1 = C#/Db … 11 = B），默认 C。 */
    public int keyTonic() {
        return keyTonic;
    }

    public void setKeyTonic(int tonic) {
        this.keyTonic = Math.floorMod(tonic, 12);
    }

    /** 调式：false = 大调、true = 小调。 */
    public boolean keyMinor() {
        return keyMinor;
    }

    public void setKeyMinor(boolean minor) {
        this.keyMinor = minor;
    }

    private static final String[] KEY_NAMES = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B" };

    /** 调号显示名（"C" / "C#" … "B"；小调在后面加小写 m，如 "Am"）。 */
    public static String keyName(int tonic) {
        return KEY_NAMES[Math.floorMod(tonic, 12)];
    }

    public static String keyName(int tonic, boolean minor) {
        return keyName(tonic) + (minor ? "m" : "");
    }

    public List<TimeSignature> timeSignatures() {
        return timeSignatures;
    }

    public List<Track> tracks() {
        return tracks;
    }

    /** 指挥轨里不建模但需要原样保留的 meta（版权、调号、标记等）。 */
    public List<com.example.mideditor.domain.RawMetaEvent> conductorMeta() {
        return conductorMeta;
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
