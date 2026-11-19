package com.example.mideditor.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** 试听单音：真的发 NOTE_ON、到点收 NOTE_OFF，且换选中对象时前一声立刻停。 */
class AuditionSoundTest {

    private static final class Recorder implements Receiver {
        final List<ShortMessage> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(MidiMessage message, long timeStamp) {
            if (message instanceof ShortMessage sm) {
                sent.add(sm);
            }
        }

        @Override
        public void close() {
        }

        synchronized List<ShortMessage> snapshot() {
            return new ArrayList<>(sent);
        }

        synchronized boolean hasNote(int command, int pitch) {
            for (ShortMessage sm : sent) {
                if (sm.getCommand() == command && sm.getData1() == pitch) {
                    return true;
                }
            }
            return false;
        }
    }

    @Test
    void playsTheNoteThenStopsItAfterTheGivenLength() throws Exception {
        Recorder recorder = new Recorder();
        SynthService synth = SynthService.forTesting(recorder);
        synth.audition(0, 0, 60, 100, 100, 64, 150);

        assertTrue(recorder.hasNote(ShortMessage.NOTE_ON, 60), "应当先发出 NOTE_ON");
        assertTrue(!recorder.hasNote(ShortMessage.NOTE_OFF, 60), "还没到点，不应提前收");

        long deadline = System.currentTimeMillis() + 2000;
        while (!recorder.hasNote(ShortMessage.NOTE_OFF, 60) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(recorder.hasNote(ShortMessage.NOTE_OFF, 60), "到点必须收掉（时长要符合时值）");
    }

    @Test
    void auditioningAgainSilencesThePreviousNote() throws Exception {
        Recorder recorder = new Recorder();
        SynthService synth = SynthService.forTesting(recorder);

        synth.audition(0, 0, 60, 100, 100, 64, 5000); // 很长的音，不等它自己结束
        synth.audition(0, 0, 64, 100, 100, 64, 5000);

        assertTrue(recorder.hasNote(ShortMessage.NOTE_ON, 64), "新选中的那条要响");
        assertTrue(recorder.hasNote(ShortMessage.NOTE_OFF, 60), "上一条必须立刻收掉，不能叠在一起");
        assertEquals(1, countNoteOn(recorder, 64));
    }

    private static int countNoteOn(Recorder recorder, int pitch) {
        int count = 0;
        for (ShortMessage sm : recorder.snapshot()) {
            if (sm.getCommand() == ShortMessage.NOTE_ON && sm.getData1() == pitch) {
                count++;
            }
        }
        return count;
    }
}
