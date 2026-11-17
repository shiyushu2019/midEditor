package com.example.mideditor.command;

import java.util.List;

import com.example.mideditor.app.AppContext;
import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/** 命令执行时的上下文快照。 */
public record CommandContext(AppContext app, MidiDocumentView document, Track activeTrack, List<Note> selection) {

    public boolean hasDocument() {
        return document != null;
    }

    public boolean hasTrack() {
        return document != null && activeTrack != null;
    }

    public boolean hasSelection() {
        return hasTrack() && selection != null && !selection.isEmpty();
    }

    public boolean playing() {
        return app != null && app.playback().isPlaying();
    }
}
