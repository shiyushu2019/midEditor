package com.example.mideditor.ui.pianoroll;

import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/** 钢琴卷帘命中结果。 */
public record HitInfo(int laneIndex, Track track, Note note, boolean rightEdge, long tick, int pitch) {

    public boolean onNote() {
        return note != null;
    }

    public boolean onEmptySpace() {
        return track != null && note == null;
    }
}
