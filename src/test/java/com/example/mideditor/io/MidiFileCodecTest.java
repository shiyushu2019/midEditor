package com.example.mideditor.io;

import com.example.mideditor.document.MidiDocument;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MidiFileCodecTest {

    @Test
    void roundTripPreservesNotesTracksAndMixerSettings(@TempDir Path dir) throws Exception {
        MidiDocument document = new MidiDocument(480);
        document.setName("往返测试");
        document.tempoMap().setBpm(0, 150);

        Track piano = new Track("钢琴", 0, 0, java.awt.Color.BLUE);
        piano.setVolume(90);
        piano.setPan(48);
        piano.addNote(new Note(60, 0, 480, 100));
        piano.addNote(new Note(64, 480, 240, 80));
        piano.addNote(new Note(67, 720, 720, 120));
        document.addTrack(piano);

        Track bass = new Track("贝斯", 1, 32, java.awt.Color.GREEN);
        bass.addNote(new Note(36, 0, 960, 110));
        document.addTrack(bass);

        Path file = dir.resolve("roundtrip.mid");
        MidiFileCodec.write(document, file);
        MidiDocument loaded = MidiFileCodec.read(file);

        assertEquals(480, loaded.ppq());
        assertEquals(2, loaded.tracks().size());
        assertEquals(150.0, loaded.tempoMap().bpmAt(0), 0.5);
        assertEquals(4, loaded.totalNoteCount());

        Track loadedPiano = loaded.tracks().get(0);
        assertEquals("钢琴", loadedPiano.name());
        assertEquals(0, loadedPiano.program());
        assertEquals(90, loadedPiano.volume());
        assertEquals(48, loadedPiano.pan());
        List<Note> notes = loadedPiano.notes();
        assertEquals(3, notes.size());
        assertEquals(60, notes.get(0).pitch());
        assertEquals(0, notes.get(0).startTick());
        assertEquals(480, notes.get(0).durationTicks());
        assertEquals(100, notes.get(0).velocity());
        assertEquals(67, notes.get(2).pitch());
        assertEquals(720, notes.get(2).startTick());
        assertEquals(720, notes.get(2).durationTicks());

        Track loadedBass = loaded.tracks().get(1);
        assertEquals(32, loadedBass.program());
        assertEquals(1, loadedBass.notes().size());
        assertEquals(36, loadedBass.notes().get(0).pitch());
        assertNotNull(loadedBass.color());
    }

    @Test
    void savedDocumentIsMarkedClean(@TempDir Path dir) throws Exception {
        MidiDocument document = new MidiDocument(480);
        document.addTrack(new Track("音轨 1", 0, 0, java.awt.Color.BLUE));
        document.setDirty(true);
        Path file = dir.resolve("clean.mid");
        MidiFileCodec.write(document, file);
        MidiDocument loaded = MidiFileCodec.read(file);
        assertEquals(false, loaded.dirty());
        assertEquals(file.getFileName().toString(), loaded.displayName());
    }
}
