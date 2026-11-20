package com.example.mideditor.ui.pianoroll;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;

import com.example.mideditor.document.MidiDocument;
import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.document.TimeMath;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.PitchRange;
import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.theme.Theme;

/**
 * 钢琴卷帘绘制。核心视觉对象是「平行线」：方角、无边框、粗细反映 velocity。
 */
final class PianoRollPainter {

    /** 活动音轨在左侧的高亮条宽度（像素）。 */
    private static final int ACTIVE_LANE_BAR = 3;

    private PianoRollPainter() {
    }

    static void paint(PianoRollCanvas canvas, Graphics2D g) {
        PianoRollView view = canvas.view();
        MidiDocumentView host = view.host();
        MidiDocument document = host.document();
        Rectangle clip = g.getClipBounds() == null ? canvas.getVisibleRect() : g.getClipBounds();

        g.setColor(Theme.CANVAS_BACKGROUND);
        g.fillRect(clip.x, clip.y, clip.width, clip.height);

        for (int i = 0; i < document.tracks().size(); i++) {
            int laneTop = view.laneTop(i);
            if (laneTop > clip.y + clip.height) {
                break;
            }
            if (laneTop + view.laneHeight() < clip.y) {
                continue;
            }
            paintLane(g, view, host, document, i, document.tracks().get(i), clip);
        }

        paintLoopRegion(g, view, host, clip);
        paintLocks(g, view, document, clip);
        paintPlayhead(g, view, host, clip);
        paintInputCursor(g, view, host, document, clip);
        paintInputBadge(g, view, host, document, clip);

        Rectangle marquee = canvas.marquee();
        if (marquee != null) {
            g.setColor(new Color(0x3D8BFD));
            g.drawRect(marquee.x, marquee.y, marquee.width, marquee.height);
            g.setColor(new Color(0x3D, 0x8B, 0xFD, 0x22));
            g.fillRect(marquee.x, marquee.y, marquee.width, marquee.height);
        }
    }

    private static void paintLane(Graphics2D g, PianoRollView view, MidiDocumentView host,
            MidiDocument document, int laneIndex, Track track, Rectangle clip) {
        int ppq = document.ppq();
        TimelineModel timeline = view.timeline();
        int laneTop = view.laneTop(laneIndex);
        int laneHeight = view.laneHeight();
        PitchRange range = track.yRange();
        boolean cHighlight = view.cHighlightVisible();

        for (int pitch = range.high() - 1; pitch >= range.low(); pitch--) {
            int y = view.rowTop(track, laneIndex, pitch);
            int bottom = view.rowBottom(track, laneIndex, pitch);
            int rowHeight = Math.max(1, bottom - y);
            boolean isC = pitch % 12 == 0;
            g.setColor(isBlackKey(pitch) ? Theme.ROW_BLACK : Theme.ROW_WHITE);
            g.fillRect(clip.x, y, clip.width, rowHeight);
            if (isC) {
                if (cHighlight) {
                    // 只在行内部涂一层半透明黄色（不加边框、不改底色）
                    g.setColor(Theme.C_TINT);
                    g.fillRect(clip.x, y, clip.width, rowHeight);
                } else {
                    g.setColor(Theme.ROW_OCTAVE_LINE);
                    g.drawLine(clip.x, bottom - 1, clip.x + clip.width, bottom - 1);
                }
            }
        }

        long firstTick = timeline.tickAtX(clip.x, ppq);
        long lastTick = timeline.tickAtX(clip.x + clip.width, ppq);
        boolean active = host.activeTrack() == track;
        if (active) {
            // 活动泳道：只在行背景上叠一层很淡的蓝（不染到平行线上）
            g.setColor(Theme.LANE_ACTIVE_TINT);
            g.fillRect(clip.x, laneTop, clip.width, laneHeight - 1);
        }
        long beatTicks = TimeMath.beatTicks(document, firstTick);
        long barTicks = TimeMath.barTicks(document, firstTick);
        long step = host.grid().step(ppq); // 0 = 网格分辨率关闭，细分层不画
        paintTimeGrid(g, view, host, timeline, ppq, laneTop, laneHeight,
                firstTick, lastTick, step, beatTicks, barTicks);

        boolean laneActive = host.activeTrack() == track;
        List<Note> notes = track.notes();
        int startIndex = firstIndexAtOrAfter(notes, firstTick);
        int backSteps = 0;
        while (startIndex > 0 && backSteps++ < 400 && notes.get(startIndex - 1).endTick() >= firstTick) {
            startIndex--;
        }
        for (int i = startIndex; i < notes.size(); i++) {
            Note note = notes.get(i);
            if (note.startTick() > lastTick) {
                break;
            }
            if (note.endTick() < firstTick || note.pitch() < range.low() || note.pitch() >= range.high()) {
                continue;
            }
            paintParallelLine(g, view, host, timeline, ppq, laneIndex, track, note, laneActive);
        }

        g.setColor(Theme.LANE_SEPARATOR);
        g.drawLine(clip.x, laneTop + laneHeight - 1, clip.x + clip.width, laneTop + laneHeight - 1);
        if (laneActive) {
            // 左侧窄高亮条：位置按“视口左边界”固定，不能跟着局部重绘的 clip 跑，
            // 否则播放头每重绘一窄条就在那里留下一根竖线。
            int left = view.visibleRect().x;
            if (left < clip.x + clip.width && left + ACTIVE_LANE_BAR > clip.x) {
                g.setColor(Theme.ACCENT);
                g.fillRect(left, laneTop, ACTIVE_LANE_BAR, laneHeight - 1);
            }
        }
    }

