package com.example.mideditor.document;

import java.util.List;

import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/**
 * 以「受影响音轨的音符快照」为单位的编辑记录。
 * 结构简单、天然支持批量操作，适合 MIDI 规模（数千平行线）。
 */
public final class TrackSnapshotEdit implements DocumentEdit {

    private final Track track;
    private final List<Note> before;
    private final List<Note> after;
    private final String label;

    public TrackSnapshotEdit(Track track, List<Note> before, List<Note> after, String label) {
        this.track = track;
        this.before = before;
        this.after = after;
        this.label = label;
    }

    public Track track() {
        return track;
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public void undo() {
        track.restoreNotes(before);
    }

    @Override
    public void redo() {
        track.restoreNotes(after);
    }
}
