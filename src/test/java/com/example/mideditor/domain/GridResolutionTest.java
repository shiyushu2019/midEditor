package com.example.mideditor.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

/**
 * 网格下拉框里每一档都必须对应真实时值：1/4 = 四分音符、1/8 = 八分音符、
 * 1/8 三连音 = 三个八分占一个四分音符（ppq/3）……以前三连音写成了两倍长。
 */
class GridResolutionTest {

    private static final int PPQ = 480;

    @Test
    void everyResolutionMapsToItsRealLength() {
        assertEquals(0, GridSettings.Resolution.OFF.ticksPerGrid(PPQ), "关闭 = 0（不吸附、不画细分格）");
        assertEquals(480, GridSettings.Resolution.QUARTER.ticksPerGrid(PPQ), "1/4 = 一个四分音符");
        assertEquals(240, GridSettings.Resolution.EIGHTH.ticksPerGrid(PPQ), "1/8 = 半个四分音符");
        assertEquals(120, GridSettings.Resolution.SIXTEENTH.ticksPerGrid(PPQ), "1/16 = 四分之一");
        assertEquals(60, GridSettings.Resolution.THIRTY_SECOND.ticksPerGrid(PPQ), "1/32 = 八分之一");
        assertEquals(160, GridSettings.Resolution.EIGHTH_TRIPLET.ticksPerGrid(PPQ),
                "1/8 三连音：三个八分占一个四分音符 → ppq/3");
        assertEquals(80, GridSettings.Resolution.SIXTEENTH_TRIPLET.ticksPerGrid(PPQ),
                "1/16 三连音：三个十六分占一个八分音符 → ppq/6");
    }

    @Test
    void snappingUsesTheSameGrid() {
        assertEquals(160, GridSettings.Resolution.EIGHTH_TRIPLET.snap(170, PPQ), "离 160 最近");
        assertEquals(320, GridSettings.Resolution.EIGHTH_TRIPLET.snap(300, PPQ), "离 320 最近");
        assertEquals(480, new GridSettings().snap(500, PPQ), "默认 1/16 网格下 500 → 480");
    }

    @Test
    void tripletGridsFitInsideOneBeat() {
        assertEquals(3, PPQ / GridSettings.Resolution.EIGHTH_TRIPLET.ticksPerGrid(PPQ),
                "一拍里整整齐齐放进 3 个 1/8 三连音");
        assertEquals(6, PPQ / GridSettings.Resolution.SIXTEENTH_TRIPLET.ticksPerGrid(PPQ),
                "一拍里整整齐齐放进 6 个 1/16 三连音");
    }
}
