package com.example.mideditor.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.sound.midi.ShortMessage;

import com.example.mideditor.document.MidiDocument;
import com.example.mideditor.domain.GmProgram;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.RawChannelEvent;
import com.example.mideditor.domain.TempoMap;
import com.example.mideditor.domain.TimeSignature;
import com.example.mideditor.domain.Track;

/**
 * 一次播放的不可变事件计划。调度线程只读它，UI 线程编辑时重建新计划。
 */
public final class PlaybackPlan {

    public record Event(long tick, int command, int channel, int data1, int data2) {
    }

    private static final int META_PRIORITY = 0;
    private static final int OFF_PRIORITY = 1;
    private static final int ON_PRIORITY = 2;

    private final TempoMap tempo;
    private final List<Event> events;
    private final long endTick;

    private PlaybackPlan(TempoMap tempo, List<Event> events, long endTick) {
        this.tempo = tempo;
        this.events = List.copyOf(events);
        this.endTick = endTick;
    }

    public static PlaybackPlan build(MidiDocument document, boolean metronome) {
        TempoMap tempo = document.tempoMap().copy();
        boolean anySolo = document.tracks().stream().anyMatch(Track::solo);
        List<Event> events = new ArrayList<>();
        long endTick = 1;

        for (Track track : document.tracks()) {
            boolean audible = anySolo ? track.solo() : !track.mute();
            if (!audible) {
                continue;
            }
            int channel = track.drumKit() ? GmProgram.DRUM_CHANNEL : track.channel();
            events.add(new Event(0, ShortMessage.PROGRAM_CHANGE, channel, track.program(), 0));
            events.add(new Event(0, ShortMessage.CONTROL_CHANGE, channel, 7, track.volume()));
            events.add(new Event(0, ShortMessage.CONTROL_CHANGE, channel, 10, track.pan()));
            for (RawChannelEvent raw : track.rawEvents()) {
                endTick = Math.max(endTick, raw.tick());
                events.add(new Event(raw.tick(), raw.command(), channel, raw.data1(), raw.data2()));
            }
            for (Note note : track.notes()) {
                endTick = Math.max(endTick, note.endTick());
                events.add(new Event(note.startTick(), ShortMessage.NOTE_ON, channel, note.pitch(), note.velocity()));
                events.add(new Event(note.endTick(), ShortMessage.NOTE_OFF, channel, note.pitch(), 0));
            }
        }

        if (metronome) {
            events.addAll(metronomeEvents(document, endTick));
        }

        events.sort(Comparator
                .comparingLong(Event::tick)
                .thenComparingInt(PlaybackPlan::priority));
        return new PlaybackPlan(tempo, events, endTick);
    }

    private static List<Event> metronomeEvents(MidiDocument document, long endTick) {
        List<Event> clicks = new ArrayList<>();
        int ppq = document.ppq();
        TimeSignature signature = document.timeSignatures().isEmpty()
                ? TimeSignature.defaultSignature()
                : document.timeSignatures().get(0);
        long beatTicks = Math.max(1, ppq * 4L / Math.max(1, signature.denominator()));
        long barTicks = beatTicks * Math.max(1, signature.numerator());
        long limit = endTick + 4L * barTicks;
        for (long tick = 0; tick <= limit; tick += beatTicks) {
            boolean accent = barTicks > 0 && tick % barTicks == 0;
            clicks.add(new Event(tick, ShortMessage.NOTE_ON, GmProgram.DRUM_CHANNEL, accent ? 76 : 77,
                    accent ? 110 : 70));
            clicks.add(new Event(tick + beatTicks / 2, ShortMessage.NOTE_OFF, GmProgram.DRUM_CHANNEL,
                    accent ? 76 : 77, 0));
        }
        return clicks;
    }

    private static int priority(Event event) {
        return switch (event.command()) {
            case ShortMessage.NOTE_OFF -> OFF_PRIORITY;
            case ShortMessage.NOTE_ON -> ON_PRIORITY;
            default -> META_PRIORITY;
        };
    }

    public List<Event> events() {
        return events;
    }

    public TempoMap tempo() {
        return tempo;
    }

    public long endTick() {
        return endTick;
    }

    /** 返回第一个 tick >= 目标的事件下标。 */
    public int indexAtTick(long tick) {
        int low = 0;
        int high = events.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (events.get(mid).tick() < tick) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
