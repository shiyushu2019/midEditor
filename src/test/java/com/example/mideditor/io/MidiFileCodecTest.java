package com.example.mideditor.io;

import java.nio.file.Path;
import java.util.List;

import javax.sound.midi.ShortMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.mideditor.document.MidiDocument;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.PitchRange;
import com.example.mideditor.domain.RawChannelEvent;
import com.example.mideditor.domain.Track;
import com.example.mideditor.engine.PlaybackPlan;

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
    void roundTripPreservesViewRangeAndTuningEvents(@TempDir Path dir) throws Exception {
        MidiDocument document = new MidiDocument(480);
        Track lead = new Track("主旋律", 0, 40, java.awt.Color.BLUE);
        lead.setYRange(new PitchRange(40, 70));
        lead.addNote(new Note(60, 0, 480, 100));
        lead.addNote(new Note(67, 480, 480, 100));
        // 弯音 + RPN 粗调音（很多 DAW 文件用它们做移调）
        lead.addRawEvent(new RawChannelEvent(0, ShortMessage.PITCH_BEND, 0, 64));
        lead.addRawEvent(new RawChannelEvent(0, ShortMessage.CONTROL_CHANGE, 101, 0));
        lead.addRawEvent(new RawChannelEvent(0, ShortMessage.CONTROL_CHANGE, 100, 2));
        lead.addRawEvent(new RawChannelEvent(0, ShortMessage.CONTROL_CHANGE, 6, 78));
        document.addTrack(lead);

        Path file = dir.resolve("tuning.mid");
        MidiFileCodec.write(document, file);
        MidiDocument loaded = MidiFileCodec.read(file);

        Track loadedLead = loaded.tracks().get(0);
        assertEquals("主旋律", loadedLead.name(), "音域标记不得污染音轨名");
        assertEquals(new PitchRange(40, 70), loadedLead.yRange(), "重新打开后音域窗口应保持不变");
        assertEquals(2, loadedLead.notes().size());
        assertEquals(60, loadedLead.notes().get(0).pitch());
        assertTrue(loadedLead.rawEvents().stream()
                .anyMatch(e -> e.command() == ShortMessage.PITCH_BEND), "弯音事件必须保留");
        assertTrue(loadedLead.rawEvents().stream()
                .anyMatch(e -> e.command() == ShortMessage.CONTROL_CHANGE && e.data1() == 101), "RPN 事件必须保留");

        PlaybackPlan plan = PlaybackPlan.build(loaded, false);
        assertTrue(plan.events().stream()
                .anyMatch(e -> e.command() == ShortMessage.PITCH_BEND), "播放计划应包含弯音事件");
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
