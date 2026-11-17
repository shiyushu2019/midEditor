package com.example.mideditor.ui.pianoroll;

import java.awt.Color;
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
        paintPlayhead(g, view, host, clip);

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
        int visibleRows = Math.min(range.rows(), Theme.LANE_ROWS);
        int lowestVisible = Math.max(range.low(), range.high() - visibleRows);

        for (int pitch = range.high() - 1; pitch >= lowestVisible; pitch--) {
            int y = view.rowTop(track, laneIndex, pitch);
            g.setColor(isBlackKey(pitch) ? Theme.ROW_BLACK : Theme.ROW_WHITE);
            g.fillRect(clip.x, y, clip.width, Theme.ROW_HEIGHT);
            if (pitch % 12 == 0) {
                g.setColor(Theme.ROW_OCTAVE_LINE);
                g.drawLine(clip.x, y + Theme.ROW_HEIGHT - 1, clip.x + clip.width, y + Theme.ROW_HEIGHT - 1);
            }
        }

        long firstTick = timeline.tickAtX(clip.x, ppq);
        long lastTick = timeline.tickAtX(clip.x + clip.width, ppq);
        long beatTicks = TimeMath.beatTicks(document, firstTick);
        long barTicks = TimeMath.barTicks(document, firstTick);
        long step = host.grid().step(ppq);
        if (step <= 0) {
            step = Math.max(1, ppq / 4);
        }
        if (host.grid().visible()) {
            long start = (firstTick / step) * step;
            for (long tick = start; tick <= lastTick; tick += step) {
                g.setColor(lineColor(tick, barTicks, beatTicks));
                int x = timeline.xAtTick(tick, ppq);
                g.drawLine(x, laneTop, x, laneTop + laneHeight - 1);
            }
        } else {
            long start = (firstTick / beatTicks) * beatTicks;
            for (long tick = start; tick <= lastTick; tick += beatTicks) {
                g.setColor(lineColor(tick, barTicks, beatTicks));
                int x = timeline.xAtTick(tick, ppq);
                g.drawLine(x, laneTop, x, laneTop + laneHeight - 1);
            }
        }

        boolean active = host.activeTrack() == track;
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
            paintParallelLine(g, view, host, timeline, ppq, laneIndex, track, note, active);
        }

        g.setColor(Theme.LANE_SEPARATOR);
        g.drawLine(clip.x, laneTop + laneHeight - 1, clip.x + clip.width, laneTop + laneHeight - 1);
        if (active) {
            g.setColor(new Color(0x3D8BFD));
            g.drawRect(0, laneTop, Math.max(clip.width, view.getWidth()) - 1, laneHeight - 1);
        }
    }

    private static void paintParallelLine(Graphics2D g, PianoRollView view, MidiDocumentView host,
            TimelineModel timeline, int ppq, int laneIndex, Track track,
            Note note, boolean laneActive) {
        int x1 = timeline.xAtTick(note.startTick(), ppq);
        int x2 = timeline.xAtTick(note.endTick(), ppq);
        int width = Math.max(3, x2 - x1);
        int rowY = view.rowTop(track, laneIndex, note.pitch());
        int maxThickness = Math.max(3, Theme.ROW_HEIGHT - 1);
        int thickness = 2 + (int) Math.round(maxThickness * (note.velocity() / 127.0));
        thickness = Math.min(thickness, maxThickness);
        int y = rowY + (Theme.ROW_HEIGHT - thickness) / 2;

        Color base = track.color();
        boolean selected = host.selection().contains(note);
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

    private static void paintPlayhead(Graphics2D g, PianoRollView view, MidiDocumentView host, Rectangle clip) {
        int ppq = host.document().ppq();
        int x = view.timeline().xAtTick(host.playheadTick(), ppq);
        if (x < clip.x - 8 || x > clip.x + clip.width + 8) {
            return;
        }
        g.setColor(Theme.PLAYHEAD);
        g.drawLine(x, clip.y, x, clip.y + clip.height);
        int[] xs = { x - 5, x + 5, x };
        int[] ys = { clip.y, clip.y, clip.y + 7 };
        g.fillPolygon(xs, ys, 3);
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

    private static Color lineColor(long tick, long barTicks, long beatTicks) {
        if (barTicks > 0 && tick % barTicks == 0) {
            return Theme.BAR_LINE;
        }
        if (beatTicks > 0 && tick % beatTicks == 0) {
            return Theme.BEAT_LINE;
        }
        return Theme.GRID_LINE;
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