    /** 细分网格最细能到几像素——再密就不画，否则整片糊成一块，小节和拍完全分不出来。 */
    private static final int MIN_SUBGRID_PIXELS = 8;

    /**
     * 时间网格三层：小节 > 拍 > 细分。
     *
     * <p>
     * 三层各用一档颜色、且下层不重复画在上层的位置上，这样一眼能分出「这里换小节了 / 这里是拍」；
     * 细分线在缩放不足（间距 < {@link #MIN_SUBGRID_PIXELS}px）时直接不画。
     * </p>
     */
    private static void paintTimeGrid(Graphics2D g, PianoRollView view, MidiDocumentView host,
            TimelineModel timeline, int ppq, int laneTop, int laneHeight,
            long firstTick, long lastTick, long step, long beatTicks, long barTicks) {
        int bottom = laneTop + laneHeight - 1;
        // 1) 细分
        if (host.grid().visible() && step > 0 && tickWidth(timeline, ppq, step) >= MIN_SUBGRID_PIXELS) {
            g.setColor(Theme.GRID_LINE);
            for (long tick = (firstTick / step) * step; tick <= lastTick; tick += step) {
                if ((beatTicks > 0 && tick % beatTicks == 0) || (barTicks > 0 && tick % barTicks == 0)) {
                    continue;
                }
                int x = timeline.xAtTick(tick, ppq);
                g.drawLine(x, laneTop, x, bottom);
            }
        }
        // 2) 拍
        if (beatTicks > 0) {
            g.setColor(Theme.BEAT_LINE);
            for (long tick = (firstTick / beatTicks) * beatTicks; tick <= lastTick; tick += beatTicks) {
                if (barTicks > 0 && tick % barTicks == 0) {
                    continue;
                }
                int x = timeline.xAtTick(tick, ppq);
                g.drawLine(x, laneTop, x, bottom);
            }
        }
        // 3) 小节（最亮、粗一档，最后画）
        if (barTicks > 0) {
            g.setColor(Theme.BAR_LINE);
            java.awt.Stroke old = g.getStroke();
            g.setStroke(new java.awt.BasicStroke(2f));
            for (long tick = (firstTick / barTicks) * barTicks; tick <= lastTick; tick += barTicks) {
                int x = timeline.xAtTick(tick, ppq);
                g.drawLine(x, laneTop, x, bottom);
            }
            g.setStroke(old);
        }
    }

    /** 一段 tick 占多少像素。 */
    private static int tickWidth(TimelineModel timeline, int ppq, long ticks) {
        return Math.max(1, timeline.xAtTick(ticks, ppq) - timeline.xAtTick(0, ppq));
    }

