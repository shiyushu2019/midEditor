package com.example.mideditor.document;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/** 当前文档的选中集合（按 note id 保存，撤销后 id 依然有效）。 */
public final class DocumentSelection {

    private final Set<UUID> ids = new LinkedHashSet<>();
    private final List<Runnable> listeners = new ArrayList<>();

    public boolean isEmpty() {
        return ids.isEmpty();
    }

    public int size() {
        return ids.size();
    }

    public boolean contains(Note note) {
        return note != null && ids.contains(note.id());
    }

    public void clear() {
        if (!ids.isEmpty()) {
            ids.clear();
            fireChanged();
        }
    }

    public void add(Note note) {
        if (note != null && ids.add(note.id())) {
            fireChanged();
        }
    }

    public void remove(Note note) {
        if (note != null && ids.remove(note.id())) {
            fireChanged();
        }
    }

    public void toggle(Note note) {
        if (note == null) {
            return;
        }
        if (!ids.remove(note.id())) {
            ids.add(note.id());
        }
        fireChanged();
    }

    public void setSingle(Note note) {
        ids.clear();
        if (note != null) {
            ids.add(note.id());
        }
        fireChanged();
    }

    public void setAll(List<Note> notes) {
        ids.clear();
        for (Note note : notes) {
            ids.add(note.id());
        }
        fireChanged();
    }

    public List<Note> resolve(MidiDocument document) {
        List<Note> result = new ArrayList<>();
        for (Track track : document.tracks()) {
            for (Note note : track.notes()) {
                if (ids.contains(note.id())) {
                    result.add(note);
                }
            }
        }
        return result;
    }

    /** 选中项涉及的音轨（去重，按文档顺序）。 */
    public List<Track> tracksOf(MidiDocument document) {
        List<Track> result = new ArrayList<>();
        for (Track track : document.tracks()) {
            for (Note note : track.notes()) {
                if (ids.contains(note.id())) {
                    result.add(track);
                    break;
                }
            }
        }
        return result;
    }

    public Track singleTrackOf(MidiDocument document) {
        List<Track> tracks = tracksOf(document);
        return tracks.size() == 1 ? tracks.get(0) : null;
    }

    public long minStartTick(MidiDocument document) {
        long min = Long.MAX_VALUE;
        for (Note note : resolve(document)) {
            min = Math.min(min, note.startTick());
        }
        return min == Long.MAX_VALUE ? 0 : min;
    }

    public long maxEndTick(MidiDocument document) {
        long max = 0;
        for (Note note : resolve(document)) {
            max = Math.max(max, note.endTick());
        }
        return max;
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    private void fireChanged() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }
}
