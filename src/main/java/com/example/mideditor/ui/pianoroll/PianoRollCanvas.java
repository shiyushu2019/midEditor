package com.example.mideditor.ui.pianoroll;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import com.example.mideditor.command.AppCommandIds;
import com.example.mideditor.command.CommandRegistry;
import com.example.mideditor.document.MidiDocument;
import com.example.mideditor.document.MidiDocumentView;
import com.example.mideditor.domain.Note;
import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.theme.Theme;

/** 钢琴卷帘画布：绘制 + 拖拽 / 框选 / 右键 / 播放头拖拽。 */
public final class PianoRollCanvas extends JComponent {

    private enum Mode {
        NONE, MOVE, RESIZE, MARQUEE, PLAYHEAD
    }

    private final PianoRollView view;
    private Mode mode = Mode.NONE;
    private int pressX;
    private int pressY;
    private long pressTick;
    private int pressPitch;
    private int dragLane;
    private Track dragTrack;
    private Note pressedNote;
    private boolean dragged;
    private Rectangle marquee;

    public PianoRollCanvas(PianoRollView view) {
        this.view = view;
        setOpaque(true);
        setBackground(Theme.CANVAS_BACKGROUND);
        setFocusable(true);
        Handler handler = new Handler();
        addMouseListener(handler);
        addMouseMotionListener(handler);
        addMouseWheelListener(handler);
        installArrowKeys();
    }

