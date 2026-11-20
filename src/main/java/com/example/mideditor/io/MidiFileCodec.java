package com.example.mideditor.io;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiEvent;
import javax.sound.midi.MidiMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;

import com.example.mideditor.document.MidiDocument;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.RawChannelEvent;
import com.example.mideditor.domain.RawMetaEvent;
import com.example.mideditor.domain.TempoMap;
import com.example.mideditor.domain.TimeSignature;
import com.example.mideditor.domain.Track;
import com.example.mideditor.domain.TrackPalette;

/**
 * 使用 JDK 自带的 javax.sound.midi 解析 / 生成 .mid（不手写解析器）。
 */
public final class MidiFileCodec {

    private static final int META_TEXT = 0x03;
    private static final int META_MARKER_TEXT = 0x01;
    private static final int META_TEMPO = 0x51;
    private static final int META_TIME_SIGNATURE = 0x58;
    private static final int DRUM_CHANNEL = 9;
    /**
     * 旧版本写进文件的「音域窗口」标记。视图状态（缩放 / 上下滑动）不属于乐曲数据，
     * 现在既不写也不读 —— 只在这里留着，读旧文件时把它丢掉，免得被当成外来 meta 又存回去。
     */
    private static final String LEGACY_RANGE_MARKER = "mideditor:yRange=";
    /** 休止符（可视化占位）不入 MIDI 音符事件，存在自定义 text meta 里。 */
    private static final String REST_MARKER = "mideditor:rest=";
    /** 文档的调号（主音音级 0..11，小调后面跟一个 m），只在非 C 大调时写，免得给旧文件添噪。 */
    private static final String KEY_MARKER = "mideditor:key=";

    private MidiFileCodec() {
    }

    private static final class ChannelData {
        final int channel;
        int program;
        boolean programSeen;
        int volume = 100;
        int pan = 64;
        final List<Note> notes = new ArrayList<>();
        final List<RawChannelEvent> rawEvents = new ArrayList<>();

        ChannelData(int channel) {
            this.channel = channel;
        }
    }

    public static MidiDocument read(Path path) throws IOException, InvalidMidiDataException {
        Sequence sequence = MidiSystem.getSequence(path.toFile());
        int ppq = sequence.getResolution() > 0 ? sequence.getResolution() : 480;
        MidiDocument doc = new MidiDocument(ppq);
        doc.setFilePath(path);
        doc.setName(path.getFileName().toString());

        readTempoAndSignatures(sequence, doc);
        readKeySignature(sequence, doc);
        readTracks(sequence, doc);
        doc.setDirty(false);
        return doc;
    }