    private static void paintParallelLine(Graphics2D g, PianoRollView view, MidiDocumentView host,
            TimelineModel timeline, int ppq, int laneIndex, Track track,
            Note note, boolean laneActive) {
        int x1 = timeline.xAtTick(note.startTick(), ppq);
        int x2 = timeline.xAtTick(note.endTick(), ppq);
        int width = Math.max(3, x2 - x1);
        int rowY = view.rowTop(track, laneIndex, note.pitch());
        int rowHeight = Math.max(1, view.rowBottom(track, laneIndex, note.pitch()) - rowY);
        boolean selected = host.selection().contains(note);

        if (note.isRest()) {
            paintRest(g, x1, x2, width, rowY, rowHeight, selected, laneActive);
            return;
        }

        int maxThickness = Math.max(1, rowHeight - 1);
        int thickness = 1 + (int) Math.round((maxThickness - 1) * (note.velocity() / 127.0));
        thickness = Math.max(1, Math.min(thickness, maxThickness));
        int y = rowY + (rowHeight - thickness) / 2;

        Color base = track.color();
        boolean sounding = host.isNoteSounding(note);

        Color fill;
        if (sounding) {
            fill = blend(base, Theme.SOUNDING, 0.6);
        } else {
            fill = laneActive ? base : base.darker();
        }
        g.setColor(fill);
        g.fillRect(x1, y, width, thickness);

        if (selected) {
            g.setColor(Theme.SELECTION_OUTLINE);
            g.drawRect(x1, y, Math.max(2, width - 1), thickness - 1);
        }
    }

    /** 休止符：红色线段（与平行线同地位，可选中 / 拖动 / 锁定，但不发声）。 */
    private static void paintRest(Graphics2D g, int x1, int x2, int width, int rowY, int rowHeight,
            boolean selected, boolean laneActive) {
        int thickness = rowHeight >= 8 ? 3 : 2;
        int barY = rowY + Math.max(1, (rowHeight - thickness) / 2);
        g.setColor(laneActive ? Theme.REST_COLOR : Theme.REST_COLOR.darker());
        g.fillRect(x1, barY, width, Math.min(thickness, Math.max(1, rowHeight)));
        // 两端小竖线，让休止符看起来是"一段"而不是"一条线"
        if (rowHeight >= 5) {
            int top = rowY + 1;
            int height = rowHeight - 2;
            g.fillRect(x1, top, 1, height);
            g.fillRect(Math.max(x1, x2 - 1), top, 1, height);
        }
        if (selected) {
            g.setColor(Theme.REST_SELECTED);
            g.drawRect(x1, barY - 1, Math.max(2, width - 1), thickness + 1);
        }
    }

    /** 收尾锁定：在上一条的尾部画空心圆（跨音高时连一条竖线）。 */
    private static void paintLocks(Graphics2D g, PianoRollView view,
            MidiDocument document, Rectangle clip) {
        int ppq = document.ppq();
        TimelineModel timeline = view.timeline();
        for (int laneIndex = 0; laneIndex < document.tracks().size(); laneIndex++) {
            Track track = document.tracks().get(laneIndex);
            int laneTop = view.laneTop(laneIndex);
            if (laneTop > clip.y + clip.height || laneTop + view.laneHeight() < clip.y) {
                continue;
            }
            for (Note follower : track.notes()) {
                if (!follower.locked()) {
                    continue;
                }
                Note leader = track.noteById(follower.lockLeaderId());
                if (leader == null) {
                    continue;
                }
                int x = timeline.xAtTick(leader.endTick(), ppq);
                int y = rowCenter(view, track, laneIndex, leader.pitch());
                int followerY = rowCenter(view, track, laneIndex, follower.pitch());
                if (x < clip.x - 8 || x > clip.x + clip.width + 8) {
                    continue;
                }
                if (followerY != y) {
                    g.setColor(new Color(0xE7, 0xEC, 0xF3, 0x88));
                    g.drawLine(x, Math.min(y, followerY), x, Math.max(y, followerY));
                }
                // 空心圆：先用底色把圆内挖空，再描边，避免看起来像个小方块
                int radius = 4;
                g.setColor(rowBackColor(leader.pitch()));
                g.fillOval(x - radius + 1, y - radius + 1, radius * 2 - 2, radius * 2 - 2);
                g.setColor(Theme.LOCK_RING);
                g.drawOval(x - radius, y - radius, radius * 2, radius * 2);
            }
        }
    }

