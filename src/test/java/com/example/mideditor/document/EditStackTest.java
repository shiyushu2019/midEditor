package com.example.mideditor.document;

import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditStackTest {

    private static Track trackWith(int... pitches) {
        Track track = new Track("音轨", 0, 0, Color.BLUE);
        for (int i = 0; i < pitches.length; i++) {
            track.addNote(new Note(pitches[i], i * 480L, 240, 100));
        }
        return track;
    }

    @Test
    void snapshotEditUndoRedo() {
        Track track = trackWith(60);
        EditStack stack = new EditStack();
        List<Note> before = track.notesCopy();
        track.addNote(new Note(72, 480, 240, 100));
        List<Note> after = track.notesCopy();
        stack.push(new TrackSnapshotEdit(track, before, after, "添加平行线"));

        assertEquals(2, track.notes().size());
        assertTrue(stack.canUndo());
        stack.undo();
        assertEquals(1, track.notes().size());
        stack.redo();
        assertEquals(2, track.notes().size());
    }

    @Test
    void undoStacksAreIsolatedPerDocument() {
        Track trackA = trackWith(60);
        Track trackB = trackWith(48);

        EditStack stackA = new EditStack();
        EditStack stackB = new EditStack();

        List<Note> beforeA = trackA.notesCopy();
        trackA.addNote(new Note(64, 0, 480, 100));
        stackA.push(new TrackSnapshotEdit(trackA, beforeA, trackA.notesCopy(), "A 添加"));

        assertTrue(stackA.canUndo());
        assertFalse(stackB.canUndo());
        stackB.undo();
        assertEquals(2, trackA.notes().size(), "B 的撤销不得影响 A");
        stackA.undo();
        assertEquals(1, trackA.notes().size());
        assertEquals(1, trackB.notes().size());
    }

    @Test
    void pushClearsRedo() {
        Track track = trackWith(60);
        EditStack stack = new EditStack();
        List<Note> before = track.notesCopy();
        track.addNote(new Note(62, 0, 120, 90));
        stack.push(new TrackSnapshotEdit(track, before, track.notesCopy(), "1"));
        stack.undo();
        assertTrue(stack.canRedo());
        List<Note> before2 = track.notesCopy();
        track.addNote(new Note(65, 0, 120, 90));
        stack.push(new TrackSnapshotEdit(track, before2, track.notesCopy(), "2"));
        assertFalse(stack.canRedo());
    }
}
