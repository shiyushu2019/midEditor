package com.example.mideditor.domain;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/** 自动休止符：第一个音之后、最后一个音之前，没在发声的时间段就是休止符。 */
class GapRestsTest {

    private static Track track() {
        return new Track("旋律", 0, 0, Color.BLUE);
    }

    @Test
    void gapBetweenTwoNotesBecomesRest() {
        Track track = track();
        track.addNote(new Note(60, 0, 480, 100));
        track.addNote(new Note(64, 960, 480, 100));

        List<GapRests.Span> spans = GapRests.of(track);
        assertEquals(1, spans.size());
        assertEquals(480, spans.get(0).startTick(), "从上一根的尾巴开始");
        assertEquals(960, spans.get(0).endTick(), "到下一根的头结束");
        assertEquals(60, spans.get(0).pitch(), "画在上一根所在的那一行");
        assertEquals(480, spans.get(0).lengthTicks());
    }

    @Test
    void noGapBeforeFirstOrAfterLastNote() {
        Track track = track();
        track.addNote(new Note(60, 960, 480, 100));
        track.addNote(new Note(64, 1920, 480, 100));

        List<GapRests.Span> spans = GapRests.of(track);
        assertEquals(1, spans.size(), "只中间那段是休止符");
        assertEquals(1440, spans.get(0).startTick());
        assertEquals(1920, spans.get(0).endTick());
    }

    @Test
    void overlappingNotesAndChordsDoNotCreateFalseRests() {
        Track track = track();
        track.addNote(new Note(60, 0, 960, 100)); // 0-960
        track.addNote(new Note(64, 480, 960, 100)); // 480-1440（与上一条重叠）
        track.addNote(new Note(67, 1440, 480, 100)); // 紧接 1440-1920

        assertTrue(GapRests.of(track).isEmpty(), "连着的和弦/重叠音之间没有空隙");
    }

    @Test
    void polyphonyOnlyLeavesRealSilenceAsRest() {
        Track track = track();
        track.addNote(new Note(60, 0, 480, 100)); // 0-480
        track.addNote(new Note(72, 240, 960, 100)); // 240-1200
        track.addNote(new Note(64, 1920, 480, 100)); // 1920-2400

        List<GapRests.Span> spans = GapRests.of(track);
        assertEquals(1, spans.size(), "有声部在响的时间不算空隙");
        assertEquals(1200, spans.get(0).startTick());
        assertEquals(1920, spans.get(0).endTick());
        assertEquals(72, spans.get(0).pitch(), "画在最后结束的那一条所在行");
    }

    @Test
    void storedRestsAreIgnoredButStillInsideGap() {
        Track track = track();
        track.addNote(new Note(60, 0, 480, 100));
        track.addNote(Note.rest(60, 480, 480)); // 手工画的休止符
        track.addNote(new Note(60, 960, 480, 100));

        List<GapRests.Span> spans = GapRests.of(track);
        assertEquals(1, spans.size());
        assertEquals(480, spans.get(0).startTick());
        assertEquals(960, spans.get(0).endTick(), "休止符不发声，所以这段依旧是空隙");
    }

    @Test
    void adjacentNotesHaveNoGap() {
        Track track = track();
        track.addNote(new Note(60, 0, 480, 100));
        track.addNote(new Note(60, 480, 480, 100));
        track.addNote(new Note(60, 1200, 480, 100));

        List<GapRests.Span> spans = GapRests.of(track);
        assertEquals(1, spans.size());
        assertEquals(960, spans.get(0).startTick(), "只有 960-1200 那一段是真空隙");
        assertEquals(1200, spans.get(0).endTick());
    }

    @Test
    void emptyTrackHasNoRests() {
        assertTrue(GapRests.of(track()).isEmpty());
        assertTrue(GapRests.of(null).isEmpty());
    }
}