    private static int rowCenter(PianoRollView view, Track track, int laneIndex, int pitch) {
        int top = view.rowTop(track, laneIndex, pitch);
        int bottom = view.rowBottom(track, laneIndex, pitch);
        return (top + bottom) / 2;
    }

    /** 某一音高所在行的底色（用于把锁定圆点挖成空心）。 */
    private static Color rowBackColor(int pitch) {
        return isBlackKey(pitch) ? Theme.ROW_BLACK : Theme.ROW_WHITE;
    }

    private static void paintPlayhead(Graphics2D g, PianoRollView view, MidiDocumentView host, Rectangle clip) {
        int ppq = host.document().ppq();
        int x = view.timeline().xAtTick(host.playheadTick(), ppq);
        if (x < clip.x - 8 || x > clip.x + clip.width + 8) {
            return;
        }
        // 画布里只画竖线。顶端的「向下三角」由顶部标尺（TimeRuler，作为列头不参与垂直滚动）负责。
        // 原因：画布里任何贴着视口边界画的装饰都扛不住垂直滚动 —— Swing 滚屏是「位块拷贝 + 只补新露出的
        // 一条带」，旧箭头像素会被连同内容一起搬走，于是屏上留下一串旧箭头（向上翻时尤其明显）。
        g.setColor(Theme.PLAYHEAD);
        g.drawLine(x, clip.y, x, clip.y + clip.height);
    }

    /**
     * 输入模式的光标：红色圆点（+ 一根很淡的竖线帮助对齐 tick）。
     *
     * <p>
     * 按着空格时，圆点后面会长出一条半透明「幽灵条」，长度就是已经按住的真实时长，
     * 松手时它会变成真正的平行线。
     * </p>
     */
    private static void paintInputCursor(Graphics2D g, PianoRollView view, MidiDocumentView host,
            MidiDocument document, Rectangle clip) {
        if (!host.inputMode()) {
            return;
        }
        Track track = host.activeTrack();
        if (track == null) {
            return;
        }
        int laneIndex = document.tracks().indexOf(track);
        if (laneIndex < 0) {
            return;
        }
        int laneTop = view.laneTop(laneIndex);
        int laneHeight = view.laneHeight();
        if (laneTop > clip.y + clip.height || laneTop + laneHeight < clip.y) {
            return;
        }
        int ppq = document.ppq();
        TimelineModel timeline = view.timeline();
        long cursorTick = host.inputCursorTick();
        int x = timeline.xAtTick(cursorTick, ppq);

        int pitch = host.inputCursorPitch();
        int rowTop = view.rowTop(track, laneIndex, pitch);
        int rowBottom = view.rowBottom(track, laneIndex, pitch);
        int rowHeight = Math.max(1, rowBottom - rowTop);
        int y = (rowTop + rowBottom) / 2;

        // 正在输入：已按住的时长画成幽灵条
        long pending = host.pendingInputTicks();
        if (pending > 0) {
            int x2 = timeline.xAtTick(cursorTick + pending, ppq);
            g.setColor(Theme.INPUT_GHOST);
            g.fillRect(x, rowTop, Math.max(2, x2 - x), Math.max(2, rowHeight - 1));
        }

        if (x < clip.x - 8 || x > clip.x + clip.width + 8) {
            return;
        }
        // 竖线：让圆点的时间位置一眼可读（按 tick 绝对定位，滚动时跟着内容走，不会留残影）
        g.setColor(Theme.INPUT_GUIDE);
        g.drawLine(x, laneTop, x, laneTop + laneHeight - 1);

        int radius = Math.max(2, Math.min(5, rowHeight / 2 + 1));
        g.setColor(Theme.INPUT_CURSOR);
        g.fillOval(x - radius, y - radius, radius * 2, radius * 2);
    }

    /** 牌子每格的边长：整体是 2a（长）× a（高），中间一条竖线分成两个正方形。 */
    private static final int INPUT_BADGE_CELL = 20;

