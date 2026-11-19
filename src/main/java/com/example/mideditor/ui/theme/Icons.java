package com.example.mideditor.ui.theme;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

import javax.swing.Icon;

/** 矢量工具图标（无需外部图片资源）。 */
public final class Icons {

    public static final int SIZE = 16;

    public static final Icon NEW = glyph("new");
    public static final Icon OPEN = glyph("open");
    public static final Icon SAVE = glyph("save");
    public static final Icon UNDO = glyph("undo");
    public static final Icon REDO = glyph("redo");
    public static final Icon CUT = glyph("cut");
    public static final Icon COPY = glyph("copy");
    public static final Icon PASTE = glyph("paste");
    public static final Icon PLAY = glyph("play");
    public static final Icon PAUSE = glyph("pause");
    public static final Icon STOP = glyph("stop");
    public static final Icon REWIND = glyph("rewind");
    public static final Icon LOOP = glyph("loop");
    public static final Icon METRONOME = glyph("metronome");
    public static final Icon DELETE = glyph("delete");
    public static final Icon ADD = glyph("add");
    public static final Icon GRID = glyph("grid");
    public static final Icon INPUT_MODE = glyph("input");
    public static final Icon BEAT_SNAP = glyph("snap");

    private Icons() {
    }

    public static Icon newDoc() {
        return NEW;
    }

    public static Icon open() {
        return OPEN;
    }

    public static Icon save() {
        return SAVE;
    }

    public static Icon undo() {
        return UNDO;
    }

    public static Icon redo() {
        return REDO;
    }

    public static Icon cut() {
        return CUT;
    }

    public static Icon copy() {
        return COPY;
    }

    public static Icon paste() {
        return PASTE;
    }

    public static Icon play() {
        return PLAY;
    }

    public static Icon pause() {
        return PAUSE;
    }

    public static Icon stop() {
        return STOP;
    }

    public static Icon rewind() {
        return REWIND;
    }

    public static Icon loop() {
        return LOOP;
    }

    public static Icon metronome() {
        return METRONOME;
    }

    public static Icon delete() {
        return DELETE;
    }

    public static Icon add() {
        return ADD;
    }

    public static Icon grid() {
        return GRID;
    }

    public static Icon inputMode() {
        return INPUT_MODE;
    }

    public static Icon beatSnap() {
        return BEAT_SNAP;
    }

    private static Icon glyph(String kind) {
        return new VectorIcon(kind);
    }

    private static final class VectorIcon implements Icon {
        private final String kind;

        VectorIcon(String kind) {
            this.kind = kind;
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                paintGlyph(g2, kind);
            } finally {
                g2.dispose();
            }
        }

