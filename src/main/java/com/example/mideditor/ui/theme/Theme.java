package com.example.mideditor.ui.theme;

import java.awt.Color;
import java.awt.Font;

/** 统一配色与度量。 */
public final class Theme {

    private Theme() {
    }

    // 主框架
    public static final Color WORKSPACE_BACKGROUND = new Color(0x59677A);
    public static final Color MENU_BACKGROUND = new Color(0xF3F4F6);
    public static final Color STATUS_BACKGROUND = new Color(0xEDEFF2);
    public static final Color BORDER = new Color(0xC7CCD3);

    // 钢琴卷帘
    public static final int ROW_HEIGHT = 8;
    public static final int LANE_ROWS = com.example.mideditor.domain.Track.VISIBLE_ROWS;
    public static final int LANE_HEIGHT = ROW_HEIGHT * LANE_ROWS;
    public static final int HEADER_WIDTH = 244;
    public static final int RULER_HEIGHT = 34;
    public static final int PITCH_STRIP_WIDTH = 46;

    public static final Color CANVAS_BACKGROUND = new Color(0x20252C);
    public static final Color ROW_WHITE = new Color(0x2A323C);
    public static final Color ROW_BLACK = new Color(0x191E25);
    public static final Color ROW_OCTAVE_LINE = new Color(0x39434F);
    public static final Color BAR_LINE = new Color(0x6C7C8C);
    public static final Color BEAT_LINE = new Color(0x46525F);
    public static final Color GRID_LINE = new Color(0x2E3742);
    public static final Color LANE_SEPARATOR = new Color(0x0F1318);
    public static final Color ACCENT = new Color(0x3D8BFD);
    public static final Color TEXT_ON_DARK = new Color(0xE7ECF3);
    public static final Color TEXT_MUTED = new Color(0x9AA6B4);
    public static final Color PLAYHEAD = new Color(0xFF5D5D);
    public static final Color SELECTION_OUTLINE = new Color(0xFFE082);
    public static final Color SOUNDING = new Color(0xFFF3BF);
    public static final Color LOOP_REGION = new Color(0x3D8BFD);
    public static final Color KEY_WHITE = new Color(0xDDE2E8);
    public static final Color KEY_BLACK = new Color(0x2A3038);

    public static final Font BASE_FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 12);
    public static final Font BOLD_FONT = new Font("Microsoft YaHei UI", Font.BOLD, 12);
    public static final Font SMALL_FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 11);
    public static final Font MONO_FONT = new Font("Consolas", Font.PLAIN, 12);
}