    /**
     * 输入模式：按住输入（空格 / Q / 小键盘数字）时，浮在「按下之前光标位置」旁边的小牌。
     *
     * <p>
     * 左格是科学记音法（MIDI 60 = C4，与左侧键盘标尺一致），右格是当前调下的简谱数字；
     * 松手后由 {@code MidiDocumentView} 再让它停留 1 秒（{@code inputBadgeVisible()}）。
     * </p>
     */
    private static void paintInputBadge(Graphics2D g, PianoRollView view, MidiDocumentView host,
            MidiDocument document, Rectangle clip) {
        if (!host.inputBadgeVisible()) {
            return;
        }
        Track track = host.inputBadgeTrack() != null ? host.inputBadgeTrack() : host.activeTrack();
        if (track == null) {
            return;
        }
        int laneIndex = document.tracks().indexOf(track);
        if (laneIndex < 0) {
            return;
        }
        int cell = INPUT_BADGE_CELL;
        int width = cell * 2;
        int height = cell;
        int pitch = host.inputBadgePitch();
        int px = view.timeline().xAtTick(host.inputBadgeTick(), document.ppq());
        int py = (view.rowTop(track, laneIndex, pitch) + view.rowBottom(track, laneIndex, pitch)) / 2;

        // 贴在光标右上方，离一点距离；靠近视口边缘时夹回可见区域内（否则跟着内容滚出去会看不见）
        Rectangle visible = view.visibleRect();
        int x = px + 14;
        int y = py - height - 14;
        x = Math.max(visible.x + 2, Math.min(x, visible.x + visible.width - width - 2));
        y = Math.max(visible.y + 2, Math.min(y, visible.y + visible.height - height - 2));
        if (x + width < clip.x || x > clip.x + clip.width || y + height < clip.y || y > clip.y + clip.height) {
            return;
        }

        g.setColor(Theme.INPUT_BADGE_FILL);
        g.fillRect(x, y, width, height);
        g.setColor(Theme.INPUT_BADGE_BORDER);
        g.drawRect(x, y, width - 1, height - 1);
        g.drawLine(x + cell, y, x + cell, y + height - 1);
        g.setColor(Theme.INPUT_BADGE_TEXT);
        g.setFont(Theme.SMALL_FONT);
        drawCentered(g, host.inputBadgePitchName(), x, y, cell, height);
        drawCentered(g, host.inputBadgeDegree(), x + cell, y, cell, height);
    }

    private static void drawCentered(Graphics2D g, String text, int x, int y, int width, int height) {
        FontMetrics metrics = g.getFontMetrics();
        int tx = x + (width - metrics.stringWidth(text)) / 2;
        int ty = y + (height - metrics.getHeight()) / 2 + metrics.getAscent();
        g.drawString(text, tx, ty);
    }

    private static void paintLoopRegion(Graphics2D g, PianoRollView view, MidiDocumentView host, Rectangle clip) {
        if (!host.loopEnabled()) {
            return;
        }
        int ppq = host.document().ppq();
        int x1 = view.timeline().xAtTick(host.loopStart(), ppq);
        int x2 = view.timeline().xAtTick(host.loopEnd(), ppq);
        if (x2 < clip.x || x1 > clip.x + clip.width) {
            return;
        }
        g.setColor(new Color(0x3D8BFD));
        g.drawLine(x1, clip.y, x1, clip.y + clip.height);
        g.drawLine(x2, clip.y, x2, clip.y + clip.height);
        g.setColor(new Color(0x3D, 0x8B, 0xFD, 0x1A));
        g.fillRect(x1, clip.y, Math.max(1, x2 - x1), clip.height);
    }

    static boolean isBlackKey(int pitch) {
        int semitone = Math.floorMod(pitch, 12);
        return semitone == 1 || semitone == 3 || semitone == 6 || semitone == 8 || semitone == 10;
    }

    private static Color blend(Color base, Color other, double amount) {
        return new Color(
                (int) (base.getRed() + (other.getRed() - base.getRed()) * amount),
                (int) (base.getGreen() + (other.getGreen() - base.getGreen()) * amount),
                (int) (base.getBlue() + (other.getBlue() - base.getBlue()) * amount));
    }

    private static int firstIndexAtOrAfter(List<Note> notes, long tick) {
        int low = 0;
        int high = notes.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (notes.get(mid).startTick() < tick) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }
}
