package com.example.mideditor.ui.pianoroll;

import java.awt.Component;

import javax.swing.BoxLayout;
import javax.swing.JPanel;

import com.example.mideditor.domain.Track;
import com.example.mideditor.ui.theme.Theme;

/** 音轨头列表（行头）。 */
public final class TrackHeaderColumn extends JPanel {

    private final PianoRollView view;

    public TrackHeaderColumn(PianoRollView view) {
        this.view = view;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(Theme.MENU_BACKGROUND);
        setBorder(javax.swing.BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER));
        // 裸滚轮：在这里滚动「一屏放不下的音轨」（频谱滚动交给卷帘 / 键盘标尺）
        addMouseWheelListener(this::onWheel);
    }

    private void onWheel(java.awt.event.MouseWheelEvent e) {
        int notches = PianoRollView.wheelNotches(e);
        if (notches == 0) {
            return;
        }
        if (e.isControlDown()) {
            view.wheelZoomPitch(view.activeTrackOrNearest(e.getY()), laneAt(e.getY()), e.getY(), notches);
            view.host().app().refreshApplicationState();
        } else if (e.isAltDown()) {
            var visible = view.visibleRect();
            view.wheelZoomTime(visible.x + visible.width / 2, notches);
            view.host().app().refreshApplicationState();
        } else if (e.isShiftDown()) {
            view.wheelScrollTime(notches);
        } else {
            view.wheelScrollLanes(notches);
        }
        e.consume();
    }

    private int laneAt(int y) {
        return Math.max(0, view.laneIndexAt(y));
    }

    public void rebuild() {
        removeAll();
        for (Track track : view.host().document().tracks()) {
            TrackHeaderRow row = new TrackHeaderRow(view, track);
            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            add(row);
            installWheelForward(row);
        }
        revalidate();
        repaint();
    }

    /** 音轨头由很多子控件组成，把滚轮事件统一转到音轨列表滚动（可交互控件除外）。 */
    private void installWheelForward(java.awt.Component component) {
        if (component == null) {
            return;
        }
        if (!(component instanceof javax.swing.JButton)
                && !(component instanceof javax.swing.JToggleButton)
                && !(component instanceof javax.swing.JSlider)
                && !(component instanceof javax.swing.JComboBox)
                && !(component instanceof javax.swing.JTextField)) {
            component.addMouseWheelListener(this::onWheel);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                installWheelForward(child);
            }
        }
    }

    void refreshRows() {
        for (Component component : getComponents()) {
            if (component instanceof TrackHeaderRow row) {
                row.refreshFromModel();
            }
        }
    }
}