    /** 读回本软件写的调号标记（没有就保持默认 C）。 */
    private static void readKeySignature(Sequence sequence, MidiDocument doc) {
        for (javax.sound.midi.Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (!(track.get(i).getMessage() instanceof MetaMessage meta)
                        || meta.getType() != META_MARKER_TEXT) {
                    continue;
                }
                String text = decodeText(meta.getData());
                if (!text.startsWith(KEY_MARKER)) {
                    continue;
                }
                String value = text.substring(KEY_MARKER.length()).trim();
                boolean minor = value.endsWith("m");
                if (minor) {
                    value = value.substring(0, value.length() - 1).trim();
                }
                try {
                    doc.setKeyTonic(Integer.parseInt(value));
                    doc.setKeyMinor(minor);
                } catch (NumberFormatException ignored) {
                    // 非法标记：保持默认
                }
                return;
            }
        }
    }

    private static void readTempoAndSignatures(Sequence sequence, MidiDocument doc) {
        List<TempoMap.Segment> tempos = new ArrayList<>();
        List<TimeSignature> signatures = new ArrayList<>();
        for (javax.sound.midi.Track midiTrack : sequence.getTracks()) {
            for (int i = 0; i < midiTrack.size(); i++) {
                MidiEvent event = midiTrack.get(i);
                MidiMessage message = event.getMessage();
                if (!(message instanceof MetaMessage meta)) {
                    continue;
                }
                byte[] data = meta.getData();
                if (meta.getType() == META_TEMPO && data.length >= 3) {
                    int micros = ((data[0] & 0xFF) << 16) | ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
                    tempos.add(new TempoMap.Segment(event.getTick(), micros));
                } else if (meta.getType() == META_TIME_SIGNATURE && data.length >= 2) {
                    signatures.add(new TimeSignature(event.getTick(), data[0] & 0xFF, 1 << (data[1] & 0xFF)));
                }
            }
        }
        tempos.sort(Comparator.comparingLong(TempoMap.Segment::tick));
        if (!tempos.isEmpty()) {
            doc.tempoMap().replaceSegments(tempos);
        }
        signatures.sort(Comparator.comparingLong(TimeSignature::startTick));
        if (!signatures.isEmpty()) {
            doc.timeSignatures().clear();
            doc.timeSignatures().addAll(signatures);
        }
    }

    private static void readTracks(Sequence sequence, MidiDocument doc) {
        boolean[] usedChannels = new boolean[16];
        int trackNumber = 0;
        int midiTrackIndex = 0;

        for (javax.sound.midi.Track midiTrack : sequence.getTracks()) {
            boolean conductor = midiTrackIndex++ == 0;
            String trackName = findTrackName(midiTrack);
            List<Note> storedRests = findStoredRests(midiTrack);
            Map<Integer, ChannelData> channels = new LinkedHashMap<>();
            Map<Integer, Deque<long[]>> pendingNotes = new HashMap<>();

            for (int i = 0; i < midiTrack.size(); i++) {
                MidiEvent event = midiTrack.get(i);
                MidiMessage message = event.getMessage();
                if (!(message instanceof ShortMessage shortMessage)) {
                    continue;
                }
                int channel = shortMessage.getChannel();
                ChannelData data = channels.computeIfAbsent(channel, ChannelData::new);
                applyMessage(data, shortMessage, event.getTick(), pendingNotes);
            }

            int channelsInTrack = channels.size();
            if (!storedRests.isEmpty()) {
                if (channels.isEmpty()) {
                    // 整轨只有休止符：也得建出音轨
                    channels.put(0, new ChannelData(0));
                }
                channels.values().iterator().next().notes.addAll(storedRests);
            }
            List<RawMetaEvent> foreignMeta = collectForeignMeta(midiTrack);
            if (conductor) {
                // 指挥轨的版权 / 调号 / 标记等挂在文档上，写回时放回指挥轨
                doc.conductorMeta().addAll(foreignMeta);
                foreignMeta = List.of();
            }
            boolean metaAttached = false;
            for (ChannelData data : channels.values()) {
                if (data.notes.isEmpty() && !data.programSeen) {
                    continue;
                }
                int channel = resolveChannel(data.channel, usedChannels);
                usedChannels[channel] = true;

                String name;
                if (trackName != null && channelsInTrack > 1) {
                    name = trackName + " · Ch" + (data.channel + 1);
                } else if (trackName != null) {
                    name = trackName;
                } else {
                    name = "音轨 " + (++trackNumber);
                }

                Track track = new Track(name, channel, data.program, TrackPalette.at(doc.tracks().size()));
                track.setVolume(data.volume);
                track.setPan(data.pan);
                track.setDrumKit(channel == DRUM_CHANNEL);
                for (Note note : data.notes) {
                    track.addNote(note);
                }
                for (RawChannelEvent raw : data.rawEvents) {
                    track.addRawEvent(raw);
                }
                // 一个 MIDI 轨被拆成多条编辑音轨时，外来 meta 只挂在第一条上，避免重复
                if (!metaAttached) {
                    for (RawMetaEvent foreign : foreignMeta) {
                        track.addRawMetaEvent(foreign);
                    }
                    metaAttached = true;
                }
                track.sortNotes();
                // 音域（缩放 / 上下位置）不入文件：按内容重新适配，保证每次打开都是「干净」的
                track.autoRangeFromNotes();
                doc.addTrack(track);
            }
        }
    }

    /**
     * 收集「我们不建模、但要原样写回」的 meta：版权(0x02)、调号(0x59)、标记(0x06)、提示(0x07)、
     * 乐器名(0x04)、歌词(0x05)、SMPTE(0x54)、通道前缀(0x20)、端口(0x21)……
     * 跳过的：轨道名(0x03)、速度(0x51)、拍号(0x58)、结束(0x2F)、以及本软件自己的标记(0x01)。
     */
    private static List<RawMetaEvent> collectForeignMeta(javax.sound.midi.Track midiTrack) {
        List<RawMetaEvent> result = new ArrayList<>();
        for (int i = 0; i < midiTrack.size(); i++) {
            MidiEvent event = midiTrack.get(i);
            if (!(event.getMessage() instanceof MetaMessage meta)) {
                continue;
            }
            int type = meta.getType();
            if (type == META_TEXT || type == META_TEMPO || type == META_TIME_SIGNATURE || type == 0x2F) {
                continue;
            }
            if (type == META_MARKER_TEXT) {
                String text = decodeText(meta.getData());
                if (text.startsWith(LEGACY_RANGE_MARKER) || text.startsWith(REST_MARKER)
                        || text.startsWith(KEY_MARKER)) {
                    continue; // 本软件自己的标记（音域标记是旧版本写的，读入时直接丢弃）
                }
            }
            result.add(new RawMetaEvent(event.getTick(), type, meta.getData()));
        }
        return result;
    }

    private static String findTrackName(javax.sound.midi.Track midiTrack) {
        for (int i = 0; i < midiTrack.size(); i++) {
            MidiMessage message = midiTrack.get(i).getMessage();
            if (message instanceof MetaMessage meta && meta.getType() == META_TEXT) {
                String text = decodeText(meta.getData());
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    /** 读回自定义标记里保存的休止符（pitch,start,duration）。 */
    private static List<Note> findStoredRests(javax.sound.midi.Track midiTrack) {
        List<Note> rests = new ArrayList<>();
        for (int i = 0; i < midiTrack.size(); i++) {
            MidiMessage message = midiTrack.get(i).getMessage();
            if (!(message instanceof MetaMessage meta) || meta.getType() != META_MARKER_TEXT) {
                continue;
            }
            String text = decodeText(meta.getData());
            if (!text.startsWith(REST_MARKER)) {
                continue;
            }
            String[] parts = text.substring(REST_MARKER.length()).split(",");
            if (parts.length != 3) {
                continue;
            }
            try {
                int pitch = Integer.parseInt(parts[0].trim());
                long start = Long.parseLong(parts[1].trim());
                long duration = Long.parseLong(parts[2].trim());
                rests.add(Note.rest(pitch, start, duration));
            } catch (NumberFormatException ignored) {
                // 忽略非法标记
            }
        }
        return rests;
    }

    private static int resolveChannel(int preferred, boolean[] used) {
        if (preferred == DRUM_CHANNEL || !used[preferred]) {
            return preferred;
        }
        for (int ch = 0; ch < 16; ch++) {
            if (ch != DRUM_CHANNEL && !used[ch]) {
                return ch;
            }
        }
        return preferred;
    }

    private static void applyMessage(ChannelData data, ShortMessage message, long tick,
            Map<Integer, Deque<long[]>> pendingNotes) {
        int command = message.getCommand();
        int pitch = message.getData1();
        int value = message.getData2();

        if (command == ShortMessage.NOTE_ON && value > 0) {
            pendingNotes.computeIfAbsent(data.channel * 128 + pitch, k -> new ArrayDeque<>())
                    .addLast(new long[] { tick, value });
        } else if (command == ShortMessage.NOTE_OFF || (command == ShortMessage.NOTE_ON && value == 0)) {
            Deque<long[]> stack = pendingNotes.get(data.channel * 128 + pitch);
            if (stack != null && !stack.isEmpty()) {
                long[] start = stack.removeLast();
                data.notes.add(new Note(pitch, start[0], Math.max(1, tick - start[0]), (int) start[1]));
            }
        } else if (command == ShortMessage.PROGRAM_CHANGE) {
            data.program = pitch;
            data.programSeen = true;
        } else if (command == ShortMessage.CONTROL_CHANGE) {
            switch (pitch) {
                case 7 -> data.volume = value;
                case 10 -> data.pan = value;
                default -> data.rawEvents.add(new RawChannelEvent(tick, command, pitch, value));
            }
        } else if (command == ShortMessage.PITCH_BEND
                || command == ShortMessage.CHANNEL_PRESSURE
                || command == ShortMessage.POLY_PRESSURE) {
            data.rawEvents.add(new RawChannelEvent(tick, command, pitch, value));
        }
    }

    private static String decodeText(byte[] data) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data))
                    .toString().trim();
        } catch (CharacterCodingException e) {
            return new String(data, StandardCharsets.ISO_8859_1).trim();
        }
    }

    public static void write(MidiDocument doc, Path path) throws IOException, InvalidMidiDataException {
        Sequence sequence = new Sequence(Sequence.PPQ, doc.ppq());
        javax.sound.midi.Track conductor = sequence.createTrack();
        conductor.add(new MidiEvent(metaText("midEditor"), 0));
        for (TempoMap.Segment segment : doc.tempoMap().segments()) {
            conductor.add(new MidiEvent(metaTempo(segment.microsPerQuarter()), segment.tick()));
        }
        for (TimeSignature signature : doc.timeSignatures()) {
            conductor.add(new MidiEvent(metaTimeSignature(signature), signature.startTick()));
        }
        if (doc.keyTonic() != 0 || doc.keyMinor()) {
            conductor.add(new MidiEvent(metaMessage(META_MARKER_TEXT,
                    KEY_MARKER + doc.keyTonic() + (doc.keyMinor() ? "m" : "")), 0));
        }
        for (RawMetaEvent foreign : doc.conductorMeta()) {
            conductor.add(new MidiEvent(
                    new MetaMessage(foreign.type(), foreign.data(), foreign.length()), foreign.tick()));
        }

        for (Track track : doc.tracks()) {
            javax.sound.midi.Track midiTrack = sequence.createTrack();
            midiTrack.add(new MidiEvent(metaText(track.name()), 0));
            // 音色库选择（CC0/CC32）必须排在 PROGRAM_CHANGE 之前，否则别的软件会选错音色
            for (RawChannelEvent raw : track.rawEvents()) {
                if (raw.tick() == 0 && raw.command() == ShortMessage.CONTROL_CHANGE
                        && (raw.data1() == 0 || raw.data1() == 32)) {
                    midiTrack.add(new MidiEvent(
                            shortMessage(raw.command(), track.channel(), raw.data1(), raw.data2()), 0));
                }
            }
            midiTrack.add(new MidiEvent(
                    shortMessage(ShortMessage.PROGRAM_CHANGE, track.channel(), track.program(), 0), 0));
            midiTrack.add(new MidiEvent(
                    shortMessage(ShortMessage.CONTROL_CHANGE, track.channel(), 7, track.volume()), 0));
            midiTrack.add(new MidiEvent(
                    shortMessage(ShortMessage.CONTROL_CHANGE, track.channel(), 10, track.pan()), 0));
            for (RawMetaEvent foreign : track.rawMetaEvents()) {
                midiTrack.add(new MidiEvent(
                        new MetaMessage(foreign.type(), foreign.data(), foreign.length()), foreign.tick()));
            }
            for (RawChannelEvent raw : track.rawEvents()) {
                if (raw.tick() == 0 && raw.command() == ShortMessage.CONTROL_CHANGE
                        && (raw.data1() == 0 || raw.data1() == 32)) {
                    continue; // 上面已经提前写过
                }
                midiTrack.add(new MidiEvent(
                        shortMessage(raw.command(), track.channel(), raw.data1(), raw.data2()), raw.tick()));
            }
            for (Note note : track.notes()) {
                if (note.isRest()) {
                    midiTrack.add(new MidiEvent(metaMessage(META_MARKER_TEXT,
                            REST_MARKER + note.pitch() + "," + note.startTick() + "," + note.durationTicks()),
                            note.startTick()));
                    continue;
                }
                midiTrack.add(new MidiEvent(
                        shortMessage(ShortMessage.NOTE_ON, track.channel(), note.pitch(), note.velocity()),
                        note.startTick()));
                midiTrack.add(new MidiEvent(
                        shortMessage(ShortMessage.NOTE_OFF, track.channel(), note.pitch(), 0),
                        note.endTick()));
            }
        }

        int[] types = MidiSystem.getMidiFileTypes(sequence);
        int type = types.length > 0 ? types[0] : 1;
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        MidiSystem.write(sequence, type, path.toFile());
    }

    private static MetaMessage metaText(String text) throws InvalidMidiDataException {
        return metaMessage(META_TEXT, text);
    }

    private static MetaMessage metaMessage(int type, String text) throws InvalidMidiDataException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new MetaMessage(type, bytes, bytes.length);
    }

    private static MetaMessage metaTempo(long microsPerQuarter) throws InvalidMidiDataException {
        long value = Math.max(1, microsPerQuarter);
        byte[] data = { (byte) ((value >> 16) & 0xFF), (byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF) };
        return new MetaMessage(META_TEMPO, data, data.length);
    }

    private static MetaMessage metaTimeSignature(TimeSignature signature) throws InvalidMidiDataException {
        int denominatorPower = Integer.numberOfTrailingZeros(signature.denominator());
        byte[] data = { (byte) signature.numerator(), (byte) denominatorPower, 24, 8 };
        return new MetaMessage(META_TIME_SIGNATURE, data, data.length);
    }

    private static ShortMessage shortMessage(int command, int channel, int data1, int data2)
            throws InvalidMidiDataException {
        return new ShortMessage(command, channel, data1, data2);
    }
}
