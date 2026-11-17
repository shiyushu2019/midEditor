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
import com.example.mideditor.domain.PitchRange;
import com.example.mideditor.domain.RawChannelEvent;
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
    private static final String RANGE_MARKER = "mideditor:yRange=";

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
        readTracks(sequence, doc);
        doc.setDirty(false);
        return doc;
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

        for (javax.sound.midi.Track midiTrack : sequence.getTracks()) {
            String trackName = findTrackName(midiTrack);
            PitchRange storedRange = findStoredRange(midiTrack);
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
                track.sortNotes();
                if (storedRange != null) {
                    track.setYRange(storedRange);
                } else {
                    track.autoRangeFromNotes();
                }
                doc.addTrack(track);
            }
        }
    }

    private static PitchRange findStoredRange(javax.sound.midi.Track midiTrack) {
        for (int i = 0; i < midiTrack.size(); i++) {
            MidiMessage message = midiTrack.get(i).getMessage();
            if (!(message instanceof MetaMessage meta) || meta.getType() != META_MARKER_TEXT) {
                continue;
            }
            String text = decodeText(meta.getData());
            if (!text.startsWith(RANGE_MARKER)) {
                continue;
            }
            String[] parts = text.substring(RANGE_MARKER.length()).split(",");
            if (parts.length == 2) {
                try {
                    return new PitchRange(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException ignored) {
                    // 忽略非法标记
                }
            }
        }
        return null;
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
            if (pitch == 7) {
                data.volume = value;
            } else if (pitch == 10) {
                data.pan = value;
            } else {
                data.rawEvents.add(new RawChannelEvent(tick, command, pitch, value));
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

        for (Track track : doc.tracks()) {
            javax.sound.midi.Track midiTrack = sequence.createTrack();
            midiTrack.add(new MidiEvent(metaText(track.name()), 0));
            midiTrack.add(new MidiEvent(
                    shortMessage(ShortMessage.PROGRAM_CHANGE, track.channel(), track.program(), 0), 0));
            midiTrack.add(new MidiEvent(
                    shortMessage(ShortMessage.CONTROL_CHANGE, track.channel(), 7, track.volume()), 0));
            midiTrack.add(new MidiEvent(
                    shortMessage(ShortMessage.CONTROL_CHANGE, track.channel(), 10, track.pan()), 0));
            midiTrack.add(new MidiEvent(
                    metaMessage(META_MARKER_TEXT, RANGE_MARKER + track.yRange().low() + "," + track.yRange().high()),
                    0));
            for (RawChannelEvent raw : track.rawEvents()) {
                midiTrack.add(new MidiEvent(
                        shortMessage(raw.command(), track.channel(), raw.data1(), raw.data2()), raw.tick()));
            }
            for (Note note : track.notes()) {
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
