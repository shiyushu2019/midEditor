package com.example.mideditor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class TempoMapTest {

    private static final int PPQ = 480;

    @Test
    void defaultTempoIsSixtyBpm() {
        TempoMap tempo = new TempoMap(PPQ);
        assertEquals(1_000_000L, tempo.microsAtTick(PPQ), "一个新的四分音符 = 1 秒");
        assertEquals(PPQ, tempo.tickAtMicros(1_000_000L));
        assertEquals(60.0, tempo.bpmAt(0), 0.001);
    }

    @Test
    void tempoChangeAppliesToFollowingSegments() {
        TempoMap tempo = new TempoMap(PPQ);
        tempo.setBpm(PPQ, 240);
        // 第一拍 1000000us + 第二拍 250000us
        assertEquals(1_250_000L, tempo.microsAtTick(PPQ * 2L));
        assertEquals(PPQ * 2L, tempo.tickAtMicros(1_250_000L));
        assertEquals(240.0, tempo.bpmAt(PPQ), 0.001);
        assertEquals(60.0, tempo.bpmAt(0), 0.001);
    }

    @Test
    void tickMicrosRoundTripIsStable() {
        TempoMap tempo = new TempoMap(PPQ);
        tempo.setBpm(0, 90);
        for (long tick : new long[] { 0, 1, 240, 480, 1920, 7777 }) {
            long micros = tempo.microsAtTick(tick);
            assertEquals(tick, tempo.tickAtMicros(micros), "tick " + tick);
        }
    }
}