        private static void paintGlyph(Graphics2D g2, String kind) {
            Color ink = new Color(0x27313D);
            Color accent = new Color(0x2F6FED);
            g2.setColor(ink);
            switch (kind) {
                case "new" -> {
                    g2.drawRect(3, 2, 9, 12);
                    g2.setColor(accent);
                    g2.drawLine(8, 5, 8, 11);
                    g2.drawLine(5, 8, 11, 8);
                }
                case "open" -> {
                    Path2D p = new Path2D.Float();
                    p.moveTo(2, 12);
                    p.lineTo(2, 4);
                    p.lineTo(6, 4);
                    p.lineTo(7.5, 6);
                    p.lineTo(14, 6);
                    p.lineTo(14, 12);
                    p.closePath();
                    g2.draw(p);
                }
                case "save" -> {
                    g2.drawRect(3, 2, 10, 12);
                    g2.drawRect(5, 2, 6, 4);
                    g2.drawRect(5, 9, 6, 5);
                }
                case "undo", "redo" -> {
                    boolean redo = kind.equals("redo");
                    Path2D p = new Path2D.Float();
                    p.moveTo(redo ? 13 : 3, 12);
                    p.lineTo(redo ? 13 : 3, 8);
                    p.curveTo(redo ? 13 : 3, 4.5, redo ? 9 : 7, 3.5, 6.5, 4.5);
                    p.curveTo(redo ? 9.5 : 6.5, 4.5, redo ? 5.5 : 10.5, 6, 4, 8);
                    g2.draw(p);
                }
                case "cut" -> {
                    g2.drawLine(4, 2, 12, 12);
                    g2.drawLine(12, 2, 4, 12);
                    g2.setColor(accent);
                    g2.drawOval(2, 10, 4, 4);
                }
                case "copy" -> {
                    g2.drawRect(3, 2, 8, 9);
                    g2.drawRect(6, 5, 8, 9);
                }
                case "paste" -> {
                    g2.drawRect(3, 3, 10, 11);
                    g2.drawRect(6, 1, 4, 3);
                }
                case "play" -> {
                    Path2D p = new Path2D.Float();
                    p.moveTo(4, 2.5);
                    p.lineTo(13, 8);
                    p.lineTo(4, 13.5);
                    p.closePath();
                    g2.setColor(new Color(0x1F7A4D));
                    g2.fill(p);
                }
                case "pause" -> {
                    g2.setColor(new Color(0x9A6B00));
                    g2.fillRect(4, 3, 3, 10);
                    g2.fillRect(9, 3, 3, 10);
                }
                case "stop" -> {
                    g2.setColor(new Color(0x8C2F39));
                    g2.fillRect(3, 3, 10, 10);
                }
                case "rewind" -> {
                    g2.setColor(new Color(0x27313D));
                    g2.fillRect(2, 3, 2, 10);
                    Path2D p = new Path2D.Float();
                    p.moveTo(13, 3);
                    p.lineTo(5, 8);
                    p.lineTo(13, 13);
                    p.closePath();
                    g2.fill(p);
                }
                case "loop" -> {
                    g2.setColor(accent);
                    g2.drawArc(2, 4, 12, 8, 30, 300);
                    Path2D p = new Path2D.Float();
                    p.moveTo(11, 2.5);
                    p.lineTo(14.5, 5.5);
                    p.lineTo(10.5, 6);
                    p.closePath();
                    g2.fill(p);
                }
                case "metronome" -> {
                    Path2D p = new Path2D.Float();
                    p.moveTo(5, 13);
                    p.lineTo(7.5, 3);
                    p.lineTo(10.5, 3);
                    p.lineTo(12, 13);
                    p.closePath();
                    g2.draw(p);
                    g2.setColor(accent);
                    g2.drawLine(8, 12, 11, 5);
                }
                case "delete" -> {
                    g2.setColor(new Color(0x8C2F39));
                    g2.drawRect(4, 4, 8, 10);
                    g2.drawLine(3, 3, 13, 3);
                }
                case "add" -> {
                    g2.setColor(accent);
                    g2.fillRoundRect(2, 2, 12, 12, 3, 3);
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine(8, 4, 8, 12);
                    g2.drawLine(4, 8, 12, 8);
                }
                case "snap" -> {
                    // 一根拍线 + 一个红点贴上去（吸附）
                    g2.setColor(ink);
                    g2.drawLine(9, 2, 9, 14);
                    g2.setColor(new Color(0xD8382B));
                    g2.fillOval(3, 6, 5, 5);
                }
                case "input" -> {
                    // 红色圆点（光标）+ 一根短横线（输入出来的长度）
                    g2.setColor(new Color(0xD8382B));
                    g2.fillOval(2, 6, 6, 6);
                    g2.setColor(accent);
                    g2.drawLine(10, 9, 14, 9);
                }
                case "grid" -> {
                    g2.setColor(new Color(0x27313D));
                    g2.drawRect(2, 2, 5, 5);
                    g2.drawRect(9, 2, 5, 5);
                    g2.drawRect(2, 9, 5, 5);
                    g2.setColor(accent);
                    g2.fillRect(9, 9, 5, 5);
                }
                default -> g2.drawRect(3, 3, 10, 10);
            }
        }
    }
}
