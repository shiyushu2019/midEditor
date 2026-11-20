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
    /** 音轨信息面板（名称 / 乐器 / 音量 / 声像 / 按钮）宽度：原来是 244，缩到三分之一。 */
    public static final int HEADER_WIDTH = 81;
    public static final int RULER_HEIGHT = 34;
    /** 钢琴标尺（Y 轴键盘）宽度：原来是 46，缩到一半。 */
    public static final int PITCH_STRIP_WIDTH = 23;

    public static final Color CANVAS_BACKGROUND = new Color(0x20252C);
    public static final Color ROW_WHITE = new Color(0x2A323C);
    public static final Color ROW_BLACK = new Color(0x191E25);
    public static final Color ROW_OCTAVE_LINE = new Color(0x39434F);
    /** C（Do）行：半透明黄色叠加，不改底色也不画边框（避免与音轨彩色平行线抢眼）。 */
    public static final Color C_TINT = new Color(0xFF, 0xD2, 0x3F, 0x3A);
    public static final Color C_TINT_KEY = new Color(0xFF, 0xD2, 0x3F, 0x5C);
    public static final Color KEY_C_TEXT = new Color(0x6B4E00);
    public static final Color BAR_LINE = new Color(0x7E8C99);
    public static final Color BEAT_LINE = new Color(0x4A5563);
    public static final Color GRID_LINE = new Color(0x333B45);
    public static final Color LANE_SEPARATOR = new Color(0x0F1318);
    public static final Color ACCENT = new Color(0x3D8BFD);
    public static final Color TEXT_ON_DARK = new Color(0xE7ECF3);
    public static final Color TEXT_MUTED = new Color(0x9AA6B4);
    public static final Color PLAYHEAD = new Color(0xFF5D5D);
    /** 休止符（不发声的平行线）用红色线段表示。 */
    public static final Color REST_COLOR = new Color(0xE05252);
    public static final Color REST_SELECTED = new Color(0xFFD08A);
    /** 收尾锁定标记：空心圆。 */
    public static final Color LOCK_RING = new Color(0xE7ECF3);
    /** 输入模式光标：红色圆点。 */
    public static final Color INPUT_CURSOR = new Color(0xFF3B30);
    /** 输入模式：按下空格时“正在输入”的幽灵条。 */
    public static final Color INPUT_GHOST = new Color(0xFF, 0x3B, 0x30, 0x59);
    /** 输入模式：光标所在的竖线（帮助对齐 tick，很淡）。 */
    public static final Color INPUT_GUIDE = new Color(0xFF, 0x3B, 0x30, 0x4D);
    /** 输入模式：按住输入时浮在光标旁的「音名 + 简谱数字」小牌。 */
    public static final Color INPUT_BADGE_FILL = new Color(0x20, 0x24, 0x2A, 0xE6);
    public static final Color INPUT_BADGE_BORDER = new Color(0xE7ECF3);
    public static final Color INPUT_BADGE_TEXT = new Color(0xF2F5F9);
    public static final Color SELECTION_OUTLINE = new Color(0xFFE082);
    /** 活动音轨泳道底色（很淡的一层，代替以前那种整条泳道的蓝框）。 */
    public static final Color LANE_ACTIVE_TINT = new Color(0x3D, 0x8B, 0xFD, 0x12);
    public static final Color SOUNDING = new Color(0xFFF3BF);
    public static final Color LOOP_REGION = new Color(0x3D8BFD);
    public static final Color KEY_WHITE = new Color(0xDDE2E8);
    public static final Color KEY_BLACK = new Color(0x2A3038);

    public static final Font BASE_FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 12);
    public static final Font BOLD_FONT = new Font("Microsoft YaHei UI", Font.BOLD, 12);
    public static final Font SMALL_FONT = new Font("Microsoft YaHei UI", Font.PLAIN, 11);
    public static final Font MONO_FONT = new Font("Consolas", Font.PLAIN, 12);
}
