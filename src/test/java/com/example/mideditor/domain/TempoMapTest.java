package com.example.mideditor.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TempoMapTest {

    private static final int PPQ = 480;

    @Test
    void defaultTempoMapsOneQuarterToHalfASecond() {
        TempoMap tempo = new TempoMap(PPQ);
        assertEquals(500_000L, tempo.microsAtTick(PPQ));
        assertEquals(PPQ, tempo.tickAtMicros(500_000L));
        assertEquals(120.0, tempo.bpmAt(0), 0.001);
    }

    @Test
    void tempoChangeAppliesToFollowingSegments() {
        TempoMap tempo = new TempoMap(PPQ);
        tempo.setBpm(PPQ, 240);
        // 第一拍 500000us + 第二拍 250000us
        assertEquals(750_000L, tempo.microsAtTick(PPQ * 2L));
        assertEquals(PPQ * 2L, tempo.tickAtMicros(750_000L));
        assertEquals(240.0, tempo.bpmAt(PPQ), 0.001);
        assertEquals(120.0, tempo.bpmAt(0), 0.001);
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
