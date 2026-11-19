package com.example.mideditor.engine;

import java.awt.Color;
import java.util.List;

import javax.sound.midi.ShortMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.Test;

import com.example.mideditor.document.MidiDocument;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

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
    void sameTickNoteOffPrecedesNextNoteOn() {
        MidiDocument document = new MidiDocument(480);
        Track track = new Track("首尾相接", 0, 0, Color.BLUE);
        track.addNote(new Note(60, 0, 480, 100));
        track.addNote(new Note(60, 480, 480, 100));
        document.addTrack(track);

        PlaybackPlan plan = PlaybackPlan.build(document, false);
        List<PlaybackPlan.Event> atBoundary = plan.events().stream()
                .filter(e -> e.tick() == 480)
                .toList();
        assertEquals(2, atBoundary.size());
        assertEquals(ShortMessage.NOTE_OFF, atBoundary.get(0).command(), "先停止上一条平行线");
        assertEquals(ShortMessage.NOTE_ON, atBoundary.get(1).command(), "再开始下一条平行线");
    }

    @Test
    void stopSilencesSoundingNotes() throws Exception {
        SynthService synth = new SynthService();
        synth.open();
        try {
            assumeTrue(synth.available(), "当前环境无可用音频设备");
            MidiDocument document = new MidiDocument(480);
            document.tempoMap().setBpm(0, 120); // 这条用例靠“1 拍 ≈ 0.5s”的时间窗，别跟着默认 BPM 变
            Track track = new Track("长音", 0, 0, Color.BLUE);
            // 第 1 个音立刻发声并持续 4 小节；第 2 个音在 1 拍后（120BPM ≈ 0.5s）开始
            track.addNote(new Note(60, 0, 4 * 1920L, 100));
            track.addNote(new Note(64, 480, 1920L, 100));
            document.addTrack(track);
            PlaybackPlan plan = PlaybackPlan.build(document, false);

            PlaybackEngine engine = new PlaybackEngine(synth);
            engine.play(this, plan, 0, false, new PlaybackEngine.LoopRange(0, 0));
            Thread.sleep(120);
            assertTrue(synth.activeNoteCount() > 0, "播放中应有发声的音符");

            // 再等到第 2 个音已交给合成器（lookahead 260ms 内）但还没到发声时刻
            Thread.sleep(300);
            long offsBefore = synth.noteOffsSent();
            engine.stop();
            assertEquals(0, synth.activeNoteCount(), "停止后不应有仍在发声的音符（否则就是“余音绕梁”）");
            assertTrue(synth.noteOffsSent() > offsBefore,
                    "停止时要把已经排进合成器队列、还没响的音一并取消");

            // 暂停同样要立刻静音
            long before = synth.noteEventsSent();
            engine.play(this, plan, 0, false, new PlaybackEngine.LoopRange(0, 0));
            Thread.sleep(150);
            assertTrue(synth.noteEventsSent() > before, "重新播放应继续投递音符");
            engine.pause();
            assertEquals(0, synth.activeNoteCount(), "暂停后同样不应有发声的音符");
            engine.stop();
        } finally {
            synth.close();
        }
    }

    @Test
    void restartAfterStopDoesNotJumpPlayhead() throws Exception {
        SynthService synth = new SynthService();
        synth.open();
        try {
            assumeTrue(synth.available(), "当前环境无可用音频设备");
            MidiDocument document = new MidiDocument(480);
            Track track = new Track("长音", 0, 0, Color.BLUE);
            track.addNote(new Note(60, 0, 9600, 100));
            document.addTrack(track);
            PlaybackPlan plan = PlaybackPlan.build(document, false);

            PlaybackEngine engine = new PlaybackEngine(synth);
            engine.play(this, plan, 0, false, new PlaybackEngine.LoopRange(0, 0));
            Thread.sleep(120);
            engine.stop();
            Thread.sleep(400);

            engine.play(this, plan, 0, false, new PlaybackEngine.LoopRange(0, 0));
            long tick = engine.currentTick();
            assertTrue(tick < 120, "停止后重新播放不应跳变，实际 tick=" + tick);
            Thread.sleep(120);
            assertTrue(engine.currentTick() < 600, "重新播放后应正常推进");
            engine.stop();
        } finally {
            synth.close();
        }
    }

    @Test
    void schedulerAdvancesClockAndSupportsSeek() throws Exception {
        SynthService synth = new SynthService();
        synth.open();
        try {
            assumeTrue(synth.available(), "当前环境无可用音频设备");
            assertTrue(synth.micros() >= 0, "surefire 应通过 --add-exports 导出 com.sun.media.sound 以取得音频时钟");
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
