package com.example.mideditor.engine;

import com.example.mideditor.document.MidiDocument;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import org.junit.jupiter.api.Test;

import javax.sound.midi.ShortMessage;
import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PlaybackEngineTest {

    private static MidiDocument demoDocument() {
        MidiDocument document = new MidiDocument(480);
        Track track = new Track("钢琴", 0, 0, Color.BLUE);
        for (int i = 0; i < 8; i++) {
            track.addNote(new Note(60 + i, i * 240L, 240, 100));
        }
        document.addTrack(track);
        return document;
    }

    @Test
    void planContainsSetupNotesAndOffEvents() {
        PlaybackPlan plan = PlaybackPlan.build(demoDocument(), false);
        List<PlaybackPlan.Event> events = plan.events();
        // 1 program + 2 CC + 8 on + 8 off
        assertEquals(19, events.size());
        assertEquals(ShortMessage.PROGRAM_CHANGE, events.get(0).command());
        assertEquals(0, events.get(0).tick());
        assertTrue(plan.endTick() >= 8 * 240L);
    }

    @Test
    void planFiltersMutedTracksAndAddsMetronome() {
        MidiDocument document = demoDocument();
        document.tracks().get(0).setMute(true);
        PlaybackPlan muted = PlaybackPlan.build(document, false);
        assertEquals(0, muted.events().size());

        PlaybackPlan metronome = PlaybackPlan.build(document, true);
        assertTrue(metronome.events().stream()
                .anyMatch(e -> e.channel() == 9 && e.command() == ShortMessage.NOTE_ON));
    }

    @Test
    void schedulerAdvancesClockAndSupportsSeek() throws Exception {
        SynthService synth = new SynthService();
        synth.open();
        try {
            assumeTrue(synth.available(), "当前环境无可用音频设备");
            PlaybackEngine engine = new PlaybackEngine(synth);
            PlaybackPlan plan = PlaybackPlan.build(demoDocument(), false);
            engine.play(this, plan, 0, false, new PlaybackEngine.LoopRange(0, 0));
            Thread.sleep(450);
            long tick = engine.currentTick();
            assertTrue(tick > 0, "播放头应随音频时钟前进，实际 " + tick);
            assertTrue(synth.noteEventsSent() > 0, "应向合成器投递真实音符事件");
            engine.seek(960);
            Thread.sleep(120);
            assertTrue(engine.currentTick() >= 960, "定位后应从新位置继续");

            engine.pause();
            assertEquals(PlaybackEngine.State.PAUSED, engine.state());
            engine.stop();
            assertEquals(PlaybackEngine.State.STOPPED, engine.state());
            assertEquals(0, engine.currentTick());
        } finally {
            synth.close();
        }
    }
}
