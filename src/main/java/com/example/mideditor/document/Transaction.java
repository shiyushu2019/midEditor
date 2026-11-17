package com.example.mideditor.document;

import java.util.List;

import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/**
 * 编辑事务：开始记录快照，commit 时压入一条快照编辑；一次拖拽只产生一条撤销记录。
 */
public final class Transaction {

    private final Track track;
    private final EditStack stack;
    private final List<Note> before;
    private boolean finished;

    public Transaction(Track track, EditStack stack) {
        this.track = track;
        this.stack = stack;
        this.before = track.notesCopy();
    }

    public Track track() {
        return track;
    }

    public void commit(String label) {
        if (finished) {
            return;
        }
        finished = true;
        List<Note> after = track.notesCopy();
        if (!sameNotes(before, after)) {
            stack.push(new TrackSnapshotEdit(track, before, after, label));
        }
    }

    public void rollback() {
        if (finished) {
            return;
        }
        finished = true;
        track.restoreNotes(before);
    }

    private static boolean sameNotes(List<Note> a, List<Note> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            Note x = a.get(i);
            Note y = b.get(i);
            if (!x.id().equals(y.id())
                    || x.pitch() != y.pitch()
                    || x.startTick() != y.startTick()
                    || x.durationTicks() != y.durationTicks()
                    || x.velocity() != y.velocity()) {
                return false;
            }
        }
        return true;
    }
}
