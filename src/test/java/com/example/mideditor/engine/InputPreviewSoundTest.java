package com.example.mideditor.engine;

import java.util.ArrayList;
import java.util.List;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.ShortMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * 「按住空格要听得见」：按下必须真的发出 NOTE_ON，松手必须收得住（否则会卡音）。
 * 用假 Receiver 验证，不依赖音频设备。
 */
class InputPreviewSoundTest {

    @Test
    void previewOnSoundsAndPreviewOffSilencesIt() {
        List<MidiMessage> received = new ArrayList<>();
        javax.sound.midi.Receiver sink = new javax.sound.midi.Receiver() {
            @Override
            public void send(MidiMessage message, long timestamp) {
                received.add(message);
            }

            @Override
            public void close() {
                // 无需释放
            }
        };
        SynthService synth = SynthService.forTesting(sink);

        synth.previewOn(3, 40, 65, 100, 100, 64);
        assertEquals(1, synth.activeNoteCount(), "按住时应当有一个音正在响");
        assertTrue(hasNote(received, ShortMessage.NOTE_ON, 3, 65), "应当发出 NOTE_ON");

        received.clear();
        synth.previewOff(3, 65);
        assertEquals(0, synth.activeNoteCount(), "松手后不能留下按住的音");
        assertTrue(hasNote(received, ShortMessage.NOTE_OFF, 3, 65), "应当发出 NOTE_OFF");
    }

    private static boolean hasNote(List<MidiMessage> messages, int command, int channel, int pitch) {
        for (MidiMessage message : messages) {
            if (message instanceof ShortMessage shortMessage
                    && shortMessage.getCommand() == command
                    && shortMessage.getChannel() == channel
                    && shortMessage.getData1() == pitch) {
                return true;
            }
        }
        return false;
    }
}
