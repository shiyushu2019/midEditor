package com.example.mideditor.engine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 * 从头播放时最开头那一声“短促的怪音”：
 * 它是刚才单击试听（或按住 Q 预览）的那个音被开播时的通道重置（CC120）硬切掉的残响。
 * 开播前必须先把试听音正常收掉。
 */
class PlaybackStartTest {

    /** 按顺序记下合成器收到的每条 ShortMessage。 */
    private static Receiver recording(List<ShortMessage> sink) {
        return new Receiver() {
            @Override
            public void send(MidiMessage message, long timeStamp) {
                if (message instanceof ShortMessage sm) {
                    synchronized (sink) {
                        sink.add(sm);
                    }
                }
            }

            @Override
            public void close() {
            }
        };
    }

    private static List<ShortMessage> snapshot(List<ShortMessage> sink) {
        synchronized (sink) {
            return new ArrayList<>(sink);
        }
    }

    private static int indexOf(List<ShortMessage> messages, int command, int pitch) {
        for (int i = 0; i < messages.size(); i++) {
            ShortMessage m = messages.get(i);
            if (m.getCommand() == command && m.getData1() == pitch) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void startingPlaybackReleasesTheAuditionNoteBeforeResettingChannels() throws Exception {
        List<ShortMessage> sent = Collections.synchronizedList(new ArrayList<>());
        SynthService synth = SynthService.forTesting(recording(sent));
        PlaybackEngine engine = new PlaybackEngine(synth);

        // 先单击试听一个跟乐曲第一个音无关的音（72），它还挂着
        synth.audition(0, 0, 72, 100, 100, 64, 60_000);
        assertTrue(indexOf(snapshot(sent), ShortMessage.NOTE_ON, 72) >= 0, "前提：试听音已经发声");

        MidiDocument document = new MidiDocument(480);
        Track track = new Track("钢琴", 0, 0, Color.BLUE);
        track.addNote(new Note(60, 0, 480, 100));
        document.addTrack(track);
        engine.play(this, PlaybackPlan.build(document, false), 0, false,
                new PlaybackEngine.LoopRange(0, 0));
        try {
            Thread.sleep(120); // 等调度线程把开头的音真正投递出去
            List<ShortMessage> messages = snapshot(sent);
            int auditionOn = indexOf(messages, ShortMessage.NOTE_ON, 72);
            int auditionOff = indexOf(messages, ShortMessage.NOTE_OFF, 72);
            int firstNoteOn = indexOf(messages, ShortMessage.NOTE_ON, 60);
            assertTrue(firstNoteOn >= 0, "乐曲第一个音应该已经响过");
            assertTrue(auditionOff > auditionOn, "开播前要先把试听音正常收掉（发 NOTE_OFF），而不是让通道重置硬切");
            assertTrue(firstNoteOn > auditionOff, "试听音先收干净，乐曲第一个音才开始");
            assertEquals(1, countNoteOn(messages, 60), "乐曲第一个音只响一次");
            assertEquals(1, countNoteOn(messages, 72), "试听那个音不会在开头再冒一次");
        } finally {
            engine.stop();
        }
    }

    private static int countNoteOn(List<ShortMessage> messages, int pitch) {
        int count = 0;
        for (ShortMessage m : messages) {
            if (m.getCommand() == ShortMessage.NOTE_ON && m.getData1() == pitch && m.getData2() > 0) {
                count++;
            }
        }
        return count;
    }
}
