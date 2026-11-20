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
    /** 框选时鼠标在视口里的位置（自动滚动用；null = 不在框选中）。 */
    private Point marqueeViewportPoint;
    private final javax.swing.Timer marqueeScroller;
    private static final int MARQUEE_EDGE = 24;
    private static final int MARQUEE_SCROLL_STEP = 24;

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
        // 框选拖到视口边缘时鼠标往往不再移动，mouseDragged 也就不会再触发 —— 用定时器持续滚动
        marqueeScroller = new javax.swing.Timer(30, e -> autoScrollMarquee());
        marqueeScroller.setRepeats(true);
    }

    /** 方向键移动选中框（画布有焦点时优先于滚动面板的同键绑定）。上下键不参与。 */
    private void installArrowKeys() {
        CommandRegistry registry = view.host().app().registry();
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

    /** 框选时鼠标停在视口边缘就持续滚动（不需要继续移动鼠标）。 */
    private void updateMarqueeScroller() {
        if (marqueeViewportPoint == null) {
            marqueeScroller.stop();
            return;
        }
        Rectangle visible = view.visibleRect();
        boolean nearEdge = marqueeViewportPoint.x < MARQUEE_EDGE
                || marqueeViewportPoint.x > visible.width - MARQUEE_EDGE
                || marqueeViewportPoint.y < MARQUEE_EDGE
                || marqueeViewportPoint.y > visible.height - MARQUEE_EDGE;
        if (nearEdge && !marqueeScroller.isRunning()) {
            marqueeScroller.start();
        } else if (!nearEdge && marqueeScroller.isRunning()) {
            marqueeScroller.stop();
        }
    }

    /** 定时器：按鼠标停在边缘的方向滚一格，并把框选矩形跟着重算（画布坐标）。 */
    private void autoScrollMarquee() {
        if (mode != Mode.MARQUEE || marqueeViewportPoint == null) {
            marqueeScroller.stop();
            return;
        }
        javax.swing.JViewport viewport = view.scrollPane().getViewport();
        Rectangle visible = viewport.getViewRect();
        int dx = marqueeViewportPoint.x < MARQUEE_EDGE ? -MARQUEE_SCROLL_STEP
                : marqueeViewportPoint.x > visible.width - MARQUEE_EDGE ? MARQUEE_SCROLL_STEP : 0;
        int dy = marqueeViewportPoint.y < MARQUEE_EDGE ? -MARQUEE_SCROLL_STEP
                : marqueeViewportPoint.y > visible.height - MARQUEE_EDGE ? MARQUEE_SCROLL_STEP : 0;
        if (dx == 0 && dy == 0) {
            marqueeScroller.stop();
            return;
        }
        int x = Math.max(0, visible.x + dx);
        int y = Math.max(0, visible.y + dy);
        if (x == visible.x && y == visible.y) {
            return; // 已经到头了
        }
        viewport.setViewPosition(new Point(x, y));
        Rectangle scrolled = viewport.getViewRect();
        int canvasX = marqueeViewportPoint.x + scrolled.x;
        int canvasY = marqueeViewportPoint.y + scrolled.y;
        marquee = new Rectangle(Math.min(pressX, canvasX), Math.min(pressY, canvasY),
                Math.abs(canvasX - pressX), Math.abs(canvasY - pressY));
        repaint();
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
            // 记住“最后一次点击频谱的时间位置”：粘贴时内容就从这里开始（音高保持剪贴板原样）
            host().setPasteAnchor(hit.tick());

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
            marqueeViewportPoint = null;
            marqueeScroller.stop();
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
                    Rectangle visible = view.visibleRect();
                    marqueeViewportPoint = new Point(e.getX() - visible.x, e.getY() - visible.y);
                    updateMarqueeScroller();
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
            // 点边缘是「缩放」手势，但没真拖动就只是一次单击 → 一样要试听
            boolean audition = (mode == Mode.MOVE || mode == Mode.RESIZE) && !dragged && auditionNote != null;
            switch (mode) {
                case MOVE -> host().commitSelectionEdit("移动平行线");
                case RESIZE -> host().commitSelectionEdit("调整平行线长度");
                case MARQUEE -> {
                    marqueeScroller.stop();
                    marqueeViewportPoint = null;
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
                host().auditionNoteWithBadge(auditionTrack, auditionNote);
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
                // Alt+滚轮：左右缩放（以鼠标处 tick 为锚点）。
                // 只同步传输条上的缩放滑块，不做整应用刷新——缩放时逐格刷面板才是“卡卡的”的来源
                view.wheelZoomTime(e.getX(), notches);
                host().transportBar().refresh();
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
                host().setPendingAdd(host().grid().snap(tick, host().document().ppq()), hit.pitch());
                host().setPendingSplit(tick);
            }
            PianoRollContextMenu.show(PianoRollCanvas.this, hit, new Point(e.getX(), e.getY()));
        }
    }
}
