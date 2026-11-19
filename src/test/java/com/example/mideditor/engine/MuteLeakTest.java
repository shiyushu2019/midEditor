package com.example.mideditor.engine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

import com.example.mideditor.document.MidiDocument;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;

/**
 * 两个音轨、其中一个静音：播放中换计划（静音/独奏/编辑）时，
 * 旧计划里已经进入投递批次的事件必须立刻作废，不能再漏出一小段声音。
 */
class MuteLeakTest {

    /** 记录所有发出的事件；可以卡住某个通道的 note-on，用来把「批次投递中」这个瞬间固定住。 */
    private static final class RecordingReceiver implements Receiver {
        final List<ShortMessage> sent = Collections.synchronizedList(new ArrayList<>());
        volatile CountDownLatch firstNoteOn = new CountDownLatch(1);
        volatile CountDownLatch resume = new CountDownLatch(1);
        volatile int gateChannel = -1;

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (!(message instanceof ShortMessage sm)) {
                return;
            }
            sent.add(sm);
            if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData2() > 0
                    && sm.getChannel() == gateChannel) {
                gateChannel = -1; // 只卡住第一次，避免后续事件反复等待
                firstNoteOn.countDown();
                try {
                    resume.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void close() {
        }

        synchronized List<ShortMessage> snapshot() {
            return new ArrayList<>(sent);
        }
    }

    private static MidiDocument twoTracks() {
        MidiDocument document = new MidiDocument(480);
        document.tempoMap().setBpm(0, 120); // 用例按“240 tick ≈ 250ms”排时间，别跟着默认 BPM 变
        Track audible = new Track("主旋律", 0, 0, Color.BLUE);
        for (int i = 0; i < 16; i++) {
            audible.addNote(new Note(60, i * 240L, 240, 100));
        }
        document.addTrack(audible);

        Track toMute = new Track("伴奏", 1, 24, Color.GREEN);
        // 一大簇同时开始的音：保证一个投递批次里有很多 ch1 的 note-on
        for (int i = 0; i < 24; i++) {
            toMute.addNote(new Note(48 + i, 0, 9600, 90));
        }
        for (int i = 0; i < 16; i++) {
            toMute.addNote(new Note(52, i * 240L, 200, 90));
        }
        document.addTrack(toMute);
        return document;
    }

    @Test
    void swappingPlanMidBatchNeverLeaksNotesOfTheMutedTrack() throws Exception {
        RecordingReceiver receiver = new RecordingReceiver();
        SynthService synth = SynthService.forTesting(receiver);
        PlaybackEngine engine = new PlaybackEngine(synth);
        MidiDocument document = twoTracks();
        PlaybackPlan beforeMute = PlaybackPlan.build(document, false);

        receiver.gateChannel = 1;
        engine.play(this, beforeMute, 0, false, new PlaybackEngine.LoopRange(0, 0));
        assertTrue(receiver.firstNoteOn.await(3, TimeUnit.SECONDS), "伴奏轨应先开始发声");
        int atSwap = receiver.snapshot().size();

        // 就在批次还在投递时静音：新计划里没有 ch1
        document.tracks().get(1).setMute(true);
        engine.updatePlan(PlaybackPlan.build(document, false));
        receiver.resume.countDown();

        Thread.sleep(400);
        engine.stop();
        receiver.resume.countDown();

        List<ShortMessage> after = receiver.snapshot().subList(atSwap, receiver.snapshot().size());
        long leaked = after.stream()
                .filter(m -> m.getChannel() == 1 && m.getCommand() == ShortMessage.NOTE_ON && m.getData2() > 0)
                .count();
        assertEquals(0, leaked, "静音生效后不应再有伴奏轨的 note-on（漏出 " + leaked + " 个）");
        assertTrue(after.stream().anyMatch(m -> m.getChannel() == 0 && m.getCommand() == ShortMessage.NOTE_ON),
                "没静音的那条音轨应照常继续");
    }

    @Test
    void playAfterMuteSendsNothingForTheMutedTrack() throws Exception {
        RecordingReceiver receiver = new RecordingReceiver();
        SynthService synth = SynthService.forTesting(receiver);
        PlaybackEngine engine = new PlaybackEngine(synth);
        MidiDocument document = twoTracks();
        document.tracks().get(1).setMute(true);

        engine.play(this, PlaybackPlan.build(document, false), 0, false, new PlaybackEngine.LoopRange(0, 0));
        Thread.sleep(300);
        engine.stop();

        long mutedNotes = receiver.snapshot().stream()
                .filter(m -> m.getChannel() == 1 && m.getCommand() == ShortMessage.NOTE_ON)
                .count();
        assertEquals(0, mutedNotes, "从头播放时静音轨不应发出任何音符");
        assertTrue(receiver.snapshot().stream()
                .anyMatch(m -> m.getChannel() == 0 && m.getCommand() == ShortMessage.NOTE_ON),
                "未静音的音轨应正常发声");
    }
}
