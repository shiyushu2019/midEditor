package com.example.mideditor.io;

import java.nio.file.Path;
import java.util.List;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.mideditor.document.MidiDocument;
import com.example.mideditor.domain.Track;

/** 外部 .mid 的保真：不认识但重要的信息不能被“打开再保存”吃掉。 */
class ForeignMidiFidelityTest {

    @Test
    void keepsForeignMetaBankOrderAndTrackName(@TempDir Path dir) throws Exception {
        Sequence seq = new Sequence(Sequence.PPQ, 480);
        javax.sound.midi.Track conductor = seq.createTrack();
        conductor.add(new MidiEvent(meta(0x03, "Conductor"), 0));
        conductor.add(new MidiEvent(meta(0x02, "(c) someone"), 0)); // 版权
        conductor.add(new MidiEvent(meta(0x51, 0x07, 0xA1, 0x20), 0));
        conductor.add(new MidiEvent(meta(0x58, 4, 2, 24, 8), 0));
        conductor.add(new MidiEvent(meta(0x59, 0, 0), 0)); // 调号

        javax.sound.midi.Track music = seq.createTrack();
        music.add(new MidiEvent(meta(0x03, "Timpani part"), 0));
        music.add(new MidiEvent(meta(0x06, "Verse 1"), 0)); // 标记
        music.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 0, 1), 0));
        music.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 32, 0), 0));
        music.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE, 0, 47, 0), 0));
        music.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE, 0, 7, 96), 0));
        music.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, 50, 100), 0));
        music.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, 50, 64), 480));

        Path source = dir.resolve("external.mid");
        MidiSystem.write(seq, 1, source.toFile());

        // 打开（不做任何编辑）再保存
        MidiDocument doc = MidiFileCodec.read(source);
        Path saved = dir.resolve("saved.mid");
        MidiFileCodec.write(doc, saved);

        Sequence out = MidiSystem.getSequence(saved.toFile());
        javax.sound.midi.Track outConductor = out.getTracks()[0];
        assertTrue(hasMeta(outConductor, 0x02), "版权 meta 不能被丢掉");
        assertTrue(hasMeta(outConductor, 0x59), "调号 meta 不能被丢掉");
        assertTrue(hasMeta(outConductor, 0x51), "速度 meta 要在");
        assertTrue(hasMeta(outConductor, 0x58), "拍号 meta 要在");

        javax.sound.midi.Track outMusic = out.getTracks()[1];
        assertTrue(hasMeta(outMusic, 0x06), "标记（Marker）不能被丢掉");
        assertEquals("Timpani part", trackName(outMusic), "音轨名要保留");

        List<MidiEvent> events = eventsOf(outMusic);
        int bankMsb = indexOfControl(events, 0, 1);
        int program = indexOfProgram(events, 47);
        assertTrue(bankMsb >= 0, "音色库选择 CC0 要保留");
        assertTrue(program >= 0, "PROGRAM_CHANGE 要保留");
        assertTrue(bankMsb < program, "CC0/CC32 必须排在 PROGRAM_CHANGE 之前，否则别的软件选错音色库");

        Track loaded = doc.tracks().get(0);
        assertEquals(47, loaded.program(), "定音鼓（GM 47）音色号要原样保留");
        assertEquals(1, loaded.notes().size());
        assertEquals(50, loaded.notes().get(0).pitch());
        assertEquals(480, loaded.notes().get(0).durationTicks());
    }

    private static MetaMessage meta(int type, String text) throws Exception {
        byte[] d = text.getBytes("UTF-8");
        return new MetaMessage(type, d, d.length);
    }

    private static MetaMessage meta(int type, int... data) throws Exception {
        byte[] d = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            d[i] = (byte) data[i];
        }
        return new MetaMessage(type, d, d.length);
    }

    private static boolean hasMeta(javax.sound.midi.Track track, int type) {
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == type) {
                return true;
            }
        }
        return false;
    }

    private static String trackName(javax.sound.midi.Track track) {
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof MetaMessage meta && meta.getType() == 0x03) {
                return new String(meta.getData(), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static List<MidiEvent> eventsOf(javax.sound.midi.Track track) {
        List<MidiEvent> list = new java.util.ArrayList<>();
        for (int i = 0; i < track.size(); i++) {
            list.add(track.get(i));
        }
        return list;
    }

    private static int indexOfControl(List<MidiEvent> events, int controller, int value) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getMessage() instanceof ShortMessage sm
                    && sm.getCommand() == ShortMessage.CONTROL_CHANGE
                    && sm.getData1() == controller && sm.getData2() == value) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfProgram(List<MidiEvent> events, int program) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).getMessage() instanceof ShortMessage sm
                    && sm.getCommand() == ShortMessage.PROGRAM_CHANGE && sm.getData1() == program) {
                return i;
            }
        }
        return -1;
    }
}