    /** 方向键移动选中框（画布有焦点时优先于滚动面板的同键绑定）。 */
    private void installArrowKeys() {
        CommandRegistry registry = view.host().app().registry();
        registry.bindLocal(this, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_UP, 0),
                AppCommandIds.NOTE_SELECT_UP);
        registry.bindLocal(this, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DOWN, 0),
                AppCommandIds.NOTE_SELECT_DOWN);
        registry.bindLocal(this, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_LEFT, 0),
                AppCommandIds.NOTE_SELECT_LEFT);
        registry.bindLocal(this, javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_RIGHT, 0),
                AppCommandIds.NOTE_SELECT_RIGHT);
        registry.bindLocal(this,
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_RIGHT,
                        java.awt.event.InputEvent.SHIFT_DOWN_MASK),
                AppCommandIds.NOTE_TRIM_LEFT);
        registry.bindLocal(this,
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_LEFT,
                        java.awt.event.InputEvent.SHIFT_DOWN_MASK),
                AppCommandIds.NOTE_TRIM_RIGHT);
        registry.bindLocal(this,
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_RIGHT,
                        java.awt.event.InputEvent.CTRL_DOWN_MASK),
                AppCommandIds.NOTE_EXTEND_RIGHT);
        registry.bindLocal(this,
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_LEFT,
                        java.awt.event.InputEvent.CTRL_DOWN_MASK),
                AppCommandIds.NOTE_EXTEND_LEFT);
    }

    public PianoRollView view() {
        return view;
    }

    public Rectangle marquee() {
        return marquee;
    }

    private MidiDocumentView host() {
        return view.host();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            PianoRollPainter.paint(this, g2);
        } finally {
            g2.dispose();
        }
    }

    private final class Handler extends MouseAdapter {

        @Override
        public void mousePressed(MouseEvent e) {
            requestFocusInWindow();
            MidiDocument document = host().document();
            int ppq = document.ppq();
            HitInfo hit = view.hitTest(e.getX(), e.getY());

            if (SwingUtilities.isRightMouseButton(e)) {
                openContextMenu(e, hit);
                return;
            }
            if (hit.track() == null) {
                return;
            }

            int playheadX = view.timeline().xAtTick(host().playheadTick(), ppq);
            if (Math.abs(e.getX() - playheadX) <= 4) {
                mode = Mode.PLAYHEAD;
                host().seekTick(view.timeline().tickAtX(e.getX(), ppq));
                return;
            }

            if (hit.onNote()) {
                host().setActiveTrack(hit.track());
                if (e.isShiftDown() || e.isControlDown()) {
                    host().selection().toggle(hit.note());
                } else if (!host().selection().contains(hit.note())) {
                    host().selection().setSingle(hit.note());
                }
                if (host().selection().contains(hit.note())) {
                    mode = hit.rightEdge() ? Mode.RESIZE : Mode.MOVE;
                    dragTrack = hit.track();
                    dragLane = hit.laneIndex();
                    pressedNote = hit.note();
                    dragged = false;
                    pressX = e.getX();
                    pressY = e.getY();
                    pressTick = view.timeline().tickAtX(e.getX(), ppq);
                    pressPitch = hit.pitch();
                    host().beginSelectionEdit();
                }
                return;
            }

            if (!e.isShiftDown()) {
                host().selection().clear();
            }
            host().setActiveTrack(hit.track());
            mode = Mode.MARQUEE;
            pressX = e.getX();
            pressY = e.getY();
            marquee = new Rectangle(pressX, pressY, 0, 0);
            repaint();
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            MidiDocument document = host().document();
            int ppq = document.ppq();
            switch (mode) {
                case PLAYHEAD -> host().seekTick(view.timeline().tickAtX(e.getX(), ppq));
                case MARQUEE -> {
                    marquee = new Rectangle(
                            Math.min(pressX, e.getX()), Math.min(pressY, e.getY()),
                            Math.abs(e.getX() - pressX), Math.abs(e.getY() - pressY));
                    repaint();
                }
                case MOVE -> {
                    if (dragTrack == null) {
                        return;
                    }
                    dragged = true;
                    long rawTick = view.timeline().tickAtX(e.getX(), ppq) - pressTick;
                    int currentPitch = view.pitchAt(dragTrack, dragLane, e.getY());
                    host().applySelectionMove(rawTick, currentPitch - pressPitch);
                    view.repaint();
                }
                case RESIZE -> {
                    dragged = true;
                    long rawTick = view.timeline().tickAtX(e.getX(), ppq) - pressTick;
                    host().applySelectionResize(rawTick);
                    view.repaint();
                }
                default -> {
                }
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            Track auditionTrack = dragTrack;
            Note auditionNote = pressedNote;
            boolean audition = mode == Mode.MOVE && !dragged && auditionNote != null;
            switch (mode) {
                case MOVE -> host().commitSelectionEdit("移动平行线");
                case RESIZE -> host().commitSelectionEdit("调整平行线长度");
                case MARQUEE -> {
                    Rectangle rect = marquee;
                    marquee = null;
                    if (rect != null && (rect.width > 3 || rect.height > 3)) {
                        host().selectInRect(rect);
                    }
                    repaint();
                }
                default -> {
                }
            }
            mode = Mode.NONE;
            dragTrack = null;
            pressedNote = null;
            dragged = false;
            if (audition) {
                host().auditionNote(auditionTrack, auditionNote);
            }
            mouseMoved(e);
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            HitInfo hit = view.hitTest(e.getX(), e.getY());
            if (hit.onNote() && hit.rightEdge()) {
                setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR));
            } else {
                setCursor(java.awt.Cursor.getDefaultCursor());
            }
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                HitInfo hit = view.hitTest(e.getX(), e.getY());
                if (hit.onEmptySpace()) {
                    int ppq = host().document().ppq();
                    long snapped = host().grid().snap(hit.tick(), ppq);
                    host().addNoteAt(hit.track(), snapped, hit.pitch());
                }
            }
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            int notches = PianoRollView.wheelNotches(e);
            if (notches == 0) {
                return;
            }
            HitInfo hit = view.hitTest(e.getX(), e.getY());
            Track track = hit.track() != null ? hit.track() : host().activeTrack();
            int lane = hit.laneIndex() >= 0 ? hit.laneIndex()
                    : Math.max(0, host().document().tracks().indexOf(track));

            if (e.isControlDown()) {
                // Ctrl+滚轮：上下缩放（以鼠标处音高为锚点）
                if (track != null) {
                    view.wheelZoomPitch(track, lane, e.getY(), notches);
                    host().setActiveTrack(track);
                    host().app().refreshApplicationState();
                }
            } else if (e.isAltDown()) {
                // Alt+滚轮：左右缩放（以鼠标处 tick 为锚点）
                view.wheelZoomTime(e.getX(), notches);
                host().app().refreshApplicationState();
            } else if (e.isShiftDown()) {
                // Shift+滚轮：左右滚动
                view.wheelScrollTime(notches);
            } else {
                // 裸滚轮：上下滚动频谱
                view.wheelScrollSpectrum(track, lane, notches);
                if (track != null) {
                    host().setActiveTrack(track);
                }
                host().app().refreshApplicationState();
            }
            e.consume();
        }

        private void openContextMenu(MouseEvent e, HitInfo hit) {
            MidiDocument document = host().document();
            long tick = hit.tick();
            if (hit.onNote()) {
                host().setActiveTrack(hit.track());
                if (!host().selection().contains(hit.note())) {
                    host().selection().setSingle(hit.note());
                }
                host().setPendingSplit(tick);
            } else if (hit.onEmptySpace()) {
                host().setActiveTrack(hit.track());
                host().selection().clear();
                host().setPendingAdd(host().grid().snap(tick, document.ppq()), hit.pitch());
                host().setPendingSplit(tick);
            }
            PianoRollContextMenu.show(PianoRollCanvas.this, hit, new Point(e.getX(), e.getY()));
        }
    }
}
